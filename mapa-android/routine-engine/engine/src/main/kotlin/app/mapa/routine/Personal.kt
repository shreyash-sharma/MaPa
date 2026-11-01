package app.mapa.routine

import kotlin.math.abs
import kotlin.math.ln

/**
 * The part of the rhythm model each phone trains on its own parent's days.
 *
 * hazard_k = sigmoid(alpha * z_k + beta * logit(rate_k) + gamma_group(k)), where z_k is the
 * backbone logit and rate_k the personal profile rate for bin t+k. Six parameters, fitted
 * by regularised Newton steps in milliseconds, so nightly retraining is cheap. The
 * personal profile ([Profile]) is the other learned half.
 */
class PersonalHead(val theta: DoubleArray = IDENTITY.copyOf()) {
    init {
        require(theta.size == SIZE)
    }

    fun hazard(k: Int, z: Float, rate: Float): Double {
        val a = theta[0] * z + theta[1] * logit(rate.toDouble().clampProb()) + theta[2 + group(k)]
        return sigmoid(a).clampProb(1e-6, 1 - 1e-6)
    }

    fun hazards(z: FloatArray, rates: FloatArray) = DoubleArray(z.size) { hazard(it, z[it], rates[it]) }

    /** Mean absolute change in next-bin hazard over the given ticks: the distance the anchor guard limits. */
    fun distanceTo(other: PersonalHead, ticks: List<Pair<Float, Float>>): Double {
        if (ticks.isEmpty()) return 0.0
        return ticks.sumOf { (z, r) -> abs(hazard(0, z, r) - other.hazard(0, z, r)) } / ticks.size
    }

    fun stepToward(target: PersonalHead, lambda: Double) =
        PersonalHead(DoubleArray(SIZE) { theta[it] + lambda * (target.theta[it] - theta[it]) })

    companion object {
        const val SIZE = 6
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        fun group(k: Int) = when (k) {
            0 -> 0
            in 1..3 -> 1
            in 4..7 -> 2
            else -> 3
        }

        class Examples(val z: FloatArray, val rate: FloatArray, val k: IntArray, val y: BooleanArray) {
            val size get() = y.size
        }

        /**
         * Discrete-time survival examples from cached ticks of the given days: for tick t and
         * horizon k, bins t+1..t+k must be observed and t+1..t+k-1 silent, all inside [days].
         */
        fun examples(days: Map<Int, DayRecord>, maxExamples: Int = 80_000): Examples {
            val zs = ArrayList<Float>(); val rs = ArrayList<Float>(); val ks = ArrayList<Int>(); val ys = ArrayList<Boolean>()
            fun bin(u: Int): Int? { // -1 unobserved, null outside days
                val d = days[Bins.day(u)] ?: return null
                return d.activity(Bins.binOfDay(u))
            }
            outer@ for (day in days.keys.sorted()) {
                val rec = days.getValue(day)
                for (b in 0 until Bins.PER_DAY) {
                    val z = rec.logits[b]
                    if (z.isEmpty()) continue
                    val t = day * Bins.PER_DAY + b
                    for (k in 0 until Bins.HORIZON) {
                        val c = bin(t + k + 1) ?: break
                        if (c < 0) break
                        zs += z[k]; rs += rec.rates[b][k]; ks += k; ys += c > 0
                        if (c > 0) break
                    }
                    if (ys.size >= maxExamples) break@outer
                }
            }
            return Examples(zs.toFloatArray(), rs.toFloatArray(), ks.toIntArray(), ys.toBooleanArray())
        }

        /** Ridge-regularised logistic regression toward [center], by Newton's method. */
        fun fit(ex: Examples, center: DoubleArray = IDENTITY, lambda: Double = 20.0, iterations: Int = 15): PersonalHead {
            val theta = center.copyOf()
            val phi = DoubleArray(SIZE)
            repeat(iterations) {
                val g = DoubleArray(SIZE) { lambda * (theta[it] - center[it]) }
                val hm = Array(SIZE) { i -> DoubleArray(SIZE) { j -> if (i == j) lambda else 0.0 } }
                for (n in 0 until ex.size) {
                    phi.fill(0.0)
                    phi[0] = ex.z[n].toDouble()
                    phi[1] = logit(ex.rate[n].toDouble().clampProb())
                    phi[2 + group(ex.k[n])] = 1.0
                    var a = 0.0
                    for (i in 0 until SIZE) a += theta[i] * phi[i]
                    val p = sigmoid(a)
                    val err = p - (if (ex.y[n]) 1.0 else 0.0)
                    val w = p * (1 - p)
                    for (i in 0 until SIZE) {
                        if (phi[i] == 0.0) continue
                        g[i] += err * phi[i]
                        for (j in 0 until SIZE) hm[i][j] += w * phi[i] * phi[j]
                    }
                }
                val step = solve(hm, g)
                var mx = 0.0
                for (i in 0 until SIZE) {
                    theta[i] -= step[i]
                    mx = maxOf(mx, abs(step[i]))
                }
                if (mx < 1e-6) return PersonalHead(theta)
            }
            return PersonalHead(theta)
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
            for (c in 0 until n) {
                val piv = (c until n).maxBy { abs(m[it][c]) }
                val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
                for (r in 0 until n) if (r != c) {
                    val f = m[r][c] / m[c][c]
                    for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}

/**
 * Turns the running surprise of a silence (negative log-likelihood of "no interaction
 * so far" under the personal hazards) into a calibrated 0..1 score: the share of this
 * parent's ordinary completed silences that were less surprising, blended with a
 * population prior while personal history is short.
 */
class SilenceCalibrator(
    private val priorHistogram: FloatArray,
    personal: DoubleArray,
    /** Clean days the personal silences came from. */
    val personalDays: Int = 0,
    /** Days behind [priorHistogram]; 0 disables the rate. */
    private val priorDays: Double = 0.0,
    private val priorWeight: Double = 200.0,
    /** Pseudo-days of prior in [weeklyRate]. */
    private val priorDayWeight: Double = 14.0,
) {
    private val sorted = personal.sortedArray()
    private val priorCdf: DoubleArray = run {
        val total = priorHistogram.sum().toDouble().coerceAtLeast(1e-9)
        var acc = 0.0
        DoubleArray(priorHistogram.size + 1) { i -> if (i == 0) 0.0 else { acc += priorHistogram[i - 1]; acc / total } }
    }
    val personalCount: Int get() = sorted.size
    internal val personalValues: DoubleArray get() = sorted

    fun score(nll: Double): Double {
        val below = lowerBound(sorted, nll)
        return (below + priorWeight * prior(nll)) / (sorted.size + priorWeight)
    }

    /**
     * How many ordinary silences per week reach this surprise for this parent, blended with
     * the population while history is short. The model calls a silence unusual when this
     * drops below a set rate (default once in four weeks), which fixes the false-alert budget
     * per parent instead of per silence.
     */
    fun weeklyRate(nll: Double): Double {
        val exceed = (sorted.size - lowerBound(sorted, nll)).toDouble()
        val priorPerDay = if (priorDays > 0) (1 - prior(nll)) * priorTotal / priorDays else 0.0
        return 7 * (exceed + priorDayWeight * priorPerDay) / (personalDays + priorDayWeight)
    }

    private val priorTotal = priorHistogram.sum().toDouble()

    private fun prior(nll: Double): Double {
        val pos = nll / BIN_WIDTH
        if (pos <= 0) return 0.0
        if (pos >= priorHistogram.size) return 1.0
        val i = pos.toInt()
        return priorCdf[i] + (pos - i) * (priorCdf[i + 1] - priorCdf[i])
    }

    companion object {
        const val BIN_WIDTH = 0.25

        private fun lowerBound(a: DoubleArray, v: Double): Int {
            var lo = 0; var hi = a.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] < v) lo = mid + 1 else hi = mid }
            return lo
        }

        /**
         * Final surprise of every completed silence in [days], replayed from the cached ticks.
         * Only silences whose start was seen and that ran without a gap in the cache count.
         */
        fun stretchSurprises(days: Map<Int, DayRecord>, head: PersonalHead): DoubleArray {
            val out = ArrayList<Double>()
            var nll = 0.0
            var silentBins = 0
            var seenStart = false
            var prevHazard: Double? = null
            var prevT: Int? = null
            for (day in days.keys.sorted()) {
                val rec = days.getValue(day)
                for (b in 0 until Bins.PER_DAY) {
                    val t = day * Bins.PER_DAY + b
                    if (prevT != t - 1) { seenStart = false; prevHazard = null }
                    val c = rec.activity(b)
                    val h = prevHazard
                    if (c > 0) {
                        if (seenStart && silentBins > 0) out += nll
                        seenStart = true; nll = 0.0; silentBins = 0
                    } else if (c == 0) {
                        if (h == null) seenStart = false else { nll += -ln(1 - h); silentBins++ }
                    }
                    val z = rec.logits[b]
                    prevHazard = if (z.isEmpty()) null else head.hazard(0, z[0], rec.rates[b][0])
                    prevT = t
                }
            }
            return out.toDoubleArray()
        }
    }
}
