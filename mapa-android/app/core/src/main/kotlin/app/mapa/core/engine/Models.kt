package app.mapa.core.engine

import app.mapa.routine.Bins
import app.mapa.routine.EngineConfig
import app.mapa.routine.HybridPipeline
import app.mapa.routine.RhythmBackbone
import app.mapa.routine.RhythmBackbones
import app.mapa.routine.RhythmWeights
import app.mapa.routine.RoutineEngine
import kotlin.math.ln

/** Which rhythm model the phone is running. Shown to the parent and synced so the family can see it too. */
enum class ModelKind(val wire: String) {
    /** The population-pretrained GRU from routine-engine/models, personalised on this phone. */
    RHYTHM_GRU("rhythm-gru"),

    /** The causal dilated 1D-CNN (run with ExecuTorch where available), personalised on this phone the same way. */
    RHYTHM_CNN("rhythm-cnn"),

    /** No weights bundled yet: profile-only hazards, rules decide. Honest fallback, never claims model confidence. */
    PROFILE_ONLY("profile-only"),
}

/**
 * Builds (or restores) the on-device routine engine from whatever model this build ships.
 * [modelId] names the exact weights, e.g. "rhythm_v2"; a snapshot taken under another id is
 * not restored, the journal is replayed through the new model instead.
 */
class EngineFactory(
    private val weights: RhythmWeights?,
    modelId: String = "rhythm_v2-cal1",
    private val config: EngineConfig = EngineConfig(),
    /** Builds the network from the weights. The parent app passes one that tries ExecuTorch first. */
    private val backbone: (RhythmWeights) -> RhythmBackbone = RhythmBackbones::create,
    /** Filled while the engine runs, for the "Under the hood" screen. */
    val stats: BackboneStats = BackboneStats(),
    /** The tested MaPa Rhythm Hybrid with its decision layer; when present it decides Unusual from day one. */
    private val hybrid: (() -> HybridPipeline)? = null,
) {
    val hasHybrid: Boolean get() = hybrid != null && weights != null

    /** The step time shown to the family belongs to the model that decides: the Hybrid when it ships. */
    private fun timed(b: RhythmBackbone): RhythmBackbone = if (hasHybrid) b else TimedBackbone(b, stats)

    val kind: ModelKind = when {
        weights == null -> ModelKind.PROFILE_ONLY
        "cnn.dilations" in weights.tensors -> ModelKind.RHYTHM_CNN
        else -> ModelKind.RHYTHM_GRU
    }
    val modelId: String = if (weights != null) modelId else kind.wire

    /** Only the trained model may decide states; the fallback runs rules with the profile in the forecast. */
    val modelDecides: Boolean get() = kind != ModelKind.PROFILE_ONLY && config.modelDecides

    /** Clean days behind the silence prior; older weights files without the tensor count as none. */
    private val silencePriorDays: Double =
        weights?.tensors?.get("silence_prior_days")?.data?.firstOrNull()?.toDouble() ?: 0.0

    fun create(installT: Int): RoutineEngine = if (weights != null) {
        RoutineEngine(
            config, timed(backbone(weights)), weights.prior(), weights["silence_prior"].data,
            Bins.EPOCH_DOW, installT, silencePriorDays, hybrid?.invoke(),
        )
    } else {
        RoutineEngine(
            config.copy(modelDecides = false), ProfileRateBackbone(), FallbackPrior.prior(),
            FallbackPrior.silenceHistogram(), Bins.EPOCH_DOW, installT,
        )
    }

    fun restore(snapshot: String): RoutineEngine = if (weights != null) {
        RoutineEngine.restore(config, timed(backbone(weights)), weights.prior(), weights["silence_prior"].data, snapshot, silencePriorDays, hybrid?.invoke())
    } else {
        RoutineEngine.restore(
            config.copy(modelDecides = false), ProfileRateBackbone(), FallbackPrior.prior(),
            FallbackPrior.silenceHistogram(), snapshot,
        )
    }
}

/**
 * Stand-in backbone used until the trained weights are bundled: turns the personal profile
 * rates already present in the feature row (x[11..14]) into hazard logits for t+1..t+16.
 * It has no hidden state and learns only through the profile and personal head.
 */
class ProfileRateBackbone : RhythmBackbone {
    override val hiddenSize = 1

    override fun step(x: FloatArray, h: FloatArray): FloatArray = FloatArray(Bins.HORIZON) { k ->
        val rate = when (k) {
            0 -> x[11]
            in 1..3 -> x[12]
            in 4..7 -> x[13]
            else -> x[14]
        }
        logit(rate.toDouble().coerceIn(0.002, 0.998)).toFloat()
    }

    private fun logit(p: Double) = ln(p / (1 - p))
}

/**
 * Generic day shape for day one when no pretrained prior is bundled: little use overnight,
 * a morning start around 6:30 to 8:00, steady daytime use, tapering after 21:00.
 * Placeholder only; the trained model ships the population prior from synthetic data.
 */
object FallbackPrior {
    fun prior(): Array<DoubleArray> = Array(3) { dt ->
        val lateStart = if (dt == 0) 0 else 2 // weekends start about 30 minutes later
        DoubleArray(Bins.PER_DAY) { b ->
            val s = b - lateStart
            when {
                s < 24 -> 0.02 // before 06:00
                s < 32 -> 0.02 + (s - 24) * 0.04 // 06:00 to 08:00 ramp
                b < 84 -> 0.32 // daytime until 21:00
                b < 92 -> 0.32 - (b - 84) * 0.035 // taper to 23:00
                else -> 0.04
            }
        }
    }

    /** Histogram (bin width 0.25 nats) of the surprise of ordinary silences; exponential-ish placeholder. */
    fun silenceHistogram(): FloatArray = FloatArray(80) { i -> (100.0 * Math.exp(-i / 6.0)).toFloat() }
}
