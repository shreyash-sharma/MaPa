package app.mapa.parent.rhythm

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mapa.routine.HybridReference
import app.mapa.routine.RhythmWeights
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.util.Arrays
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the trained Hybrid's Kotlin streaming reference on a real Android device. */
@RunWith(AndroidJUnit4::class)
class HybridDeviceLatencyTest {
    @Test
    fun reportSteadyStateStreamingStepLatency() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val weights = testContext.assets.open("hybrid_reference.weights.txt").bufferedReader().use {
            RhythmWeights.parse(it)
        }
        val config = weights["hybrid.config"].data
        val channels = config[0].toInt()
        val hidden = config[1].toInt()
        val dailyInput = FloatArray(30 * 9)
        val dailyShape = longArrayOf(1, 30, 9)
        val countInput = longArrayOf(3)
        val countShape = longArrayOf(1)
        val dailyModule = loadModule(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, testContext, "rhythm-daily.pte")
        var slow = FloatArray(hidden)
        val dailyNanos = measure(30, 100) {
            slow = dailyModule.forward(
                EValue.from(Tensor.fromBlob(dailyInput, dailyShape)),
                EValue.from(Tensor.fromBlob(countInput, countShape)),
            )[0].toTensor().dataAsFloatArray
            slow
        }
        check(slow.size == hidden)
        val input = FloatArray(21) { 0.05f * ((it % 7) - 3) }

        // Warm the Kotlin reference independently from ExecuTorch.
        val reference = HybridReference(weights)
        val kotlinNanos = measure(WARMUP_STEPS, MEASURED_STEPS) { reference.step(input, slow) }

        // Load the exported XNNPACK step program and preserve its explicit streaming state.
        val stepModule = loadModule(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, testContext, "rhythm-step.pte")
        val convStateSize = 2 * 1 * 21 + 2 * 2 * channels + 2 * 4 * channels + 2 * 8 * channels
        val convShape = longArrayOf(1, convStateSize.toLong())
        val gruShape = longArrayOf(1, 1, hidden.toLong())
        val memoryShape = longArrayOf(1, 96, hidden.toLong())
        var conv = FloatArray(convStateSize)
        var gru = FloatArray(hidden)
        var memory = FloatArray(96 * hidden)
        var memoryCount = longArrayOf(0)
        fun execuTorchStep(): FloatArray {
            val out = stepModule.forward(
                EValue.from(Tensor.fromBlob(input, longArrayOf(1, 21))),
                EValue.from(Tensor.fromBlob(conv, convShape)),
                EValue.from(Tensor.fromBlob(gru, gruShape)),
                EValue.from(Tensor.fromBlob(memory, memoryShape)),
                EValue.from(Tensor.fromBlob(memoryCount, countShape)),
                EValue.from(Tensor.fromBlob(slow, longArrayOf(1, hidden.toLong()))),
            )
            check(out.size == 5)
            val logits = out[0].toTensor().dataAsFloatArray
            conv = out[1].toTensor().dataAsFloatArray
            gru = out[2].toTensor().dataAsFloatArray
            memory = out[3].toTensor().dataAsFloatArray
            memoryCount = out[4].toTensor().dataAsLongArray
            check(logits.size == 19 && logits.all { it.isFinite() })
            return logits
        }
        val executorchNanos = measure(WARMUP_STEPS, MEASURED_STEPS, ::execuTorchStep)

        val result = """{"model":"${Build.MODEL}","device":"${Build.DEVICE}","api":${Build.VERSION.SDK_INT},"inputs":21,"warmup_steps":$WARMUP_STEPS,"measured_steps":$MEASURED_STEPS,"executorch_xnnpack":{"median_ms":${percentile(executorchNanos, 0.50)},"p90_ms":${percentile(executorchNanos, 0.90)},"max_ms":${executorchNanos.last() / 1e6}},"kotlin_reference":{"median_ms":${percentile(kotlinNanos, 0.50)},"p90_ms":${percentile(kotlinNanos, 0.90)},"max_ms":${kotlinNanos.last() / 1e6}},"daily_encoder_executorch":{"median_ms":${percentile(dailyNanos, 0.50)},"p90_ms":${percentile(dailyNanos, 0.90)}}}"""
        Log.i(TAG, result)
        println("MaPaHybridLatency $result")
        assertTrue("ExecuTorch timing sample must be non-empty", executorchNanos.all { it > 0L })
        assertTrue("Kotlin timing sample must be non-empty", kotlinNanos.all { it > 0L })
    }

    private fun loadModule(directory: File, context: android.content.Context, name: String): Module {
        directory.mkdirs()
        val file = File(directory, name)
        if (!file.isFile) context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return Module.load(file.absolutePath)
    }

    private fun measure(warmups: Int, count: Int, block: () -> FloatArray): LongArray {
        repeat(warmups) { block() }
        return LongArray(count) {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            SystemClock.elapsedRealtimeNanos() - start
        }.also(Arrays::sort)
    }

    private fun percentile(sorted: LongArray, percentile: Double): Double {
        val index = ((sorted.size * percentile).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index] / 1e6
    }

    private companion object {
        const val WARMUP_STEPS = 300
        const val MEASURED_STEPS = 1_000
        const val TAG = "MaPaHybridLatency"
    }
}
