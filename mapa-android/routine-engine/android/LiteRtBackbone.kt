package app.mapa.routine.android

import app.mapa.routine.RhythmBackbone
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Optional LiteRT-backed [RhythmBackbone] for the parent app module (needs
 * `com.google.ai.edge.litert:litert`). Not compiled in this repo, which has no Android
 * toolchain: drop it into the app module. Uses `rhythm_step_int8.tflite`
 * (signature `serving_default`, inputs args_0 float32[1,16] and args_1 float32[1,48],
 * outputs output_0 = logits float32[1,16] and output_1 = new hidden state float32[1,48]).
 */
class LiteRtBackbone(model: ByteBuffer, override val hiddenSize: Int = 48) : RhythmBackbone {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
    private val x = Array(1) { FloatArray(16) }
    private val hIn = Array(1) { FloatArray(hiddenSize) }
    private val logits = Array(1) { FloatArray(16) }
    private val hOut = Array(1) { FloatArray(hiddenSize) }

    override fun step(x: FloatArray, h: FloatArray): FloatArray {
        x.copyInto(this.x[0])
        h.copyInto(hIn[0])
        interpreter.runSignature(
            mapOf("args_0" to this.x, "args_1" to hIn),
            mapOf("output_0" to logits, "output_1" to hOut),
            "serving_default",
        )
        hOut[0].copyInto(h)
        return logits[0].copyOf()
    }

    fun close() = interpreter.close()
}
