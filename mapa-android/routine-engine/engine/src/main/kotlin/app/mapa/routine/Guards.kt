package app.mapa.routine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The five guards from the brief that stop a continually learning model from
 * treating an unwell week as the new normal. Each can be switched off for ablations.
 */
data class GuardConfig(
    /** Guard 1: a day waits this many days before it can enter training. */
    val quarantineDays: Int = 7,
    /** While the model is still learning (days 1-14), days enter after 1 day and are re-checked at every retrain. */
    val learningFastLane: Boolean = true,
    val dropFlaggedDays: Boolean = true,
    val minCoverage: Double = 0.8,
    val windowDays: Int = 42,
    /** Guard 2. */
    val honourLifeTags: Boolean = true,
    /** Guard 3: limits per weekly retrain, and total drift from the month-one anchor. */
    val anchor: Boolean = true,
    val maxWeeklyProfileStep: Double = 0.02,
    val maxWeeklyHeadStep: Double = 0.03,
    val maxAnchorDrift: Double = 0.05,
    /** Guard 4. */
    val askAboutNewRoutine: Boolean = true,
    val newRoutineShiftMinutes: Int = 45,
    val newRoutineWeeks: Int = 3,
    val snoozeWeeksAfterNo: Int = 3,
    /** Guard 5. */
    val trendShiftMinutes: Int = 30,
    val trendActivityChange: Double = 0.25,
) {
    companion object {
        /** Ablation: everything off, days enter training the next night. */
        val OFF = GuardConfig(
            quarantineDays = 1, dropFlaggedDays = false, honourLifeTags = false, anchor = false,
            askAboutNewRoutine = false,
        )
    }
}

enum class LifeTagKind { UNWELL, TRAVELLING, GUESTS, FESTIVAL, CHECK_IN, OTHER }

/** Guard 2: one tap from the family or the parent; a voice check-in answer is also a tag. */
data class LifeTag(val startT: Int, val endT: Int, val kind: LifeTagKind) {
    fun overlapsDay(day: Int) = startT < (day + 1) * Bins.PER_DAY && endT > day * Bins.PER_DAY
}

/** Closed days plus tags, and guard 1's eligibility rule. */
class DayLedger(private val config: GuardConfig, private val retainDays: Int = 63) {
    private val days = sortedMapOf<Int, DayRecord>()
    val tags = mutableListOf<LifeTag>()

    fun add(rec: DayRecord) {
        days[rec.day] = rec
        while (days.size > retainDays) days.remove(days.firstKey())
    }

    fun get(day: Int): DayRecord? = days[day]
    fun all(): Collection<DayRecord> = days.values

    fun tagged(day: Int) = config.honourLifeTags && tags.any { it.overlapsDay(day) }

    /**
     * Days allowed into training at [today] (the day being lived, not yet closed).
     * [learning] enables the fast lane; [sinceDay] drops days before an adopted new routine.
     */
    fun eligible(today: Int, learning: Boolean, sinceDay: Int = Int.MIN_VALUE): Map<Int, DayRecord> {
        val minAge = if (learning && config.learningFastLane) 1 else config.quarantineDays
        val newest = today - minAge
        val oldest = newest - config.windowDays + 1
        val out = sortedMapOf<Int, DayRecord>()
        for ((d, rec) in days) {
            if (d > newest || d < oldest || d < sinceDay) continue
            if (config.dropFlaggedDays && rec.flaggedUnusual) continue
            if (rec.coverage < config.minCoverage) continue
            if (tagged(d)) continue
            out[d] = rec
        }
        return out
    }

    internal fun save(w: StateWriter) {
        w.tag("ledger").int(days.size).newline()
        days.values.forEach { w.dayRecord(it) }
        w.tag("tags").int(tags.size)
        tags.forEach { w.int(it.startT).int(it.endT).int(it.kind.ordinal) }
        w.newline()
    }

    internal fun load(r: StateReader) {
        r.tag("ledger")
        days.clear()
        repeat(r.int()) { r.dayRecord().let { days[it.day] = it } }
        r.tag("tags")
        tags.clear()
        repeat(r.int()) { tags += LifeTag(r.int(), r.int(), LifeTagKind.entries[r.int()]) }
    }

    /** Recent closed days for the routine and trend checks: tagged and poorly covered days left out. */
    fun recent(today: Int, n: Int): List<DayRecord> =
        (today - n until today).mapNotNull { days[it] }.filter { !tagged(it.day) && it.coverage >= config.minCoverage }
}

/** Guard 3: hold back a weekly step that moves too far from the last model or the month-one anchor. */
class AnchorGuard(private val config: GuardConfig) {
    var anchorProfile: Profile? = null
        private set
    var anchorHead: PersonalHead? = null
        private set

    fun setAnchor(profile: Profile, head: PersonalHead) {
        anchorProfile = profile
        anchorHead = head
    }

    data class Result(val profile: Profile, val head: PersonalHead, val heldBack: Boolean)

    fun limit(
        lastProfile: Profile, lastHead: PersonalHead,
        candProfile: Profile, candHead: PersonalHead,
        ticks: List<Pair<Float, Float>>,
    ): Result {
        if (!config.anchor) return Result(candProfile, candHead, false)
        var lp = 1.0
        val dp = lastProfile.distanceTo(candProfile)
        if (dp > config.maxWeeklyProfileStep) lp = config.maxWeeklyProfileStep / dp
        var profile = lastProfile.stepToward(candProfile, lp)
        anchorProfile?.let { a ->
            if (profile.distanceTo(a) > config.maxAnchorDrift) {
                // largest step toward the candidate that stays inside the anchor radius
                var lo = 0.0; var hi = lp
                repeat(20) {
                    val mid = (lo + hi) / 2
                    if (lastProfile.stepToward(candProfile, mid).distanceTo(a) <= config.maxAnchorDrift) lo = mid else hi = mid
                }
                lp = lo
                profile = lastProfile.stepToward(candProfile, lp)
            }
        }
        var lh = 1.0
        val dh = lastHead.distanceTo(candHead, ticks)
        if (dh > config.maxWeeklyHeadStep) lh = config.maxWeeklyHeadStep / dh
        val head = lastHead.stepToward(candHead, lh)
        return Result(profile, head, lp < 0.999 || lh < 0.999)
    }

    internal fun save(w: StateWriter) {
        w.tag("anchor").bool(anchorProfile != null)
        anchorProfile?.let { w.profile(it) }
        anchorHead?.let { w.doubles(it.theta) }
        w.newline()
    }

    internal fun load(r: StateReader) {
        r.tag("anchor")
        if (r.bool()) { anchorProfile = r.profile(); anchorHead = PersonalHead(r.doubles()) }
    }
}

/** Guard 4's question to the family. Wording lives in the language layer. */
data class NewRoutineQuestion(
    val shiftMinutes: Int,
    val weeks: Int,
    val sinceDay: Int,
) {
    val later: Boolean get() = shiftMinutes > 0
}

/**
 * Guard 4: if first phone use of the day has moved the same way by at least
 * [GuardConfig.newRoutineShiftMinutes] for [GuardConfig.newRoutineWeeks] weekly checks
 * running, ask a human. Yes adopts the new routine; no keeps the old baseline.
 */
class NewRoutineDetector(private val config: GuardConfig) {
    private var streak = 0
    private var streakSign = 0
    private var streakStartDay = 0
    private val shifts = ArrayList<Int>()
    private var snoozedUntil = Int.MIN_VALUE
    var pending: NewRoutineQuestion? = null
        private set

    fun weeklyCheck(today: Int, lastWeek: List<DayRecord>, baseline: Profile, dowOffset: Int) {
        if (!config.askAboutNewRoutine || pending != null) return
        val devs = lastWeek.mapNotNull { d ->
            val first = d.firstActivityMinute() ?: return@mapNotNull null
            val usual = baseline.usualFirstActivity(d.day, dowOffset) ?: return@mapNotNull null
            first - usual
        }
        val shift = if (devs.size >= 4) Profile.median(devs)!! else 0
        // Light phone users have first-use times that wander by hours; a weekly median must
        // clear twice its own noise as well as the fixed threshold.
        val mad = if (devs.size >= 4) Profile.median(devs.map { abs(it - shift) })!! else 0
        val noise = 2 * 1.2533 * 1.4826 * mad / sqrt(devs.size.toDouble())
        val s = if (abs(shift) >= maxOf(config.newRoutineShiftMinutes.toDouble(), noise)) sign(shift.toDouble()).toInt() else 0
        if (s == 0) { streak = 0; shifts.clear(); return }
        if (s == streakSign && streak > 0) streak++ else {
            streak = 1; streakSign = s; streakStartDay = today - 7; shifts.clear()
        }
        shifts += shift
        if (streak >= config.newRoutineWeeks && today >= snoozedUntil) {
            pending = NewRoutineQuestion(Profile.median(shifts)!!, streak, streakStartDay)
        }
    }

    /** Returns the first day of the new routine when adopted, else null. */
    fun answer(today: Int, yes: Boolean): Int? {
        val q = pending ?: return null
        pending = null
        streak = 0; shifts.clear()
        if (yes) return q.sinceDay
        snoozedUntil = today + 7 * config.snoozeWeeksAfterNo
        return null
    }

    internal fun save(w: StateWriter) {
        w.tag("newroutine").int(streak).int(streakSign).int(streakStartDay).ints(shifts.toIntArray()).int(snoozedUntil)
        val q = pending
        w.bool(q != null)
        if (q != null) w.int(q.shiftMinutes).int(q.weeks).int(q.sinceDay)
        w.newline()
    }

    internal fun load(r: StateReader) {
        r.tag("newroutine")
        streak = r.int(); streakSign = r.int(); streakStartDay = r.int()
        shifts.clear(); r.ints().forEach { shifts += it }
        snoozedUntil = r.int()
        pending = if (r.bool()) NewRoutineQuestion(r.int(), r.int(), r.int()) else null
    }
}

/** Guard 5: slow drift is shown to the family as phone-pattern trends, never as health. */
data class TrendReport(
    val day: Int,
    val firstUseShiftMinutes: Int?,
    val lastUseShiftMinutes: Int?,
    /** Relative change in active 15-minute bins per day vs the baseline, e.g. -0.3 = 30% fewer. */
    val activityChange: Double?,
    val notable: Boolean,
) {
    /** Template wording; the language layer may rephrase but must keep it about phone patterns. */
    fun lines(name: String): List<String> = buildList {
        firstUseShiftMinutes?.takeIf { abs(it) >= 15 }?.let {
            add("$name's first phone use this week was about ${abs(it)} minutes ${if (it > 0) "later" else "earlier"} than usual.")
        }
        lastUseShiftMinutes?.takeIf { abs(it) >= 15 }?.let {
            add("Last phone use in the evening was about ${abs(it)} minutes ${if (it > 0) "later" else "earlier"} than usual.")
        }
        activityChange?.takeIf { abs(it) >= 0.1 }?.let {
            add("Phone activity was about ${(abs(it) * 100).roundToInt()}% ${if (it > 0) "higher" else "lower"} than usual.")
        }
        if (isEmpty()) add("$name's phone patterns this week were close to usual.")
    }
}

class TrendReporter(private val config: GuardConfig) {
    private data class Baseline(val first: Int?, val last: Int?, val activeBins: Double)

    private var baseline: Baseline? = null

    fun setBaseline(days: Collection<DayRecord>) {
        if (days.isEmpty()) return
        baseline = Baseline(
            Profile.median(days.mapNotNull { it.firstActivityMinute() }),
            Profile.median(days.mapNotNull { it.lastActivityMinute() }),
            days.map { it.activeBins() }.average(),
        )
    }

    fun weekly(today: Int, lastWeek: List<DayRecord>): TrendReport? {
        val b = baseline ?: return null
        if (lastWeek.size < 4) return null
        val first = Profile.median(lastWeek.mapNotNull { it.firstActivityMinute() })
        val last = Profile.median(lastWeek.mapNotNull { it.lastActivityMinute() })
        val fs = if (first != null && b.first != null) first - b.first else null
        val ls = if (last != null && b.last != null) last - b.last else null
        val ac = if (b.activeBins > 0) lastWeek.map { it.activeBins() }.average() / b.activeBins - 1 else null
        val notable = (fs != null && abs(fs) >= config.trendShiftMinutes) ||
            (ls != null && abs(ls) >= config.trendShiftMinutes) ||
            (ac != null && abs(ac) >= config.trendActivityChange)
        return TrendReport(today, fs, ls, ac, notable)
    }

    internal fun save(w: StateWriter) {
        val b = baseline
        w.tag("trend").bool(b != null)
        if (b != null) w.intOrNull(b.first).intOrNull(b.last).double(b.activeBins)
        w.newline()
    }

    internal fun load(r: StateReader) {
        r.tag("trend")
        baseline = if (r.bool()) Baseline(r.intOrNull(), r.intOrNull(), r.double()) else null
    }
}
