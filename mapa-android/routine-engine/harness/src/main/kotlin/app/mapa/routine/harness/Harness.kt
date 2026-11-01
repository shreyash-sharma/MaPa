package app.mapa.routine.harness

import app.mapa.routine.Alert
import app.mapa.routine.BinObservation
import app.mapa.routine.Bins
import app.mapa.routine.EngineConfig
import app.mapa.routine.FamilyStatusResolver
import app.mapa.routine.GuardConfig
import app.mapa.routine.KotlinGruBackbone
import app.mapa.routine.LifeTag
import app.mapa.routine.LifeTagKind
import app.mapa.routine.RhythmWeights
import app.mapa.routine.RoutineEngine
import app.mapa.routine.RoutineState
import app.mapa.routine.RuleConfig
import app.mapa.routine.SyncedSnapshot
import app.mapa.routine.TrainKind
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Replays synthetic parents (from `ml/mapa_rhythm/synth.py`) through the full engine,
 * phone side and family side, and scores each configuration on the same data.
 *
 *   gradle :harness:run --args="data/harness models/rhythm_v1.weights.txt results"
 */
class Parent(
    val pid: String,
    val startDow: Int,
    val days: Int,
    val interactions: IntArray,
    val phoneOn: BooleanArray,
    val usageOk: BooleanArray,
    val networkOk: BooleanArray,
    val charging: BooleanArray,
    val battery: IntArray,
    val paused: BooleanArray,
    val episodes: List<Episode>,
)

class Episode(val kind: String, val label: String, val start: Int, val end: Int, val tagged: Boolean, val tagEntered: Int)

data class Variant(val name: String, val config: EngineConfig)

class UnusualAlertRecord(
    val raisedT: Int, val deliveredT: Int, val byModel: Boolean, val floor: Boolean,
    val silentSinceT: Int = 0, val recurrenceWeeks: Double? = null,
)

class ParentResult(
    val pid: String,
    val alerts: List<UnusualAlertRecord>,
    val staleAlerts: Int,
    val lowBattery: Int,
    val staleShownAsFresh: Int,
    val familyStateTicks: IntArray,
    val questions: List<Pair<Int, Boolean>>,
    val trains: Map<TrainKind, Int>,
    val heldBack: Int,
    val modelReadyDay: Int?,
    val notableTrends: Int,
)

fun loadParents(dir: File): List<Parent> {
    val meta = dir.resolve("parents.csv").readLines().drop(1).map { it.split(",") }
    val eps = dir.resolve("episodes.csv").readLines().drop(1).map { it.split(",") }
        .groupBy({ it[0] }) { Episode(it[1], it[2], it[3].toInt(), it[4].toInt(), it[5] == "1", it[6].toInt()) }
    val arrays = meta.associate { m ->
        val n = m[2].toInt() * Bins.PER_DAY
        m[0] to Array(7) { IntArray(n) }
    }
    GZIPInputStream(dir.resolve("bins.csv.gz").inputStream()).bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
            val p = line.split(",")
            val a = arrays.getValue(p[0])
            val t = p[1].toInt()
            for (i in 0 until 7) a[i][t] = p[2 + i].toInt()
        }
    }
    return meta.map { m ->
        val a = arrays.getValue(m[0])
        Parent(
            m[0], m[1].toInt(), m[2].toInt(), a[0],
            BooleanArray(a[1].size) { a[1][it] == 1 }, BooleanArray(a[2].size) { a[2][it] == 1 },
            BooleanArray(a[3].size) { a[3][it] == 1 }, BooleanArray(a[4].size) { a[4][it] == 1 },
            a[5], BooleanArray(a[6].size) { a[6][it] == 1 }, eps[m[0]] ?: emptyList(),
        )
    }
}

private fun tagKind(kind: String) = when (kind) {
    "unwell" -> LifeTagKind.UNWELL
    "travel" -> LifeTagKind.TRAVELLING
    "guests" -> LifeTagKind.GUESTS
    "festival" -> LifeTagKind.FESTIVAL
    else -> LifeTagKind.OTHER
}

fun simulate(p: Parent, v: Variant, weights: RhythmWeights): ParentResult {
    val prior = weights.prior()
    val silencePrior = weights["silence_prior"].data
    val priorDays = weights.tensors["silence_prior_days"]?.data?.get(0)?.toDouble() ?: 0.0
    val engine = RoutineEngine(v.config, KotlinGruBackbone(weights), prior, silencePrior, p.startDow, installT = 0, priorDays)
    val family = FamilyStatusResolver()
    val tagsByEntry = p.episodes.filter { it.tagged }.groupBy { it.tagEntered }
    val shift = p.episodes.firstOrNull { it.kind == "routine_shift" }
    var lastSync: SyncedSnapshot? = null
    var lastSeenAndSynced = Int.MIN_VALUE / 2
    val pending = ArrayList<Pair<Alert, Int>>()
    val alerts = ArrayList<UnusualAlertRecord>()
    var stale = 0
    var lowBattery = 0
    var violations = 0
    val stateTicks = IntArray(RoutineState.entries.size)
    val questions = ArrayList<Pair<Int, Boolean>>()
    val trains = HashMap<TrainKind, Int>()
    var heldBack = 0
    var readyDay: Int? = null
    var notableTrends = 0
    val n = p.days * Bins.PER_DAY
    for (t in 0 until n) {
        tagsByEntry[t]?.forEach { engine.addLifeTag(LifeTag(it.start, it.end, tagKind(it.kind))) }
        if (p.phoneOn[t]) {
            val obs = BinObservation(t, p.interactions[t], p.charging[t], p.battery[t])
            val r = engine.onBin(obs, p.paused[t])
            r.trained?.let { trains.merge(it, 1, Int::plus) }
            if (r.heldBack) heldBack++
            if (r.trend?.notable == true) notableTrends++
            if (r.modelReady && readyDay == null) readyDay = Bins.day(t)
            r.alerts.forEach { pending += it to t }
            engine.pendingQuestion?.let {
                // The family answers truthfully: yes only if the routine really changed.
                val yes = shift != null && shift.start <= t
                if (System.getenv("MAPA_DEBUG") != null) println("question ${p.pid} day ${Bins.day(t)} shift ${it.shiftMinutes} since ${it.sinceDay} truth $yes")
                questions += t to yes
                engine.answerNewRoutine(yes)
            }
            if (p.networkOk[t]) {
                lastSync = r.snapshot
                if (p.usageOk[t] && !p.paused[t]) lastSeenAndSynced = t
                for ((a, raised) in pending) when (a) {
                    is Alert.UnusualInactivity -> alerts += UnusualAlertRecord(
                        raised, t, a.reason.decidedBy == app.mapa.routine.Decider.MODEL && !a.reason.floorRule, a.reason.floorRule,
                        a.reason.silentSinceT, a.reason.recurrenceWeeks,
                    )
                    is Alert.LowBattery -> lowBattery++
                    is Alert.MonitoringUnavailable -> stale++
                }
                pending.clear()
            }
        }
        val fam = family.resolve(t, lastSync)
        stateTicks[fam.state.ordinal]++
        if ((fam.state == RoutineState.ACTIVE || fam.state == RoutineState.QUIET) && t - lastSeenAndSynced > 4) violations++
        if (family.staleAlert(t, lastSync) != null) stale++
    }
    return ParentResult(p.pid, alerts, stale, lowBattery, violations, stateTicks, questions, trains, heldBack, readyDay, notableTrends)
}

class Score(
    val variant: String,
    val parentWeeks: Double,
    val falseAlerts: Int,
    val falseAlertsSteady: Int,
    val steadyParentWeeks: Double,
    val ambiguousAlerts: Int,
    val positives: Int,
    val detected: Int,
    val positivesSteady: Int,
    val detectedSteady: Int,
    val medianLatencyMin: Int?,
    val medianLatencySteadyMin: Int?,
    val alertsByModel: Int,
    val floorAlerts: Int,
    val staleShownAsFresh: Int,
    val staleAlerts: Int,
    val questionsTrue: Int,
    val questionsFalse: Int,
    val shiftParents: Int,
    val medianDaysToQuestion: Int?,
    val falseAlertsDuringShift: Int,
    val heldBack: Int,
    val medianReadyDay: Int?,
    val trains: Map<TrainKind, Int>,
    val familyStateShare: Map<RoutineState, Double>,
    val falseByCause: Map<String, Int>,
    val detectedByDuration: Map<String, IntArray>,
)

private fun median(xs: List<Int>): Int? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

fun score(variant: String, parents: List<Parent>, results: List<ParentResult>, steadyFromDay: Int = 30): Score {
    var fa = 0; var faSteady = 0; var amb = 0; var pos = 0; var det = 0; var posS = 0; var detS = 0
    val falseByCause = sortedMapOf<String, Int>()
    val debugFalse = if (System.getenv("MAPA_DEBUG") != null) StringBuilder("pid,raised,silent_since,by_model,floor,recurrence_weeks,cause\n") else null
    val missByDuration = sortedMapOf<String, IntArray>()
    var faShift = 0; var qTrue = 0; var qFalse = 0; var shiftParents = 0
    val lat = ArrayList<Int>(); val latS = ArrayList<Int>(); val daysToQ = ArrayList<Int>()
    val byP = parents.associateBy { it.pid }
    for (r in results) {
        val p = byP.getValue(r.pid)
        val positives = p.episodes.filter { it.label == "positive" }
        val ambiguous = p.episodes.filter { it.label == "ambiguous" }
        val shift = p.episodes.firstOrNull { it.kind == "routine_shift" }
        for (a in r.alerts) {
            when {
                positives.any { a.raisedT in it.start until it.end } -> Unit
                ambiguous.any { a.raisedT in it.start until it.end } -> amb++
                else -> {
                    val cause = p.episodes.filter { it.label == "benign" && a.raisedT in it.start until it.end + 8 }
                        .maxByOrNull { it.start }?.kind ?: "none"
                    falseByCause.merge(cause, 1, Int::plus)
                    debugFalse?.appendLine("${r.pid},${a.raisedT},${a.silentSinceT},${a.byModel},${a.floor},${a.recurrenceWeeks},$cause")
                    fa++
                    if (Bins.day(a.raisedT) >= steadyFromDay) faSteady++
                    if (shift != null && a.raisedT >= shift.start) faShift++
                }
            }
        }
        for (e in positives) {
            val first = r.alerts.filter { it.raisedT in e.start until e.end }.minByOrNull { it.raisedT }
            val steady = Bins.day(e.start) >= steadyFromDay
            pos++; if (steady) posS++
            val hours = (e.end - e.start) * Bins.MINUTES / 60
            val bucket = when { hours < 4 -> "3-4h"; hours < 6 -> "4-6h"; hours < 9 -> "6-9h"; else -> "9-14h" }
            missByDuration.getOrPut(bucket) { IntArray(2) }.let { it[0]++; if (first != null) it[1]++ }
            if (first != null) {
                det++
                val l = (first.raisedT - e.start) * Bins.MINUTES
                lat += l
                if (steady) { detS++; latS += l }
            }
        }
        if (shift != null) {
            shiftParents++
            r.questions.firstOrNull { it.second }?.let { daysToQ += Bins.day(it.first) - Bins.day(shift.start) }
        }
        qTrue += r.questions.count { it.second }
        qFalse += r.questions.count { !it.second }
    }
    debugFalse?.let { File("results/false_alerts_$variant.csv").writeText(it.toString()) }
    val totalTicks = results.sumOf { it.familyStateTicks.sum() }.toDouble()
    val share = RoutineState.entries.associateWith { s -> results.sumOf { it.familyStateTicks[s.ordinal] } / totalTicks }
    val trains = HashMap<TrainKind, Int>()
    results.forEach { r -> r.trains.forEach { (k, v) -> trains.merge(k, v, Int::plus) } }
    return Score(
        variant,
        parentWeeks = parents.sumOf { it.days } / 7.0,
        falseAlerts = fa, falseAlertsSteady = faSteady,
        steadyParentWeeks = parents.sumOf { (it.days - steadyFromDay).coerceAtLeast(0) } / 7.0,
        ambiguousAlerts = amb, positives = pos, detected = det, positivesSteady = posS, detectedSteady = detS,
        medianLatencyMin = median(lat), medianLatencySteadyMin = median(latS),
        alertsByModel = results.sumOf { r -> r.alerts.count { it.byModel } },
        floorAlerts = results.sumOf { r -> r.alerts.count { it.floor } },
        staleShownAsFresh = results.sumOf { it.staleShownAsFresh },
        staleAlerts = results.sumOf { it.staleAlerts },
        questionsTrue = qTrue, questionsFalse = qFalse, shiftParents = shiftParents,
        medianDaysToQuestion = median(daysToQ), falseAlertsDuringShift = faShift,
        heldBack = results.sumOf { it.heldBack },
        medianReadyDay = median(results.mapNotNull { it.modelReadyDay }),
        trains = trains, familyStateShare = share, falseByCause = falseByCause, detectedByDuration = missByDuration,
    )
}

fun Score.toJson(): String {
    fun d(x: Double) = "%.4f".format(x)
    return buildString {
        append("{\"variant\":\"$variant\",\"parent_weeks\":${d(parentWeeks)},\"false_alerts\":$falseAlerts,")
        append("\"false_alerts_per_parent_week\":${d(falseAlerts / parentWeeks)},")
        append("\"false_alerts_steady\":$falseAlertsSteady,\"false_alerts_per_parent_week_steady\":${d(falseAlertsSteady / steadyParentWeeks)},")
        append("\"ambiguous_alerts\":$ambiguousAlerts,\"positives\":$positives,\"detected\":$detected,")
        append("\"recall\":${d(detected.toDouble() / positives)},\"positives_steady\":$positivesSteady,\"detected_steady\":$detectedSteady,")
        append("\"recall_steady\":${d(detectedSteady.toDouble() / positivesSteady)},")
        append("\"median_latency_min\":$medianLatencyMin,\"median_latency_steady_min\":$medianLatencySteadyMin,")
        append("\"alerts_by_model\":$alertsByModel,\"floor_alerts\":$floorAlerts,")
        append("\"stale_shown_as_fresh_ticks\":$staleShownAsFresh,\"monitoring_alerts\":$staleAlerts,")
        append("\"new_routine_questions_true\":$questionsTrue,\"new_routine_questions_false\":$questionsFalse,")
        append("\"routine_shift_parents\":$shiftParents,\"median_days_to_question\":$medianDaysToQuestion,")
        append("\"false_alerts_after_routine_shift\":$falseAlertsDuringShift,\"held_back_steps\":$heldBack,")
        append("\"median_model_ready_day\":$medianReadyDay,")
        append("\"trains\":{${trains.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},")
        append("\"false_alerts_by_cause\":{${falseByCause.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},")
        append("\"positives_detected_by_duration\":{${detectedByDuration.entries.joinToString(",") { "\"${it.key}\":[${it.value[0]},${it.value[1]}]" }}},")
        append("\"family_state_share\":{${familyStateShare.entries.joinToString(",") { "\"${it.key}\":${d(it.value)}" }}}}")
    }
}

fun variants(only: Set<String>?): List<Variant> {
    val base = EngineConfig()
    val list = buildList {
        for (h in listOf(3, 4, 5, 6)) {
            add(Variant("rules-${h}h", base.copy(modelDecides = false, rules = RuleConfig(inactivityMinutes = h * 60))))
        }
        for (rate in listOf(1.0, 0.5, 0.25, 0.1, 0.05, 0.02)) {
            add(Variant("model-$rate", base.copy(modelMaxWeeklyRate = rate)))
        }
        add(Variant("model-0.1-no-guards", base.copy(modelMaxWeeklyRate = 0.1, guards = GuardConfig.OFF)))
        add(Variant("model-0.1-no-floor", base.copy(modelMaxWeeklyRate = 0.1, rules = RuleConfig(floorMinutes = 24 * 60))))
    }
    return if (only == null) list else list.filter { it.name in only }
}

fun main(args: Array<String>) {
    val dataDir = File(args.getOrElse(0) { "data/harness" })
    val weightsFile = File(args.getOrElse(1) { "models/rhythm_v1.weights.txt" })
    val outDir = File(args.getOrElse(2) { "results" }).also { it.mkdirs() }
    val only = args.getOrNull(3)?.split(",")?.toSet()
    val t0 = System.currentTimeMillis()
    val parents = loadParents(dataDir)
    val weights = RhythmWeights.load(weightsFile)
    println("loaded ${parents.size} parents x ${parents.first().days} days in ${(System.currentTimeMillis() - t0) / 1000}s")
    val scores = ArrayList<Score>()
    for (v in variants(only)) {
        val t1 = System.currentTimeMillis()
        val results = parents.parallelStream().map { simulate(it, v, weights) }.toList()
        val s = score(v.name, parents, results)
        scores += s
        println(
            "%-24s false/parent-wk %.3f (steady %.3f)  recall %.3f (steady %.3f)  median latency %s min  stale-as-fresh %d  [%ds]".format(
                v.name, s.falseAlerts / s.parentWeeks, s.falseAlertsSteady / s.steadyParentWeeks,
                s.detected.toDouble() / s.positives, s.detectedSteady.toDouble() / s.positivesSteady,
                s.medianLatencyMin, s.staleShownAsFresh, (System.currentTimeMillis() - t1) / 1000,
            )
        )
    }
    outDir.resolve("harness_results.json").writeText("[\n" + scores.joinToString(",\n") { it.toJson() } + "\n]\n")
    println("wrote ${outDir.resolve("harness_results.json")} in ${((System.currentTimeMillis() - t0) / 1000.0).roundToInt()}s")
}
