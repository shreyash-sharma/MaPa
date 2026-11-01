package app.mapa.parent.rhythm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mapa.routine.BinObservation
import app.mapa.routine.Bins
import app.mapa.routine.RoutineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app's own ModelStore on a real phone: the Hybrid loads, decides, and survives a snapshot. */
@RunWith(AndroidJUnit4::class)
class HybridOnPhoneTest {
    @Test
    fun appModelStoreRunsTheHybridOnExecuTorch() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = ModelStore.factory(ctx)
        assertTrue("the Hybrid ships in this build", factory.hasHybrid)
        val start = 20_000 * Bins.PER_DAY
        val engine = factory.create(start)
        assertEquals("ExecuTorch (XNNPACK)", ModelStore.runtime)

        // 21 ordinary days: use every hour from 07:00 to 22:00, then a day with no use after 07:30.
        fun count(day: Int, slot: Int) = when {
            day == 21 && slot >= 31 -> 0
            slot in 28 until 88 && slot % 4 == 0 -> 2
            else -> 0
        }
        var unusualAt: Int? = null
        var normalUnusual = 0
        val began = System.nanoTime()
        for (day in 0..21) for (slot in 0 until Bins.PER_DAY) {
            val r = engine.onBin(BinObservation(start + day * Bins.PER_DAY + slot, count(day, slot), false, 80))
            assertNotNull("the Hybrid answers every bin", r.hybrid)
            if (r.decision.state == RoutineState.UNUSUAL_INACTIVITY) {
                if (day == 21) { if (unusualAt == null) unusualAt = slot } else normalUnusual++
            }
        }
        val ms = (System.nanoTime() - began) / 1e6
        val snapshot = engine.snapshot()
        val restored = factory.restore(snapshot)
        val next = restored.onBin(BinObservation(start + 22 * Bins.PER_DAY, 0, false, 80))
        assertNotNull(next.hybrid)
        val result = "runtime=${ModelStore.runtime} days=22 bins=${22 * Bins.PER_DAY} total_ms=${"%.0f".format(ms)} " +
            "normal_day_unusual_bins=$normalUnusual quiet_day_unusual_from=${unusualAt?.let { "%02d:%02d".format(it / 4, it % 4 * 15) }}"
        Log.i("MaPaHybridOnPhone", result)
        println("MaPaHybridOnPhone $result")
        assertNotNull("a whole quiet day must become Unusual", unusualAt)
    }
}
