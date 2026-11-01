package app.mapa.routine

/**
 * Configurable inactivity rule with quiet hours (Stage A of the brief).
 *
 * Silence is counted only over observed bins outside quiet hours, so a parent who
 * sleeps 22:00 to 07:00 does not trip the rule at breakfast. Two thresholds:
 * [inactivityMinutes] is the rule that decides while the model is learning or unsure,
 * and [floorMinutes] is the safety floor that fires whatever the model says.
 */
data class RuleConfig(
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 7 * 60,
    val inactivityMinutes: Int = 4 * 60,
    val floorMinutes: Int = 8 * 60,
) {
    init {
        require(quietStartMinute in 0 until 1440 && quietEndMinute in 0 until 1440)
        require(inactivityMinutes in 1..floorMinutes)
    }

    fun isQuiet(t: Int): Boolean {
        val m = Bins.minuteOfDay(t)
        return if (quietStartMinute <= quietEndMinute) m in quietStartMinute until quietEndMinute
        else m >= quietStartMinute || m < quietEndMinute
    }
}

/** Running counter of observed, awake-hours silence since the last use or movement. O(1) per bin. */
class RuleBaseline(val config: RuleConfig) {
    var silentAwakeBins: Int = 0
        private set

    fun observe(obs: BinObservation) {
        when {
            obs.alive -> silentAwakeBins = 0
            obs.observed && !config.isQuiet(obs.t) -> silentAwakeBins++
        }
    }

    val silentAwakeMinutes: Int get() = silentAwakeBins * Bins.MINUTES
    val unusual: Boolean get() = silentAwakeMinutes >= config.inactivityMinutes
    val floorTriggered: Boolean get() = silentAwakeMinutes >= config.floorMinutes

    fun reset() {
        silentAwakeBins = 0
    }

    internal fun save(w: StateWriter) { w.tag("rules").int(silentAwakeBins) }
    internal fun load(r: StateReader) { r.tag("rules"); silentAwakeBins = r.int() }
}
