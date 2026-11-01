package app.mapa.routine

import java.io.File
import java.io.Reader
import kotlin.math.exp
import kotlin.math.tanh

/**
 * The population-pretrained recurrent core, stepped once per 15-minute bin.
 * On Android this is backed by the LiteRT int8 model (`rhythm_step_int8.tflite`);
 * on the JVM by [KotlinGruBackbone], a direct float implementation used for tests,
 * the harness and as a fallback on phones where LiteRT is unavailable.
 */
interface RhythmBackbone {
    val hiddenSize: Int

    /** Returns hazard logits for bins t+1..t+16 and writes the new hidden state into [h]. */
    fun step(x: FloatArray, h: FloatArray): FloatArray
}

/** Tensors exported by `ml/mapa_rhythm/export.py` as plain text. */
class RhythmWeights(val tensors: Map<String, Tensor>) {
    class Tensor(val rows: Int, val cols: Int, val data: FloatArray) {
        operator fun get(r: Int, c: Int) = data[r * cols + c]
    }

    operator fun get(name: String): Tensor = tensors[name] ?: error("missing tensor $name")

    /** Population prior P(active) per day type x bin. */
    fun prior(): Array<DoubleArray> {
        val p = this["prior"]
        return Array(3) { dt -> DoubleArray(Bins.PER_DAY) { b -> p[dt, b].toDouble() } }
    }

    companion object {
        fun load(file: File) = file.reader().use { parse(it) }

        fun parse(reader: Reader): RhythmWeights {
            val lines = reader.buffered().lineSequence().iterator()
            require(lines.next().startsWith("mapa-rhythm")) { "not a MaPa rhythm weights file" }
            val out = LinkedHashMap<String, Tensor>()
            while (lines.hasNext()) {
                val head = lines.next().trim()
                if (head.isEmpty() || head.startsWith("#")) continue
                val (kw, name, r, c) = head.split(" ")
                require(kw == "tensor")
                val rows = r.toInt()
                val cols = c.toInt()
                val data = FloatArray(rows * cols)
                for (i in 0 until rows) {
                    val vals = lines.next().trim().split(" ")
                    require(vals.size == cols) { "$name row $i has ${vals.size} values, expected $cols" }
                    for (j in 0 until cols) data[i * cols + j] = vals[j].toFloat()
                }
                out[name] = Tensor(rows, cols, data)
            }
            return RhythmWeights(out)
        }
    }
}

/**
 * GRU cell (PyTorch gate order r, z, n) followed by a linear head over [h', x].
 * Matches `torch.nn.GRUCell` exactly in float32.
 */
class KotlinGruBackbone(w: RhythmWeights) : RhythmBackbone {
    private val wih = w["gru.weight_ih"]
    private val whh = w["gru.weight_hh"]
    private val bih = w["gru.bias_ih"].data
    private val bhh = w["gru.bias_hh"].data
    private val hw = w["head.weight"]
    private val hb = w["head.bias"].data
    override val hiddenSize = whh.cols
    private val nIn = wih.cols
    private val gi = FloatArray(3 * hiddenSize)
    private val gh = FloatArray(3 * hiddenSize)

    init {
        require(nIn == 18 || nIn == FeatureBuilder.N) {
            "weights expect $nIn inputs but the engine builds ${FeatureBuilder.N}: bundle rhythm_v2.weights.txt"
        }
    }

    override fun step(x: FloatArray, h: FloatArray): FloatArray {
        val hs = hiddenSize
        for (r in 0 until 3 * hs) {
            var a = bih[r]
            val off = r * nIn
            for (c in 0 until nIn) a += wih.data[off + c] * x[c]
            gi[r] = a
            var b = bhh[r]
            val offh = r * hs
            for (c in 0 until hs) b += whh.data[offh + c] * h[c]
            gh[r] = b
        }
        for (j in 0 until hs) {
            val rg = sig(gi[j] + gh[j])
            val zg = sig(gi[hs + j] + gh[hs + j])
            val ng = tanh(gi[2 * hs + j] + rg * gh[2 * hs + j])
            h[j] = (1f - zg) * ng + zg * h[j]
        }
        val out = FloatArray(hw.rows)
        for (k in 0 until hw.rows) {
            var a = hb[k]
            val off = k * hw.cols
            for (c in 0 until hs) a += hw.data[off + c] * h[c]
            for (c in 0 until nIn) a += hw.data[off + hs + c] * x[c]
            out[k] = a
        }
        return out
    }

    private fun sig(v: Float): Float = (1.0 / (1.0 + exp(-v.toDouble()))).toFloat()
}
