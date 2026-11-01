package app.mapa.routine

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class NightOneLearningTest {
    @Test
    fun unwellFirstDayCannotEnterNightOneLearning() {
        val engine = RoutineEngine(EngineConfig(), RhythmBackbones.profileOnly(),
            Array(3) { DoubleArray(96) { .2 } }, FloatArray(193) { 1f }, 0, 0)
        engine.addLifeTag(LifeTag(0, 96, LifeTagKind.UNWELL))
        for (t in 0..96) engine.onBin(BinObservation(t, if (t % 3 == 0) 1 else 0, true, 90))
        assertContentEquals(PersonalHead.IDENTITY, engine.head.theta)
    }

    @Test
    fun flaggedDayIsExcludedEvenInLearningFastLane() {
        val ledger = DayLedger(GuardConfig())
        val day = dayRecord(0)
        day.flaggedUnusual = true
        ledger.add(day)
        assertTrue(ledger.eligible(today = 1, learning = true).isEmpty())
    }

    @Test
    fun firstEligibleNightFitsPersonalHeadWithoutThreeDayWait() {
        val engine = RoutineEngine(EngineConfig(), RhythmBackbones.profileOnly(),
            Array(3) { DoubleArray(96) { .2 } }, FloatArray(193) { 1f }, 0, 0)
        for (t in 0..96) {
            val count = if (t % 96 in 28..87 && t % 3 == 0) 1 else 0
            engine.onBin(BinObservation(t, count, true, 90))
        }
        assertTrue(engine.head.theta.indices.any { kotlin.math.abs(engine.head.theta[it]-PersonalHead.IDENTITY[it]) > 1e-8 },
            "personal head must learn from the first eligible completed day")
    }
}
