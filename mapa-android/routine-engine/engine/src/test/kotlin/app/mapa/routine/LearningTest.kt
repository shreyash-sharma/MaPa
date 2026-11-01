package app.mapa.routine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A day where the phone is used every [every] bins between [wakeBin] and 88 (22:00). */
fun dayRecord(day: Int, dow: Int = Math.floorMod(day, 7), wakeBin: Int = 28, every: Int = 3, coverage: Double = 1.0): DayRecord {
    val r = DayRecord(day, dow)
    val unobserved = ((1 - coverage) * Bins.PER_DAY).toInt()
    for (b in 0 until Bins.PER_DAY) {
        r.interactions[b] = when {
            b < unobserved -> -1
            b in wakeBin until 88 && (b - wakeBin) % every == 0 -> 2
            else -> 0
        }
    }
    return r
}

class ProfileTest {
    private val prior = Array(3) { DoubleArray(Bins.PER_DAY) { 0.2 } }

    @Test
    fun priorOnlyWithoutHistory() {
        val p = Profile.priorOnly(prior)
        assertEquals(0.2, p.rateAt(at(0, 9), 0), 1e-12)
        assertEquals(0.0, p.confidence)
    }

    @Test
    fun learnsUsualFirstActivityAndMovesTowardHistory() {
        val days = (0 until 28).map { dayRecord(it, wakeBin = 28) }
        val p = Profile.build(days, prior)
        assertEquals(1.0, p.confidence)
        assertEquals(7 * 60, p.usualFirstActivity(30, 0))
        assertTrue(p.rateAt(at(1, 3), 0) < 0.05, "night")
        assertTrue(p.rateAt(at(1, 12), 0) > 0.2, "day")
    }

    @Test
    fun stepTowardInterpolates() {
        val a = Profile.priorOnly(prior)
        val b = Profile.build((0 until 10).map { dayRecord(it) }, prior)
        val half = a.stepToward(b, 0.5)
        assertEquals(a.distanceTo(b) / 2, a.distanceTo(half), 1e-12)
    }
}

class PersonalHeadTest {
    @Test
    fun identityHeadIsTheBackbone() {
        val h = PersonalHead()
        assertEquals(sigmoid(1.3), h.hazard(0, 1.3f, 0.4f), 1e-6)
    }

    @Test
    fun fitRecoversAKnownCalibration() {
        val rnd = Random(1)
        val n = 80_000
        val z = FloatArray(n) { (rnd.nextDouble() * 6 - 3).toFloat() }
        val r = FloatArray(n) { (0.05 + rnd.nextDouble() * 0.9).toFloat() }
        val k = IntArray(n) { rnd.nextInt(Bins.HORIZON) }
        val truth = doubleArrayOf(0.7, 0.5, -0.3, 0.2, 0.0, 0.4)
        val y = BooleanArray(n) {
            val p = PersonalHead(truth).hazard(k[it], z[it], r[it])
            rnd.nextDouble() < p
        }
        val fit = PersonalHead.fit(PersonalHead.Companion.Examples(z, r, k, y), lambda = 1.0)
        for (i in truth.indices) assertEquals(truth[i], fit.theta[i], 0.12, "theta[$i]")
    }

    @Test
    fun survivalExamplesStopAtFirstActivityAndAtGaps() {
        val d = DayRecord(0, 0)
        d.interactions.fill(0)
        d.interactions[10] = 1
        d.interactions[13] = -1
        for (b in 0 until Bins.PER_DAY) {
            d.logits[b] = FloatArray(Bins.HORIZON); d.rates[b] = FloatArray(Bins.HORIZON) { 0.3f }
        }
        val ex = PersonalHead.examples(mapOf(0 to d))
        // tick 8: k=0 -> bin 9 silent, k=1 -> bin 10 active then stop
        // tick 10: bins 11, 12 silent, bin 13 unobserved stops
        val perTick = ex.k.count { it == 0 }
        assertEquals(Bins.PER_DAY - 1 - 1, perTick, "every tick except the last and the one before the gap")
    }
}

class SilenceCalibratorTest {
    @Test
    fun scoreIsMonotoneAndBlendsPrior() {
        val prior = FloatArray(240) { if (it < 40) 1f else 0f } // prior silences all under 10 nats
        val c0 = SilenceCalibrator(prior, DoubleArray(0))
        assertEquals(0.5, c0.score(5.0), 1e-9)
        assertEquals(1.0, c0.score(12.0), 1e-9)
        val personal = DoubleArray(200) { it * 0.1 } // 0..20 nats
        val c = SilenceCalibrator(prior, personal, priorWeight = 200.0)
        var prev = -1.0
        for (x in 0..60) { val s = c.score(x * 0.5); assertTrue(s >= prev); prev = s }
        assertEquals((100 + 200 * 1.0) / 400, c.score(10.0), 1e-9)
    }

    @Test
    fun weeklyRateCountsPersonalSilencesPerWeek() {
        val prior = FloatArray(240) { if (it < 40) 1f else 0f } // 40 prior silences under 10 nats from 5 days
        val personal = DoubleArray(70) { if (it < 7) 12.0 else 1.0 } // 7 of 70 silences reach 12 nats over 70 days
        val c = SilenceCalibrator(prior, personal, personalDays = 70, priorDays = 5.0, priorDayWeight = 14.0)
        assertEquals(7.0 * 7 / (70 + 14), c.weeklyRate(11.0), 1e-9, "prior never reaches 11 nats")
        assertEquals(0.0, c.weeklyRate(13.0), 1e-9)
        val cold = SilenceCalibrator(prior, DoubleArray(0), 0, 5.0)
        assertEquals(7 * 0.5 * 40 / 5.0, cold.weeklyRate(5.0), 1e-9, "half the prior silences exceed 5 nats, 8 per day")
    }

    @Test
    fun replaysOnlyFullySeenSilences() {
        val d = DayRecord(0, 0)
        d.interactions.fill(0)
        d.interactions[5] = 1; d.interactions[9] = 1; d.interactions[20] = 1
        for (b in 0 until Bins.PER_DAY) { d.logits[b] = FloatArray(Bins.HORIZON) { -2f }; d.rates[b] = FloatArray(Bins.HORIZON) { 0.2f } }
        val s = SilenceCalibrator.stretchSurprises(mapOf(0 to d), PersonalHead())
        val per = -kotlin.math.ln(1 - sigmoid(-2.0))
        assertEquals(2, s.size, "0..5 starts unseen; 5..9 and 9..20 complete; 20..end never ends")
        assertEquals(3 * per, s[0], 1e-6)
        assertEquals(10 * per, s[1], 1e-6)
    }
}

class GuardsTest {
    @Test
    fun quarantineFastLaneFlagsCoverageAndLateTags() {
        val ledger = DayLedger(GuardConfig())
        for (d in 0 until 30) ledger.add(dayRecord(d, coverage = if (d == 12) 0.5 else 1.0).also { it.flaggedUnusual = d == 14 })
        val learning = ledger.eligible(today = 30, learning = true)
        assertEquals(28, learning.size, "days 0..29 minus day 12 (coverage) and day 14 (flagged)")
        val steady = ledger.eligible(today = 30, learning = false)
        assertEquals((0..23).count { it != 12 && it != 14 }, steady.size)
        assertTrue(steady.keys.max() == 23, "a day waits 7 days")
        ledger.tags += LifeTag(at(20, 9), at(22, 9), LifeTagKind.UNWELL)
        val tagged = ledger.eligible(today = 30, learning = false)
        assertTrue(20 !in tagged && 21 !in tagged && 22 !in tagged, "a tag entered later still removes the days")
    }

    @Test
    fun anchorHoldsBackLargeSteps() {
        val prior = Array(3) { DoubleArray(Bins.PER_DAY) { 0.2 } }
        val g = AnchorGuard(GuardConfig(maxWeeklyProfileStep = 0.01, maxAnchorDrift = 0.03))
        val base = Profile.build((0 until 28).map { dayRecord(it, wakeBin = 28) }, prior)
        val shifted = Profile.build((0 until 28).map { dayRecord(it, wakeBin = 40, every = 6) }, prior)
        g.setAnchor(base, PersonalHead())
        val r = g.limit(base, PersonalHead(), shifted, PersonalHead(), emptyList())
        assertTrue(r.heldBack)
        assertEquals(0.01, base.distanceTo(r.profile), 1e-6)
        var p = r.profile
        repeat(10) { p = g.limit(p, PersonalHead(), shifted, PersonalHead(), emptyList()).profile }
        assertTrue(p.distanceTo(base) <= 0.03 + 1e-6, "never drifts past the anchor radius")
    }

    @Test
    fun asksAfterThreeWeeksOfLaterMornings() {
        val prior = Array(3) { DoubleArray(Bins.PER_DAY) { 0.2 } }
        val baseline = Profile.build((0 until 28).map { dayRecord(it, wakeBin = 28) }, prior) // 07:00
        val det = NewRoutineDetector(GuardConfig())
        fun week(start: Int, wake: Int) = (start until start + 7).map { dayRecord(it, wakeBin = wake) }
        det.weeklyCheck(42, week(35, 28), baseline, 0)
        assertNull(det.pending)
        det.weeklyCheck(49, week(42, 32), baseline, 0) // 08:00
        det.weeklyCheck(56, week(49, 32), baseline, 0)
        assertNull(det.pending)
        det.weeklyCheck(63, week(56, 33), baseline, 0)
        val q = assertNotNull(det.pending)
        assertTrue(q.later && q.shiftMinutes in 60..75 && q.sinceDay == 42)
        assertEquals(42, det.answer(63, yes = true))
        assertNull(det.pending)
    }

    @Test
    fun noKeepsBaselineAndSnoozes() {
        val prior = Array(3) { DoubleArray(Bins.PER_DAY) { 0.2 } }
        val baseline = Profile.build((0 until 28).map { dayRecord(it, wakeBin = 28) }, prior)
        val det = NewRoutineDetector(GuardConfig(snoozeWeeksAfterNo = 3))
        var day = 42
        repeat(3) { det.weeklyCheck(day, (day - 7 until day).map { dayRecord(it, wakeBin = 34) }, baseline, 0); day += 7 }
        assertNotNull(det.pending)
        assertNull(det.answer(day, yes = false))
        repeat(3) { det.weeklyCheck(day, (day - 7 until day).map { dayRecord(it, wakeBin = 34) }, baseline, 0); day += 7 }
        assertNull(det.pending, "snoozed for three weeks")
        det.weeklyCheck(day, (day - 7 until day).map { dayRecord(it, wakeBin = 34) }, baseline, 0)
        assertNotNull(det.pending)
    }

    @Test
    fun trendReportStaysAboutPhonePatterns() {
        val tr = TrendReporter(GuardConfig())
        tr.setBaseline((0 until 28).map { dayRecord(it, wakeBin = 28) })
        val r = assertNotNull(tr.weekly(40, (33 until 40).map { dayRecord(it, wakeBin = 32, every = 6) }))
        assertEquals(60, r.firstUseShiftMinutes)
        assertTrue(r.notable)
        val text = r.lines("Mom").joinToString(" ")
        assertTrue("later" in text && "lower" in text)
        for (banned in listOf("ill", "sick", "health", "safe", "fall", "emergency")) {
            assertTrue(Regex("\\b$banned\\b", RegexOption.IGNORE_CASE).find(text) == null, banned)
        }
    }
}

class SchedulerTest {
    private fun obs(t: Int, charging: Boolean = true, battery: Int = 80) = BinObservation(t, 0, charging, battery)

    @Test
    fun phasesAndNightlyThenWeekly() {
        // day 0 is a Monday
        val s = TrainingScheduler(ScheduleConfig(), installDay = 0, dowOffset = 0)
        assertEquals(Phase.LEARNING, s.phase(13))
        assertEquals(Phase.CALIBRATING, s.phase(14))
        assertEquals(Phase.STEADY, s.phase(30))
        assertNull(s.due(obs(at(1, 12)), null, false), "outside the night window")
        assertEquals(TrainKind.FULL, s.due(obs(at(1, 2)), null, false))
        assertNull(s.due(obs(at(1, 3)), null, false), "once a night")
        assertEquals(TrainKind.REFRESH, s.due(obs(at(31, 2)), null, false))
        assertEquals(TrainKind.FULL, s.due(obs(at(35, 2)), null, false), "Monday night is the weekly retrain")
        assertEquals(TrainKind.ADOPT_NEW_ROUTINE, s.due(obs(at(36, 2)), null, true))
    }

    @Test
    fun needsChargeAndIdleUnlessNightsAreMissed() {
        val s = TrainingScheduler(ScheduleConfig(), installDay = 0, dowOffset = 0)
        assertNull(s.due(obs(at(2, 1), charging = false), null, false))
        assertNull(s.due(obs(at(2, 1, 15)), at(2, 1), false), "parent is using the phone")
        assertNull(s.due(obs(at(3, 1), charging = false), null, false))
        assertNull(s.due(obs(at(4, 1), charging = false, battery = 50), null, false))
        assertEquals(TrainKind.FULL, s.due(obs(at(4, 1, 15), charging = false, battery = 70), null, false), "fallback after two missed nights")
    }
}

/** Light check that weights equal-distance helpers behave. */
class HeadDistanceTest {
    @Test
    fun stepTowardHalvesDistance() {
        val a = PersonalHead()
        val b = PersonalHead(doubleArrayOf(1.5, 0.3, 0.2, 0.0, 0.0, 0.0))
        val ticks = listOf(0.5f to 0.3f, -1f to 0.1f, 2f to 0.6f)
        assertTrue(abs(a.distanceTo(a.stepToward(b, 0.5), ticks) - a.distanceTo(b, ticks) / 2) < 0.02)
    }
}
