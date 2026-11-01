package app.mapa.routine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/** Float reference for hybrid parity. Outputs 16 hazards, use-now, unusual and confidence logits. */
class HybridReference(private val weights: RhythmWeights) {
    private val config = weights["hybrid.config"].data
    private val channels = config[0].toInt()
    private val hidden = config[1].toInt()
    private val personal = config[2] == 1f
    private val slow = config[3] == 1f
    private val attention = config[4] == 1f
    private val nextUseBlend = if (config.size > 5) config[5].toDouble() else 0.0
    private val dilations = intArrayOf(1, 2, 4, 8)
    private val buffers = Array(4) { i -> FloatArray(2 * dilations[i] * if (i == 0) 21 else channels) }
    private val recurrent = FloatArray(hidden)
    private val memory = ArrayList<FloatArray>()

    /** Hidden size of the GRU and the slow-lane LSTM. */
    val hiddenSize: Int get() = hidden

    fun saveState(): FloatArray {
        val out = ArrayList<Float>()
        buffers.forEach { b -> b.forEach { out.add(it) } }
        recurrent.forEach { out.add(it) }
        out.add(memory.size.toFloat())
        memory.forEach { m -> m.forEach { out.add(it) } }
        return out.toFloatArray()
    }

    fun loadState(state: FloatArray) {
        var k = 0
        buffers.forEach { b -> for (i in b.indices) b[i] = state[k++] }
        for (i in recurrent.indices) recurrent[i] = state[k++]
        memory.clear()
        repeat(state[k++].toInt()) { memory.add(FloatArray(hidden) { state[k++] }) }
    }

    /** Streaming state resets at each midnight, as in training and evaluation. */
    fun reset() {
        buffers.forEach { it.fill(0f) }
        recurrent.fill(0f)
        memory.clear()
    }

    private fun linear(name: String, x: FloatArray): FloatArray {
        val w = weights["$name.weight"]
        val bias = weights["$name.bias"].data
        return FloatArray(w.rows) { r ->
            var sum = bias[r].toDouble()
            for (c in x.indices) sum += w[r, c] * x[c].toDouble()
            sum.toFloat()
        }
    }

    private fun affine(w: RhythmWeights.Tensor, bias: FloatArray, x: FloatArray): FloatArray =
        FloatArray(w.rows) { r ->
            var sum = bias[r].toDouble()
            for (c in x.indices) sum += w[r, c] * x[c].toDouble()
            sum.toFloat()
        }

    fun encodeDaily(rows: Array<FloatArray>, count: Int): FloatArray {
        require(slow && count in 0..30 && rows.size == 30 && rows.all { it.size == 9 })
        val state = FloatArray(hidden)
        val cell = FloatArray(hidden)
        for (t in 0 until count) {
            val input = affine(weights["slow.weight_ih_l0"], weights["slow.bias_ih_l0"].data, rows[t])
            val recurrentPart = affine(weights["slow.weight_hh_l0"], weights["slow.bias_hh_l0"].data, state)
            for (j in 0 until hidden) {
                val i = sigmoid((input[j]+recurrentPart[j]).toDouble())
                val f = sigmoid((input[hidden+j]+recurrentPart[hidden+j]).toDouble())
                val g = tanh((input[2*hidden+j]+recurrentPart[2*hidden+j]).toDouble())
                val o = sigmoid((input[3*hidden+j]+recurrentPart[3*hidden+j]).toDouble())
                cell[j] = (f*cell[j]+i*g).toFloat()
                state[j] = (o*tanh(cell[j].toDouble())).toFloat()
            }
        }
        return state
    }

    fun step(x: FloatArray, slowVector: FloatArray): FloatArray {
        require(x.size == 21 && slowVector.size == hidden)
        var current = x.copyOf()
        for (i in dilations.indices) {
            val width = current.size
            val buf = buffers[i]
            val w = weights["cnn.convs.$i.weight"]
            val bias = weights["cnn.convs.$i.bias"].data
            val next = FloatArray(channels) { r ->
                var sum = bias[r].toDouble()
                for (c in 0 until width) {
                    sum += w[r, 3*c] * buf[c].toDouble()
                    sum += w[r, 3*c+1] * buf[dilations[i]*width+c].toDouble()
                    sum += w[r, 3*c+2] * current[c].toDouble()
                }
                maxOf(0.0, sum).toFloat()
            }
            System.arraycopy(buf, width, buf, 0, buf.size-width)
            current.copyInto(buf, buf.size-width)
            current = if (i == 0) next else FloatArray(channels) { current[it]+next[it] }
        }
        val gi = affine(weights["gru.weight_ih_l0"], weights["gru.bias_ih_l0"].data, current+x)
        val gh = affine(weights["gru.weight_hh_l0"], weights["gru.bias_hh_l0"].data, recurrent)
        for (j in 0 until hidden) {
            val reset = sigmoid((gi[j]+gh[j]).toDouble())
            val update = sigmoid((gi[hidden+j]+gh[hidden+j]).toDouble())
            val candidate = tanh(gi[2*hidden+j]+reset*gh[2*hidden+j])
            recurrent[j] = ((1-update)*candidate+update*recurrent[j]).toFloat()
        }
        var fast = recurrent.copyOf()
        memory.add(fast.copyOf()); if (memory.size > 96) memory.removeAt(0)
        if (attention) {
            val w = weights["attention.in_proj_weight"]
            val b = weights["attention.in_proj_bias"].data
            val query = affine(w, b, fast).copyOfRange(0, hidden)
            val keysValues = memory.map { affine(w, b, it) }
            val attended = FloatArray(hidden)
            val width = hidden/4
            for (head in 0 until 4) {
                val logits = DoubleArray(memory.size) { t ->
                    var sum = 0.0
                    for (j in 0 until width) { val c = head*width+j; sum += query[c]*keysValues[t][hidden+c].toDouble() }
                    sum/sqrt(width.toDouble())
                }
                val maximum = logits.maxOrNull()!!
                val probabilities = logits.map { exp(it-maximum) }; val total = probabilities.sum()
                for (j in 0 until width) {
                    val c = head*width+j
                    attended[c] = memory.indices.sumOf { probabilities[it]/total*keysValues[it][2*hidden+c] }.toFloat()
                }
            }
            val projected = linear("attention.out_proj", attended)
            fast = FloatArray(hidden) { fast[it]+projected[it] }
        }
        var context = fast+x
        if (personal) {
            val inputs = x.copyOfRange(11,16)+x.copyOfRange(18,20)
            context += linear("personal.0", inputs).map { tanh(it) }.toFloatArray()
        }
        if (slow) context += slowVector
        val hazards = linear("hazard_head", context)
        if (nextUseBlend > 0.0) {
            val modelProbability = 1.0 / (1.0 + exp(-hazards[0].toDouble()))
            val profileProbability = x[11].toDouble().coerceIn(1e-6, 1.0 - 1e-6)
            val blended = (nextUseBlend * modelProbability + (1.0 - nextUseBlend) * profileProbability)
                .coerceIn(1e-6, 1.0 - 1e-6)
            hazards[0] = ln(blended / (1.0 - blended)).toFloat()
        }
        return hazards+linear("use_now_head", context)+
            linear("behaviour_head", context)+linear("confidence_head", context)
    }
}
