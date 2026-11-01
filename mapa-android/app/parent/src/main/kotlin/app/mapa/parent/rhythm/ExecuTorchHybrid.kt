package app.mapa.parent.rhythm

import android.content.Context
import android.util.Log
import app.mapa.routine.HybridModel
import app.mapa.routine.KotlinHybrid
import app.mapa.routine.RhythmWeights
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.abs

/**
 * The MaPa Rhythm Hybrid run by ExecuTorch (XNNPACK): `rhythm-step.pte` streams one 15-minute bin with explicit
 * state, `rhythm-daily.pte` encodes the last 30 days. [load] checks both against the Kotlin reference first and
 * returns null on any problem, so the caller uses [KotlinHybrid], which computes the same numbers.
 */
class ExecuTorchHybrid private constructor(
    private val step: Module,
    private val daily: Module,
    override val hiddenSize: Int,
    channels: Int,
) : HybridModel {
    private val convSize = 2 * 1 * 21 + 2 * 2 * channels + 2 * 4 * channels + 2 * 8 * channels
    private var conv = FloatArray(convSize)
    private var gru = FloatArray(hiddenSize)
    private var memory = FloatArray(MEMORY * hiddenSize)
    private var memoryCount = longArrayOf(0)

    override fun reset() {
        conv = FloatArray(convSize); gru = FloatArray(hiddenSize); memory = FloatArray(MEMORY * hiddenSize)
        memoryCount = longArrayOf(0)
    }

    override fun encodeDaily(rows: Array<FloatArray>, count: Int): FloatArray {
        val flat = FloatArray(30 * 9).also { f -> rows.forEachIndexed { t, r -> r.copyInto(f, t * 9) } }
        return daily.forward(
            EValue.from(Tensor.fromBlob(flat, longArrayOf(1, 30, 9))),
            EValue.from(Tensor.fromBlob(longArrayOf(count.toLong()), longArrayOf(1))),
        )[0].toTensor().dataAsFloatArray
    }

    override fun step(x: FloatArray, slowVector: FloatArray): FloatArray {
        val out = step.forward(
            EValue.from(Tensor.fromBlob(x, longArrayOf(1, 21))),
            EValue.from(Tensor.fromBlob(conv, longArrayOf(1, convSize.toLong()))),
            EValue.from(Tensor.fromBlob(gru, longArrayOf(1, 1, hiddenSize.toLong()))),
            EValue.from(Tensor.fromBlob(memory, longArrayOf(1, MEMORY.toLong(), hiddenSize.toLong()))),
            EValue.from(Tensor.fromBlob(memoryCount, longArrayOf(1))),
            EValue.from(Tensor.fromBlob(slowVector, longArrayOf(1, hiddenSize.toLong()))),
        )
        conv = out[1].toTensor().dataAsFloatArray
        gru = out[2].toTensor().dataAsFloatArray
        memory = out[3].toTensor().dataAsFloatArray
        memoryCount = out[4].toTensor().dataAsLongArray
        return out[0].toTensor().dataAsFloatArray
    }

    override fun saveState(): FloatArray = conv + gru + floatArrayOf(memoryCount[0].toFloat()) + memory

    override fun loadState(state: FloatArray) {
        conv = state.copyOfRange(0, convSize)
        gru = state.copyOfRange(convSize, convSize + hiddenSize)
        memoryCount = longArrayOf(state[convSize + hiddenSize].toLong())
        memory = state.copyOfRange(convSize + hiddenSize + 1, state.size)
    }

    companion object {
        private const val TAG = "MaPaExecuTorch"
        private const val MEMORY = 96

        fun load(ctx: Context, stepAsset: String, dailyAsset: String, weights: RhythmWeights): HybridModel? = try {
            val config = weights["hybrid.config"].data
            val et = ExecuTorchHybrid(
                Module.load(copy(ctx, stepAsset).absolutePath), Module.load(copy(ctx, dailyAsset).absolutePath),
                hiddenSize = config[1].toInt(), channels = config[0].toInt(),
            )
            if (agrees(et, KotlinHybrid(weights))) et
            else null.also { Log.w(TAG, "ExecuTorch Hybrid differs from the Kotlin reference; using Kotlin") }
        } catch (e: Throwable) {
            Log.w(TAG, "ExecuTorch Hybrid unavailable; using the Kotlin reference", e)
            null
        }

        private fun copy(ctx: Context, asset: String): File {
            val file = File(ctx.filesDir, "models/" + asset.substringAfterLast('/'))
            file.parentFile?.mkdirs()
            val size = runCatching { ctx.assets.openFd(asset).use { it.length } }.getOrNull()
                ?: ctx.assets.open(asset).use { s -> var n = 0L; val buf = ByteArray(64 * 1024); while (true) { val r = s.read(buf); if (r < 0) break; n += r }; n }
            if (!file.exists() || file.length() != size) ctx.assets.open(asset).use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            return file
        }

        /** Three days of history and four steps on fixed inputs: outputs must match within float noise. */
        private fun agrees(a: HybridModel, b: HybridModel): Boolean {
            val rows = Array(30) { t -> FloatArray(9) { j -> if (t < 3) 0.1f * ((t + j) % 5) else 0f } }
            val sa = a.encodeDaily(rows, 3)
            val sb = b.encodeDaily(rows, 3)
            if (sa.size != sb.size || sa.indices.any { abs(sa[it] - sb[it]) > 1e-3f }) return false
            a.reset(); b.reset()
            val x = FloatArray(21) { 0.05f * ((it % 7) - 3) }
            repeat(4) {
                val za = a.step(x.copyOf(), sa)
                val zb = b.step(x.copyOf(), sb)
                if (za.size != zb.size || za.indices.any { abs(za[it] - zb[it]) > 1e-3f }) return false
            }
            a.reset(); b.reset()
            return true
        }
    }
}
