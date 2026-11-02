package app.mapa.routine

import kotlin.math.ln

enum class Phase { LEARNING, CALIBRATING, STEADY }

enum class TrainKind { FULL, REFRESH, ADOPT_NEW_ROUTINE }

/**
 * When the phone retrains: nightly through day 30, then a full retrain once a week
 * (the night after Sunday) with a nightly calibrator refresh. Only between 00:00 and
 * 05:00, on charge, after 30 idle minutes; if the phone was never on charge for two
 * nights running, a well-charged idle phone trains anyway.
 */
data class ScheduleConfig(
    val windowStartMinute: Int = 0,
    val windowEndMinute: Int = 5 * 60,
    val minIdleBins: Int = 2,
    val minBatteryOnCharge: Int = 30,
    val fallbackBattery: Int = 60,
    val fallbackAfterMissedNights: Int = 2,
    val learningDays: Int = 14,
    val calibratingDays: Int = 30,
)

class TrainingScheduler(private val config: ScheduleConfig, private val installDay: Int, private val dowOffset: Int) {
    private var lastNight = Int.MIN_VALUE
    private var currentDay = Int.MIN_VALUE
    private var missedNights = 0

    fun dayNumber(day: Int) = day - installDay + 1

    fun phase(day: Int): Phase = when (dayNumber(day)) {
        in Int.MIN_VALUE..config.learningDays -> Phase.LEARNING
        in config.learningDays + 1..config.calibratingDays -> Phase.CALIBRATING
        else -> Phase.STEADY
    }

    /** Monday, early morning: the night after Sunday. */
    fun weeklyNight(day: Int) = Bins.dow(day, dowOffset) == 0

    fun due(obs: BinObservation, lastInteractionT: Int?, adoptPending: Boolean): TrainKind? {
        val day = Bins.day(obs.t)
        if (day != currentDay) {
            if (currentDay != Int.MIN_VALUE && lastNight != currentDay) missedNights++
            currentDay = day
        }
        val m = Bins.minuteOfDay(obs.t)
        if (m < config.windowStartMinute || m >= config.windowEndMinute || lastNight == day) return null
        if (dayNumber(day) <= 1) return null
        val idle = lastInteractionT == null || obs.t - lastInteractionT >= config.minIdleBins
        val onCharge = obs.charging && obs.battery >= config.minBatteryOnCharge
        val fallback = missedNights >= config.fallbackAfterMissedNights && obs.battery >= config.fallbackBattery
        if (!idle || !(onCharge || fallback)) return null
        lastNight = day
        missedNights = 0
        return when {
            adoptPending -> TrainKind.ADOPT_NEW_ROUTINE
            phase(day) != Phase.STEADY -> TrainKind.FULL
            weeklyNight(day) -> TrainKind.FULL
            else -> TrainKind.REFRESH
        }
    }

    internal fun save(w: StateWriter) { w.tag("schedule").int(lastNight).int(currentDay).int(missedNights).newline() }
    internal fun load(r: StateReader) { r.tag("schedule"); lastNight = r.int(); currentDay = r.int(); missedNights = r.int() }
}

data class EngineConfig(
    val rules: RuleConfig = RuleConfig(),
    val alerts: AlertConfig = AlertConfig(),
    val guards: GuardConfig = GuardConfig(),
    val schedule: ScheduleConfig = ScheduleConfig(),
    /** False runs the rule baseline only (the model still runs in shadow for comparison). */
    val modelDecides: Boolean = true,
    /**
     * The model calls a silence unusual once ordinary silences this surprising happen
     * less often than this many times per week for this parent (0.25 = once in four weeks).
     */
    val modelMaxWeeklyRate: Double = 0.25,
    val minModelSilenceMinutes: Int = 60,
    /** The model's confidence bar: enough clean days and completed silences behind it. */
    val minReadyDays: Int = 7,
    val minReadySilences: Int = 30,
)

data class TickResult(
    val t: Int,
    val decision: StateDecision,
    val alerts: List<Alert>,
    val snapshot: SyncedSnapshot,
    val decider: Decider,
    /** Model's calibrated score for the current silence, shown as "x% confident". */
    val score: Double,
    val ruleUnusual: Boolean,
    val modelUnusual: Boolean,
    val modelReady: Boolean,
    val expectedByT: Int?,
    val trained: TrainKind?,
    val heldBack: Boolean,
    val trend: TrendReport?,
    /** The trained Hybrid's view of this bin, when the phone ships it; it then decides Unusual. */
    val hybrid: HybridPipeline.Result? = null,
)

/**
 * The on-device routine engine. Call [onBin] once per closed 15-minute bin (WorkManager
 * catches up on missed bins as unobserved). Everything here runs on the parent's phone;
 * only [SyncedSnapshot] and alerts leave it.
 */
class RoutineEngine(
    private val config: EngineConfig,
    private val backbone: RhythmBackbone,
    private val prior: Array<DoubleArray>,
    private val silencePrior: FloatArray,
    private val dowOffset: Int,
    installT: Int,
    /** Clean days behind [silencePrior] (`silence_prior_days` in the weights file). */
    private val silencePriorDays: Double = 0.0,
    /**
     * The MaPa Rhythm Hybrid with its versioned decision policy. When present it decides Unusual from day one
     * ("please call"); its selected policy controls the model's absolute quiet floor.
     */
    private val hybrid: HybridPipeline? = null,
) {
    private val installDay = Bins.day(installT)
    private val rules = RuleBaseline(config.rules)
    private val features = FeatureBuilder(dowOffset)
    private val stateMachine = PhoneStateMachine()
    private val alertPolicy = AlertPolicy(config.alerts)
    val scheduler = TrainingScheduler(config.schedule, installDay, dowOffset)
    val ledger = DayLedger(config.guards)
    private val anchor = AnchorGuard(config.guards)
    val newRoutine = NewRoutineDetector(config.guards)
    private val trends = TrendReporter(config.guards)

    var profile: Profile = Profile.priorOnly(prior)
        private set
    var head = PersonalHead()
        private set
    private var calibrator = SilenceCalibrator(silencePrior, DoubleArray(0), 0, silencePriorDays)
    var modelReady = false
        private set

    private val hidden = FloatArray(backbone.hiddenSize)
    private var lastT: Int? = null
    private var today: DayRecord? = null
    private var nll = 0.0
    private var prevHazard: Double? = null
    private var lastBattery = 100
    private var routineSince = Int.MIN_VALUE
    private var adoptPending = false
    private var steadyAnchored = false
    val lastInteractionT: Int? get() = features.lastInteractionT
    val lastActivityT: Int? get() = features.lastActivityT

    fun addLifeTag(tag: LifeTag) {
        ledger.tags += tag
    }

    val pendingQuestion: NewRoutineQuestion? get() = newRoutine.pending

    fun answerNewRoutine(yes: Boolean) {
        val day = lastT?.let { Bins.day(it) } ?: return
        val since = newRoutine.answer(day, yes) ?: return
        routineSince = since
        adoptPending = true
        // those days were flagged because of the new routine itself
        ledger.all().filter { it.day >= since }.forEach { it.flaggedUnusual = false }
    }

    fun onBin(obs: BinObservation, paused: Boolean = false): TickResult {
        val prev = lastT
        if (prev != null) {
            require(obs.t > prev) { "bins must arrive in order" }
            for (u in prev + 1 until obs.t) process(BinObservation(u, -1, false, lastBattery), paused = false)
        }
        return process(obs, paused)
    }

    private fun process(raw: BinObservation, paused: Boolean): TickResult {
        // While paused nothing is collected: the bin counts as unobserved for learning.
        val obs = if (paused) raw.copy(interactions = -1, moved = null) else raw
        val t = obs.t
        val day = Bins.day(t)
        val b = Bins.binOfDay(t)
        if (today?.day != day) {
            today?.let { ledger.add(it) }
            today = DayRecord(day, Bins.dow(day, dowOffset))
        }
        val rec = today!!
        lastT = t
        if (obs.battery >= 0) lastBattery = obs.battery

        // Running surprise of the current silence, from the hazard predicted at the previous tick.
        val h = prevHazard
        if (obs.alive) nll = 0.0
        else if (obs.observed && h != null) nll += -ln(1 - h)
        rules.observe(obs)

        val kind = scheduler.due(obs, features.lastActivityT ?: if (obs.alive) t else null, adoptPending)
        var heldBack = false
        var trend: TrendReport? = null
        if (kind != null) {
            val r = train(day, kind)
            heldBack = r.first
            trend = r.second
        }

        val taggedDay = ledger.tagged(day)
        val hy = hybrid?.let { p ->
            if (taggedDay) p.tagToday(day)
            p.step(obs)
        }
        val row = features.build(obs, profile)
        val z = backbone.step(row.x, hidden)
        rec.interactions[b] = obs.interactions
        rec.moved[b] = when (obs.moved) { true -> 1; false -> 0; null -> -1 }
        rec.logits[b] = z
        rec.rates[b] = row.rates
        val hz = head.hazards(z, row.rates)
        prevHazard = hz[0]

        val last = features.lastInteractionT
        val sign = features.lastActivityT
        val silenceMin = if (sign == null) Int.MAX_VALUE else (t - sign) * Bins.MINUTES
        val score = calibrator.score(nll)
        val rate = calibrator.weeklyRate(nll)
        val v2TaggedGuard = taggedDay && hybrid?.decisionPolicy?.suppressTaggedAlerts == true
        val modelUnusual = if (v2TaggedGuard) false else hy?.pleaseCall
            ?: (obs.observed && silenceMin >= config.minModelSilenceMinutes && rate <= config.modelMaxWeeklyRate)
        val ready = hy != null || modelReady
        val phase = scheduler.phase(day)
        val decider = when {
            hy != null && config.modelDecides -> Decider.MODEL
            config.modelDecides && modelReady && phase != Phase.LEARNING -> Decider.MODEL
            else -> Decider.RULES
        }
        val expectedBy = forecast(t, hz)
        val decision = stateMachine.evaluate(
            StateInputs(
                t = t, paused = paused, observed = obs.observed, lastInteractionT = last, lastActivityT = sign,
                ruleUnusual = rules.unusual && !v2TaggedGuard,
                floorTriggered = rules.floorTriggered && !v2TaggedGuard &&
                    !(hybrid != null && config.modelDecides && hybrid.decisionPolicy.hybridOwnsAbsoluteFloor),
                modelUnusual = if (ready) modelUnusual else null, decider = decider,
                expectedByT = expectedBy, usualFirstActivityMinute = profile.usualFirstActivity(day, dowOffset),
                confidence = hy?.score ?: if (modelReady) score else null,
                recurrenceWeeks = if (hy == null && modelReady && rate > 0) 1 / rate else null,
            )
        )
        // Guard 1 drops flagged days from training. Only strong evidence flags a day: the floor
        // rule, or the model once it is ready. Flagging on the plain rule starved the model of
        // days for light phone users, whose ordinary 4-hour gaps trip it, so it never got ready.
        if (rules.floorTriggered || (ready && modelUnusual)) rec.flaggedUnusual = true
        val alerts = alertPolicy.onTick(t, decision, sign, obs.battery, obs.charging)
        val snap = SyncedSnapshot(t, decision.state, decision.reason, last, obs.battery, obs.charging)
        return TickResult(
            t, decision, alerts, snap, decider, hy?.score ?: score, rules.unusual, modelUnusual, ready,
            expectedBy, kind, heldBack, trend, hy,
        )
    }

    /** Survival over the model's hazards for the next 4 hours, then the profile, up to 24 hours. */
    private fun forecast(t: Int, hz: DoubleArray): Int? {
        var survive = 1.0
        for (k in 1..Bins.PER_DAY) {
            val p = if (k <= hz.size) hz[k - 1] else profile.rateAt(t + k, dowOffset)
            survive *= 1 - p
            if (survive <= 0.5) return t + k
        }
        return null
    }

    private fun train(day: Int, kind: TrainKind): Pair<Boolean, TrendReport?> {
        val phase = scheduler.phase(day)
        val learning = phase == Phase.LEARNING
        val adopting = kind == TrainKind.ADOPT_NEW_ROUTINE
        val eligible = ledger.eligible(day, learning || adopting, routineSince)
        var heldBack = false
        var trend: TrendReport? = null

        if (kind == TrainKind.FULL || adopting) {
            if (phase == Phase.STEADY && !steadyAnchored) {
                anchor.setAnchor(profile, head)
                trends.setBaseline(eligible.values)
                steadyAnchored = true
            }
            var candProfile = Profile.build(eligible.values.toList(), prior)
            var candHead = if (eligible.isNotEmpty())
                PersonalHead.fit(PersonalHead.examples(eligible), center = head.theta) else head
            if (adopting) {
                anchor.setAnchor(candProfile, candHead)
                trends.setBaseline(eligible.values)
                adoptPending = false
            } else {
                val ticks = recentTicks(eligible)
                val r = anchor.limit(profile, head, candProfile, candHead, ticks)
                candProfile = r.profile
                candHead = r.head
                heldBack = r.heldBack
            }
            profile = candProfile
            head = candHead
            if (phase == Phase.STEADY && scheduler.weeklyNight(day)) {
                val week = ledger.recent(day, 7)
                newRoutine.weeklyCheck(day, week, anchor.anchorProfile ?: profile, dowOffset)
                trend = trends.weekly(day, week)
            }
        }
        calibrator = SilenceCalibrator(silencePrior, SilenceCalibrator.stretchSurprises(eligible, head), eligible.size, silencePriorDays)
        modelReady = eligible.size >= config.minReadyDays && calibrator.personalCount >= config.minReadySilences
        return heldBack to trend
    }

    /**
     * Everything the engine has learned and is tracking, as versioned text. Restore with
     * [RoutineEngine.restore] in a fresh process; the result behaves exactly like this engine.
     */
    fun snapshot(): String {
        val w = StateWriter()
        w.tag(SNAPSHOT_MAGIC).int(SNAPSHOT_VERSION).int(dowOffset).int(installDay).newline()
        w.tag("engine").intOrNull(lastT).double(nll).doubleOrNull(prevHazard).int(lastBattery)
            .int(routineSince).bool(adoptPending).bool(steadyAnchored).bool(modelReady).floats(hidden).newline()
        w.profile(profile)
        w.tag("head").doubles(head.theta).newline()
        w.tag("calibrator").int(calibrator.personalDays).doubles(calibrator.personalValues).newline()
        w.tag("today").bool(today != null).newline()
        today?.let { w.dayRecord(it) }
        rules.save(w); features.save(w); stateMachine.save(w); alertPolicy.save(w); w.newline()
        scheduler.save(w); ledger.save(w); anchor.save(w); newRoutine.save(w); trends.save(w)
        w.newline().tag("hybrid-present").bool(hybrid != null)
        hybrid?.save(w)
        return w.toString()
    }

    private fun load(r: StateReader) {
        r.tag("engine")
        lastT = r.intOrNull(); nll = r.double(); prevHazard = r.doubleOrNull(); lastBattery = r.int()
        routineSince = r.int(); adoptPending = r.bool(); steadyAnchored = r.bool(); modelReady = r.bool()
        r.floats().copyInto(hidden)
        profile = r.profile()
        r.tag("head"); head = PersonalHead(r.doubles())
        r.tag("calibrator")
        val pd = r.int()
        calibrator = SilenceCalibrator(silencePrior, r.doubles(), pd, silencePriorDays)
        r.tag("today"); today = if (r.bool()) r.dayRecord() else null
        rules.load(r); features.load(r); stateMachine.load(r); alertPolicy.load(r)
        scheduler.load(r); ledger.load(r); anchor.load(r); newRoutine.load(r); trends.load(r)
        r.tag("hybrid-present")
        val saved = r.bool()
        require(saved == (hybrid != null)) { "snapshot was taken with a different model" }
        if (saved) hybrid!!.load(r)
    }

    companion object {
        const val SNAPSHOT_MAGIC = "mapa-engine-state"
        const val SNAPSHOT_VERSION = 5

        /** Rebuilds an engine from [snapshot]. Config, model and priors are supplied again, not stored. */
        fun restore(
            config: EngineConfig, backbone: RhythmBackbone, prior: Array<DoubleArray>,
            silencePrior: FloatArray, snapshot: String, silencePriorDays: Double = 0.0,
            hybrid: HybridPipeline? = null,
        ): RoutineEngine {
            val r = StateReader(snapshot)
            r.tag(SNAPSHOT_MAGIC)
            val version = r.int()
            require(version == SNAPSHOT_VERSION) { "unsupported engine snapshot version $version" }
            val dow = r.int()
            val installDay = r.int()
            return RoutineEngine(config, backbone, prior, silencePrior, dow, installDay * Bins.PER_DAY, silencePriorDays, hybrid)
                .also { it.load(r) }
        }
    }

    private fun recentTicks(days: Map<Int, DayRecord>): List<Pair<Float, Float>> =
        days.values.toList().takeLast(7).flatMap { d ->
            (0 until Bins.PER_DAY step 4).mapNotNull { b -> d.logits[b].takeIf { it.isNotEmpty() }?.let { it[0] to d.rates[b][0] } }
        }
}
