package app.mapa.core.engine

import app.mapa.routine.RhythmBackbone

/** What the "Under the hood" screen shows about the network that is running: which one, and how fast on this phone. */
class BackboneStats {
    @Volatile var runtime: String = "none"
    /** Trained network parameters (the population prior tables are not counted). 0 when unknown. */
    @Volatile var params: Int = 0
    /** Bytes of the model file the phone runs (the ExecuTorch program, else the weights file). 0 when unknown. */
    @Volatile var modelBytes: Long = 0
    @Volatile var steps: Long = 0
        private set
    @Volatile private var totalNanos: Long = 0
    @Volatile var maxMicros: Long = 0
        private set

    private val recent = LongArray(256)

    /**
     * Typical time for one step: the median of the last 256 steps. A mean would be dominated by the first
     * steps after a start (program load, warm-up) and by garbage collection pauses, which are not the model.
     */
    /** A warmed-up benchmark taken once when the model loads (see ModelStore); preferred when present. */
    @Volatile var benchMicros: Long = 0

    val meanMicros: Long
        @Synchronized get() {
            if (benchMicros > 0) return benchMicros
            val n = minOf(steps, recent.size.toLong()).toInt()
            if (n == 0) return 0
            return recent.copyOf(n).sorted()[n / 2] / 1000
        }

    @Synchronized
    fun record(nanos: Long) {
        recent[(steps % recent.size).toInt()] = nanos
        steps++
        totalNanos += nanos
        val us = nanos / 1000
        if (us > maxMicros) maxMicros = us
    }
}

/** Times the Hybrid's 15-minute step, which is the model the family sees on "Under the hood". */
class TimedHybrid(private val inner: app.mapa.routine.HybridModel, private val stats: BackboneStats) : app.mapa.routine.HybridModel by inner {
    override fun step(x: FloatArray, slowVector: FloatArray): FloatArray {
        val t0 = System.nanoTime()
        val z = inner.step(x, slowVector)
        stats.record(System.nanoTime() - t0)
        return z
    }
}

/** Times every network step; the engine sees an ordinary [RhythmBackbone]. */
class TimedBackbone(private val inner: RhythmBackbone, private val stats: BackboneStats) : RhythmBackbone {
    override val hiddenSize get() = inner.hiddenSize
    override fun step(x: FloatArray, h: FloatArray): FloatArray {
        val t0 = System.nanoTime()
        val z = inner.step(x, h)
        stats.record(System.nanoTime() - t0)
        return z
    }
}
