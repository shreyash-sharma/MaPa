package app.mapa.parent.rhythm

import android.content.Context
import android.util.Log
import app.mapa.core.engine.EngineFactory
import app.mapa.core.engine.TimedHybrid
import app.mapa.routine.Bins
import app.mapa.routine.ColdStartPriors
import app.mapa.routine.HybridModel
import app.mapa.routine.HybridPipeline
import app.mapa.routine.HybridDecisionPolicy
import app.mapa.routine.KotlinHybrid
import app.mapa.routine.RhythmBackbone
import app.mapa.routine.RhythmBackbones
import app.mapa.routine.RhythmWeights

/**
 * Loads the bundled rhythm model once per process. The MaPa Rhythm Hybrid v2 policy decides
 * Unusual from day one and runs on ExecuTorch when both programs load and agree with the Kotlin reference. The
 * causal 1D-CNN still drives the "usually by" forecast and the nightly personal head. The earlier GRU stays as the
 * last resort if the CNN files are missing from the build.
 */
object ModelStore {
    const val CNN_ID = "rhythm_cnn-cal1"
    const val HYBRID_ID = "rhythm_hybrid-v2-policy+cnn-cal1"
    private const val HYBRID_WEIGHTS = "models/hybrid_reference.weights.txt"
    private const val HYBRID_PRIORS = "models/hybrid_priors.txt"
    private const val HYBRID_STEP = "models/rhythm-step.pte"
    private const val HYBRID_DAILY = "models/rhythm-daily.pte"
    const val GRU_ID = "rhythm_v2-cal1"
    private const val CNN_WEIGHTS = "models/rhythm_cnn.weights.txt"
    private const val CNN_PROGRAM = "models/rhythm_cnn.pte"
    private const val GRU_WEIGHTS = "models/rhythm_v2.weights.txt"

    @Volatile
    private var factory: EngineFactory? = null

    /** "ExecuTorch (XNNPACK)", "Kotlin", or "none": which runtime is computing the network on this phone. */
    @Volatile
    var runtime: String = "none"
        private set

    fun factory(ctx: Context): EngineFactory = factory ?: synchronized(this) {
        factory ?: build(ctx.applicationContext).also { factory = it }
    }

    private fun build(ctx: Context): EngineFactory {
        load(ctx, CNN_WEIGHTS)?.let { w ->
            val hybrid = hybridFactory(ctx)
            lateinit var f: EngineFactory
            f = EngineFactory(w, if (hybrid != null) HYBRID_ID else CNN_ID, backbone = { weights -> pick(ctx, weights, f) }, hybrid = hybrid)
            val hw = if (hybrid != null) load(ctx, HYBRID_WEIGHTS) else null
            f.stats.params = hw?.let { params(it) } ?: params(w)
            f.stats.modelBytes = if (hybrid != null) (assetBytes(ctx, HYBRID_STEP) ?: 0) + (assetBytes(ctx, HYBRID_DAILY) ?: 0)
            else assetBytes(ctx, CNN_PROGRAM) ?: assetBytes(ctx, CNN_WEIGHTS) ?: 0
            return f
        }
        val gru = load(ctx, GRU_WEIGHTS)
        runtime = if (gru != null) "Kotlin" else "none"
        return EngineFactory(gru, GRU_ID).also {
            it.stats.runtime = runtime
            if (gru != null) { it.stats.params = params(gru); it.stats.modelBytes = assetBytes(ctx, GRU_WEIGHTS) ?: 0 }
        }
    }

    private fun pick(ctx: Context, weights: RhythmWeights, f: EngineFactory): RhythmBackbone {
        val et = ExecuTorchBackbone.load(ctx, CNN_PROGRAM, weights)
        if (!f.hasHybrid) {
            runtime = if (et != null) "ExecuTorch (XNNPACK)" else "Kotlin"
            f.stats.runtime = runtime
        }
        return et ?: RhythmBackbones.create(weights)
    }

    /** A new pipeline per engine (create or restore); the model programs are loaded once per process. */
    private fun hybridFactory(ctx: Context): (() -> HybridPipeline)? {
        val weights = load(ctx, HYBRID_WEIGHTS) ?: return null
        val priors = try {
            ctx.assets.open(HYBRID_PRIORS).reader().use { ColdStartPriors.parse(it) }
        } catch (e: Exception) {
            Log.e("MaPaModel", "$HYBRID_PRIORS missing from assets", e)
            return null
        }
        var model: HybridModel? = null
        return {
            val m = model ?: (ExecuTorchHybrid.load(ctx, HYBRID_STEP, HYBRID_DAILY, weights)
                ?.also { runtime = "ExecuTorch (XNNPACK)" }
                ?: KotlinHybrid(weights).also { runtime = "Kotlin" }).also { model = it }
            factory?.stats?.runtime = runtime
            factory?.stats?.let { st -> if (st.benchMicros == 0L) st.benchMicros = benchmark(m) }
            m.reset()
            HybridPipeline(factory?.stats?.let { TimedHybrid(m, it) } ?: m, priors,
                Bins.EPOCH_DOW, HybridDecisionPolicy.V2)
        }
    }

    /**
     * Typical time for one 15-minute step on this phone: 30 warm-up steps, then the median of 100. Each background
     * run is a fresh process that steps only a few bins, so a running figure would only ever see warm-up (~30 ms once).
     */
    private fun benchmark(m: HybridModel): Long {
        val slow = m.encodeDaily(Array(30) { FloatArray(9) }, 0)
        val x = FloatArray(21) { 0.05f * ((it % 7) - 3) }
        m.reset()
        repeat(30) { m.step(x.copyOf(), slow) }
        val t = LongArray(100) { val t0 = System.nanoTime(); m.step(x.copyOf(), slow); System.nanoTime() - t0 }
        m.reset()
        return t.sorted()[50] / 1000
    }

    /** Network parameters: weights and biases only (not the prior tables or the dilation settings). */
    private fun params(w: RhythmWeights): Int =
        w.tensors.filterKeys { !it.contains("prior") && it != "cnn.dilations" && it != "hybrid.config" }.values.sumOf { it.data.size }

    private fun assetBytes(ctx: Context, asset: String): Long? = try {
        ctx.assets.open(asset).use { s -> var n = 0L; val buf = ByteArray(64 * 1024); while (true) { val r = s.read(buf); if (r < 0) break; n += r }; n }
    } catch (e: Exception) { null }

    private fun load(ctx: Context, asset: String): RhythmWeights? = try {
        ctx.assets.open(asset).reader().use { RhythmWeights.parse(it) }
    } catch (e: Exception) {
        Log.e("MaPaModel", "$asset missing from assets", e)
        null
    }
}
