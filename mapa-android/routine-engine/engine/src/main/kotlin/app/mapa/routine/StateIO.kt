package app.mapa.routine

/**
 * Minimal positional text format for engine snapshots: whitespace-separated tokens,
 * written and read in the same order. `_` stands for null. Doubles and floats use
 * Kotlin's shortest round-trip representation, so a restore is bit-exact.
 */
class StateWriter {
    private val sb = StringBuilder()

    fun tag(s: String) = apply { sb.append(s).append(' ') }
    fun int(v: Int) = apply { sb.append(v).append(' ') }
    fun intOrNull(v: Int?) = apply { sb.append(v?.toString() ?: "_").append(' ') }
    fun bool(v: Boolean) = apply { sb.append(if (v) '1' else '0').append(' ') }
    fun double(v: Double) = apply { sb.append(v).append(' ') }
    fun doubleOrNull(v: Double?) = apply { sb.append(v?.toString() ?: "_").append(' ') }
    fun ints(a: IntArray) = apply { int(a.size); a.forEach { int(it) } }
    fun doubles(a: DoubleArray) = apply { int(a.size); a.forEach { double(it) } }
    fun floats(a: FloatArray) = apply { int(a.size); a.forEach { sb.append(it).append(' ') } }
    fun newline() = apply { sb.append('\n') }

    override fun toString() = sb.toString()
}

class StateReader(text: String) {
    private val tokens = text.split(' ', '\n').filter { it.isNotEmpty() }.iterator()

    private fun next(): String = tokens.next()
    fun tag(expected: String) {
        val t = next()
        require(t == expected) { "snapshot corrupt: expected '$expected', found '$t'" }
    }
    fun int() = next().toInt()
    fun intOrNull() = next().let { if (it == "_") null else it.toInt() }
    fun bool() = next() == "1"
    fun double() = next().toDouble()
    fun doubleOrNull() = next().let { if (it == "_") null else it.toDouble() }
    fun ints() = IntArray(int()) { int() }
    fun doubles() = DoubleArray(int()) { double() }
    fun floats() = FloatArray(int()) { next().toFloat() }
}

internal fun StateWriter.dayRecord(d: DayRecord) = apply {
    tag("day").int(d.day).int(d.dow).bool(d.flaggedUnusual).ints(d.interactions).ints(d.moved)
    for (b in 0 until Bins.PER_DAY) { floats(d.logits[b]); floats(d.rates[b]) }
    newline()
}

internal fun StateReader.dayRecord(): DayRecord {
    tag("day")
    val d = DayRecord(int(), int())
    d.flaggedUnusual = bool()
    ints().copyInto(d.interactions)
    ints().copyInto(d.moved)
    for (b in 0 until Bins.PER_DAY) { d.logits[b] = floats(); d.rates[b] = floats() }
    return d
}

internal fun StateWriter.profile(p: Profile) = apply {
    tag("profile").int(p.nDays)
    for (dt in 0 until 3) doubles(p.rate[dt])
    bool(p.usualFirstActivityMinute != null)
    p.usualFirstActivityMinute?.let { ints(it) }
    newline()
}

internal fun StateReader.profile(): Profile {
    tag("profile")
    val n = int()
    val rate = Array(3) { doubles() }
    val usual = if (bool()) ints() else null
    return Profile(rate, n, usual)
}

internal fun StateWriter.reason(r: Reason.LongerThanUsual) = apply {
    int(r.silentSinceT).intOrNull(r.expectedByT).intOrNull(r.usualFirstActivityMinute)
        .doubleOrNull(r.confidence).doubleOrNull(r.recurrenceWeeks).int(r.decidedBy.ordinal).bool(r.floorRule)
}

internal fun StateReader.reason() = Reason.LongerThanUsual(
    int(), intOrNull(), intOrNull(), doubleOrNull(), doubleOrNull(), Decider.entries[int()], bool(),
)
