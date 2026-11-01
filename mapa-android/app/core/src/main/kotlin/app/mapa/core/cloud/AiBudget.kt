package app.mapa.core.cloud

/**
 * What a cloud call is for. The ceiling covers the open model's hidden reasoning too (it uses several hundred
 * tokens before it answers, even when asked not to), so it is well above the answer length.
 */
enum class AiKind(val maxTokens: Int) { NOTE(1600), ASK(1400), TAG(1200) }

/** Where the counters live: SharedPreferences on the phone, a map in tests. */
interface CounterStore {
    fun get(key: String): Long
    fun put(key: String, value: Long)
}

class MemoryCounters : CounterStore {
    private val m = HashMap<String, Long>()
    override fun get(key: String) = m[key] ?: 0L
    override fun put(key: String, value: Long) { m[key] = value }
}

/**
 * The spend guard for cloud AI on a demo build (Firebase Spark plan: the key is in the build, so the build itself
 * must stop). Hard stops: [maxCalls] calls in total, about [maxUsd] dollars in total, and [maxTapsPerDay] Ask MaPa
 * and life-tag calls a day. Daily notes are cached once per parent per day elsewhere ([AiKind.NOTE] is not in the
 * daily tap count). Every attempt counts, also a failed one, so nothing retries around the guard.
 *
 * Prices per million tokens: Baseten Muse Spark Open, $1.25 input and $4.25 output, from third-party captures of
 * Baseten's pricing page (23 Oct 2026). Not checked on baseten.co: the dollar figure is an estimate.
 */
class AiBudget(
    private val store: CounterStore,
    private val today: () -> String,
    val maxCalls: Long = 3_000,
    val maxUsd: Double = 50.0,
    val maxTapsPerDay: Long = 100,
    private val inPerMillion: Double = 1.25,
    private val outPerMillion: Double = 4.25,
) {
    val calls: Long get() = store.get(CALLS)
    val tokensIn: Long get() = store.get(IN)
    val tokensOut: Long get() = store.get(OUT)
    val usd: Double get() = tokensIn / 1e6 * inPerMillion + tokensOut / 1e6 * outPerMillion
    private val tapsToday: Long get() = store.get(DAY + today())

    /** Null when the call may go out, else why not. */
    @Synchronized
    fun refusal(kind: AiKind): String? = when {
        calls >= maxCalls -> "call limit ($maxCalls) reached"
        usd >= maxUsd -> "spend limit (about $$maxUsd) reached"
        kind != AiKind.NOTE && tapsToday >= maxTapsPerDay -> "daily limit ($maxTapsPerDay) reached"
        else -> null
    }

    @Synchronized
    fun record(kind: AiKind, tokensIn: Long, tokensOut: Long) {
        store.put(CALLS, calls + 1)
        store.put(IN, this.tokensIn + tokensIn)
        store.put(OUT, this.tokensOut + tokensOut)
        if (kind != AiKind.NOTE) store.put(DAY + today(), tapsToday + 1)
    }

    /** For the debug line: "AI 12 calls, 4,310 in / 1,020 out tokens, about $0.010". */
    fun line(): String = "AI %d calls, %,d in / %,d out tokens, about $%.3f (list price, unverified)".format(calls, tokensIn, tokensOut, usd)

    private companion object {
        const val CALLS = "ai.calls"
        const val IN = "ai.in"
        const val OUT = "ai.out"
        const val DAY = "ai.day."
    }
}
