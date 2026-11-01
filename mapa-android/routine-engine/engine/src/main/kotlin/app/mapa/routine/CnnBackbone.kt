package app.mapa.routine

/**
 * Causal dilated 1D-CNN (kernel 3, ReLU, residual from layer 2 on) followed by a linear head over
 * [last features, x]. Streaming form of `ml/mapa_rhythm/cnn.py`: the state vector `h` holds the last
 * `2 * dilation` inputs of every layer, oldest first, so a zero state equals zero left padding.
 * Conv weights are rows of [out][tap * in], tap 0 the oldest input.
 */
class KotlinCnnBackbone(w: RhythmWeights) : RhythmBackbone {
    private val dilations = w["cnn.dilations"].data.map { it.toInt() }
    private val convW = dilations.indices.map { w["cnn.conv$it.weight"] }
    private val convB = dilations.indices.map { w["cnn.conv$it.bias"].data }
    private val channels = convW[0].rows
    private val hw = w["head.weight"]
    private val hb = w["head.bias"].data
    private val nIn = convW[0].cols / TAPS
    private val offsets = IntArray(dilations.size)
    override val hiddenSize: Int

    init {
        require(nIn == 18 || nIn == FeatureBuilder.N) {
            "weights expect $nIn inputs but the engine builds ${FeatureBuilder.N}: bundle a matching weights file"
        }
        var off = 0
        for (i in dilations.indices) {
            offsets[i] = off
            off += (TAPS - 1) * dilations[i] * convW[i].cols / TAPS
        }
        hiddenSize = off
    }

    private var cur = FloatArray(maxOf(channels, nIn))
    private val y = FloatArray(channels)

    override fun step(x: FloatArray, h: FloatArray): FloatArray {
        x.copyInto(cur, endIndex = nIn)
        for (i in dilations.indices) {
            val d = dilations[i]
            val cin = convW[i].cols / TAPS
            val n = (TAPS - 1) * d * cin
            val off = offsets[i]
            val wd = convW[i].data
            val bias = convB[i]
            val mid = off + d * cin
            for (o in 0 until channels) {
                var a = bias[o]
                val row = o * TAPS * cin
                for (c in 0 until cin) {
                    a += wd[row + c] * h[off + c] + wd[row + cin + c] * h[mid + c] + wd[row + 2 * cin + c] * cur[c]
                }
                y[o] = if (a > 0f) a else 0f
            }
            System.arraycopy(h, off + cin, h, off, n - cin)
            System.arraycopy(cur, 0, h, off + n - cin, cin)
            if (i == 0) y.copyInto(cur, endIndex = channels) else for (o in 0 until channels) cur[o] += y[o]
        }
        val out = FloatArray(hw.rows)
        for (k in 0 until hw.rows) {
            var a = hb[k]
            val off = k * hw.cols
            for (c in 0 until channels) a += hw.data[off + c] * cur[c]
            for (c in 0 until nIn) a += hw.data[off + channels + c] * x[c]
            out[k] = a
        }
        return out
    }

    private companion object {
        const val TAPS = 3
    }
}

/** Picks the backbone a weights file was trained for. */
object RhythmBackbones {
    fun create(w: RhythmWeights): RhythmBackbone =
        if ("cnn.dilations" in w.tensors) KotlinCnnBackbone(w) else KotlinGruBackbone(w)

    /** Outputs zero logits: the engine then runs on the personal profile alone (the statistics baseline). */
    fun profileOnly(): RhythmBackbone = object : RhythmBackbone {
        override val hiddenSize = 1
        override fun step(x: FloatArray, h: FloatArray) = FloatArray(Bins.HORIZON)
    }
}
