package app.mapa.family

import android.content.Context
import app.mapa.core.LocalBins
import app.mapa.core.wire.AlertKind
import app.mapa.core.wire.DayDoc
import app.mapa.core.wire.EventDoc
import app.mapa.core.wire.ParentProfile
import app.mapa.core.wire.RoutineDoc
import app.mapa.core.wire.StatusDoc
import app.mapa.core.wire.TagDoc
import app.mapa.routine.Decider
import app.mapa.routine.Phase
import app.mapa.routine.Reason
import app.mapa.routine.RoutineState
import app.mapa.routine.SyncedSnapshot
import app.mapa.routine.TrendReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** The judge flavor feeds the production family screens from the same bundled, identity-free seed. */
internal object JudgeFamilySeed {
    private const val ZONE = "Asia/Kolkata"
    private val zone = ZoneId.of(ZONE)
    private data class Day(val offset: Int, val doc: DayDoc)
    private data class Parent(
        val id: String,
        val name: String,
        val role: String,
        val historyDays: Int,
        val usualFirstMinute: Int?,
        val days: List<Day>,
        val alerts: List<JSONObject>,
    )

    @Volatile private var parents: List<Parent> = emptyList()

    fun initialize(context: Context) {
        if (parents.isNotEmpty()) return
        val root = JSONObject(context.assets.open("demo/demo-history.json").bufferedReader().use { it.readText() })
        require(root.getString("provenance") == "model_derived" && root.getString("split") == "train")
        val people = root.getJSONArray("people")
        parents = List(people.length()) { i ->
            val row = people.getJSONObject(i)
            val name = row.getString("name")
            val daysJson = row.getJSONArray("days")
            val days = List(daysJson.length()) { j ->
                val day = daysJson.getJSONObject(j)
                val interactions = day.getJSONArray("interactions")
                val observed = day.getJSONArray("observed")
                val active = List(96) { bin ->
                    if (!observed.getBoolean(bin)) -1 else if (interactions.getInt(bin) > 0) 1 else 0
                }
                Day(
                    offset = day.getInt("day_offset"),
                    doc = DayDoc(
                        date = LocalDate.now(zone).minusDays(day.getInt("day_offset").toLong()).toString(),
                        active = active,
                        usual = row.getJSONArray("usual_use_probability").toDoubleList(),
                        firstActivityMinute = day.getInt("first_use_bin").takeIf { it >= 0 }?.times(15),
                    ),
                )
            }.sortedByDescending { it.offset }
            Parent(
                id = "judge-${name.lowercase()}",
                name = name,
                role = if (name.equals("Ma", true)) "mother" else "father",
                historyDays = row.getInt("history_days"),
                usualFirstMinute = days.lastOrNull()?.doc?.firstActivityMinute,
                days = days,
                alerts = List(row.getJSONArray("alerts").length()) { row.getJSONArray("alerts").getJSONObject(it) },
            )
        }
    }

    val ids: List<String> get() = parents.map { it.id }
    private fun parent(id: String): Parent? = parents.firstOrNull { it.id == id }
    fun parentsFlow(): Flow<List<String>> = flowOf(ids)
    fun profile(id: String): Flow<ParentProfile?> = flowOf(parent(id)?.let {
        ParentProfile(it.name, it.role, "en", ZONE, paused = false, showPaused = true)
    })

    fun status(id: String): Flow<StatusDoc?> = flowOf(parent(id)?.let { p ->
        val now = System.currentTimeMillis()
        val t = LocalBins.binAt(now, zone)
        val lastUseBin = p.days.lastOrNull()?.doc?.active?.indexOfLast { it == 1 }?.takeIf { it >= 0 }
        val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val seededLastUse = lastUseBin?.let { startOfToday + it * 15L * 60 * 1000 }
            ?.let { if (it > now) it - 24L * 60 * 60 * 1000 else it }
        val lastUseT = seededLastUse?.let { LocalBins.binAt(it, zone) } ?: (t - 40)
        val reason = Reason.LongerThanUsual(
            silentSinceT = lastUseT,
            expectedByT = null,
            usualFirstActivityMinute = p.usualFirstMinute,
            confidence = null,
            recurrenceWeeks = null,
            decidedBy = Decider.RULES,
            floorRule = false,
        )
        StatusDoc(
            snapshot = SyncedSnapshot(t, RoutineState.UNUSUAL_INACTIVITY, reason, lastUseT, 80, true),
            computedAtMs = now,
            zone = ZONE,
            lastInteractionAtMs = seededLastUse,
            decider = Decider.RULES,
            modelReady = false,
            modelDecides = false,
            model = "",
            score = null,
            expectedByT = null,
            usualFirstMinute = p.usualFirstMinute,
            phase = Phase.STEADY,
            dayNumber = p.historyDays,
            lastTrainedT = null,
            note = "Phone activity cannot show what someone is doing. Quiet phone time has many ordinary explanations.",
            noteSource = null,
            noteLang = "en",
            noteAtMs = now,
        )
    })

    fun today(id: String): Flow<DayDoc?> = flowOf(parent(id)?.days?.lastOrNull()?.doc)

    fun week(id: String, limit: Long): List<DayDoc> = parent(id)?.days.orEmpty()
        .takeLast(limit.toInt().coerceAtLeast(0)).map { it.doc }

    fun events(id: String, limit: Long): Flow<List<Pair<String, EventDoc>>> = flowOf(
        parent(id)?.let { p ->
            p.alerts.mapIndexed { index, alert ->
                val date = LocalDate.now(zone).minusDays(alert.getInt("day_offset").toLong())
                val at = date.atStartOfDay(zone).plusMinutes(alert.getInt("alert_bin") * 15L).toInstant().toEpochMilli()
                val t = LocalBins.binAt(at, zone)
                val event = EventDoc(
                    atMs = at,
                    t = t,
                    from = RoutineState.QUIET,
                    to = RoutineState.UNUSUAL_INACTIVITY,
                    reason = Reason.LongerThanUsual(t - alert.getInt("quiet_minutes") / 15, t, p.usualFirstMinute, 0.82, 4.0, Decider.MODEL, false),
                    text = "Phone activity was quieter than usual.",
                    alert = AlertKind.UNUSUAL,
                )
                "${p.id}-event-$index" to event
            }.sortedByDescending { it.second.atMs }.take(limit.toInt().coerceAtLeast(0))
        }.orEmpty(),
    )

    fun trend(id: String): Flow<TrendReport?> = flowOf(parent(id)?.let {
        TrendReport(it.historyDays, null, null, null, notable = false)
    })
    fun routine(@Suppress("UNUSED_PARAMETER") id: String): Flow<RoutineDoc?> = flowOf(null)
    fun tags(@Suppress("UNUSED_PARAMETER") id: String): Flow<List<TagDoc>> = flowOf(emptyList())

    private fun JSONArray.toDoubleList() = List(length()) { getDouble(it) }
}
