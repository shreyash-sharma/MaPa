package app.mapa.parent.rhythm

import android.content.Context
import android.util.Log
import app.mapa.routine.FeatureBuilder
import app.mapa.routine.KotlinCnnBackbone
import app.mapa.routine.RhythmBackbone
import app.mapa.routine.RhythmWeights
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlin.math.abs

/**
 * The rhythm CNN run by Meta's ExecuTorch runtime (XNNPACK, float32). The program is
 * `ml/mapa_rhythm/export_executorch.py`'s `(x[1,18], h[1,S]) -> (logits[1,16], h'[1,S])`, the same contract as
 * [RhythmBackbone.step]. [load] checks it against the Kotlin CNN on a fixed input first and returns null on any
 * problem, so the caller falls back to [KotlinCnnBackbone], which computes the same numbers from the same weights.
 */
class ExecuTorchBackbone private constructor(
    private val module: Module,
    override val hiddenSize: Int,
    /** Inputs the exported program takes (18 for the shipped CNN). The feature row now has 21: pass the first ones. */
    private val inputs: Int,
) : RhythmBackbone {
    private val xShape = longArrayOf(1, inputs.toLong())
    private val hShape = longArrayOf(1, hiddenSize.toLong())

    override fun step(x: FloatArray, h: FloatArray): FloatArray {
        // A wrong shape aborts the process inside ExecuTorch (no exception), so never pass more than the program takes.
        val input = if (x.size == inputs) x else x.copyOf(inputs)
        val out = module.forward(EValue.from(Tensor.fromBlob(input, xShape)), EValue.from(Tensor.fromBlob(h, hShape)))
        out[1].toTensor().dataAsFloatArray.copyInto(h)
        return out[0].toTensor().dataAsFloatArray
    }

    companion object {
        private const val TAG = "MaPaExecuTorch"

        fun load(ctx: Context, asset: String, weights: RhythmWeights): RhythmBackbone? = try {
            val file = File(ctx.filesDir, "models/" + asset.substringAfterLast('/'))
            file.parentFile?.mkdirs()
            // openFd works only for uncompressed assets; fall back to counting bytes so a compressed one still loads.
            val size = runCatching { ctx.assets.openFd(asset).use { it.length } }.getOrNull()
                ?: ctx.assets.open(asset).use { s -> var n = 0L; val buf = ByteArray(64 * 1024); while (true) { val r = s.read(buf); if (r < 0) break; n += r }; n }
            if (!file.exists() || file.length() != size) {
                ctx.assets.open(asset).use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            }
            val reference = KotlinCnnBackbone(weights)
            val inputs = weights["cnn.conv0.weight"].cols / 3
            val et = ExecuTorchBackbone(Module.load(file.absolutePath), reference.hiddenSize, inputs)
            if (agrees(et, reference)) et else null.also { Log.w(TAG, "ExecuTorch output differs from the Kotlin CNN; using Kotlin") }
        } catch (e: Throwable) {
            Log.w(TAG, "ExecuTorch unavailable; using the Kotlin CNN", e)
            null
        }

        /** Three steps on a fixed input: logits and state must match within float noise. */
        private fun agrees(a: RhythmBackbone, b: RhythmBackbone): Boolean {
            val ha = FloatArray(a.hiddenSize)
            val hb = FloatArray(b.hiddenSize)
            val x = FloatArray(FeatureBuilder.N) { 0.05f * ((it % 7) - 3) }
            repeat(3) {
                val za = a.step(x.copyOf(), ha)
                val zb = b.step(x.copyOf(), hb)
                if (za.size != zb.size || za.indices.any { abs(za[it] - zb[it]) > 1e-3f }) return false
                if (ha.indices.any { abs(ha[it] - hb[it]) > 1e-3f }) return false
            }
            return true
        }
    }
}
