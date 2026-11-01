package app.mapa.core

import app.mapa.core.engine.EngineFactory
import app.mapa.routine.RhythmWeights
import java.io.File

/** The trained weights from routine-engine/models, if present next to this project. */
object TestModels {
    val weights: RhythmWeights? by lazy {
        listOf("../routine-engine/models/rhythm_v2.weights.txt", "../../routine-engine/models/rhythm_v2.weights.txt")
            .map { File(it) }.firstOrNull { it.exists() }?.let { RhythmWeights.load(it) }
    }

    fun factory() = EngineFactory(weights)
}
