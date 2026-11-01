package app.mapa.core

import app.mapa.core.language.ClaimsGuard
import app.mapa.core.language.Clock
import app.mapa.core.language.Lang
import app.mapa.core.language.Note
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.NotePrompt
import app.mapa.core.language.NoteWriter
import app.mapa.core.language.Person
import app.mapa.core.language.Phrases
import app.mapa.core.language.Role
import app.mapa.core.language.TextModel
import app.mapa.routine.Bins
import app.mapa.routine.Decider
import app.mapa.routine.NewRoutineQuestion
import app.mapa.routine.Reason
import app.mapa.routine.RoutineState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageTest {
    private val mom = Person("Mom", Role.MOTHER)
    private val dad = Person("Dad", Role.FATHER)
    private val monday = 20_381 // a Monday
    private fun t(day: Int, h: Int, m: Int) = day * Bins.PER_DAY + (h * 60 + m) / 15

    private fun facts(lang: Lang = Lang.EN, first: Int? = 9 * 60 + 5, learning: Boolean = false) = NoteFacts(
        person = mom, lang = lang, weekday = 0, nowMinute = 11 * 60 + 20, state = RoutineState.ACTIVE,
        firstActivityTodayMinute = first, usualFirstMinute = 7 * 60 + 40, lastInteractionMinute = 11 * 60 + 2,
        lastInteractionToday = true, activeBinsToday = 6, usualActiveBinsSoFar = 12.0, battery = 54, charging = false,
        learning = learning,
    )

    @Test
    fun `clock reads like a person`() {
        assertEquals("7:40 AM", Clock.time(7 * 60 + 40, Lang.EN))
        assertEquals("12:05 PM", Clock.time(12 * 60 + 5, Lang.EN))
        assertEquals("सुबह 7:40", Clock.time(7 * 60 + 40, Lang.HI))
        assertEquals("3 hours", Clock.duration(185, Lang.EN))
        assertEquals("about an hour", Clock.roughShift(60, Lang.EN))
    }

    @Test
    fun `unusual wording matches the brief`() {
        val r = Reason.LongerThanUsual(t(monday, 7, 5), null, 8 * 60 + 30, 0.97, null, Decider.MODEL, false)
        assertEquals(
            "Mom hasn't used her phone since 7:00 AM, and she's usually active by 8:30 AM on weekdays. " +
                "We're 97% confident this is unusual for her. This may be nothing; you might want to call.",
            Phrases(Lang.EN).detail(r, mom, t(monday, 9, 10)),
        )
        val rare = r.copy(recurrenceWeeks = 6.2)
        assertTrue(Phrases(Lang.EN).detail(rare, mom, t(monday, 9, 10)).contains("A quiet stretch like this happens about once in 6 weeks for her."))
        val floor = r.copy(decidedBy = Decider.RULES, floorRule = true)
        assertFalse(Phrases(Lang.EN).detail(floor, mom, t(monday, 9, 10)).contains("confident"))
    }

    @Test
    fun `stale wording never sounds like an all-clear`() {
        val r = Reason.NoFreshData(t(monday, 8, 0), 6, false)
        assertEquals(
            "We haven't heard from Dad's phone for 3 hours. Its battery was at 6%, so it has probably switched off.",
            Phrases(Lang.EN).detail(r, dad, t(monday, 11, 0)),
        )
    }

    @Test
    fun `forecast line appears once the usual time has passed`() {
        val ph = Phrases(Lang.EN)
        assertEquals(
            "Mom usually picks up her phone by 7:40 AM. It's 9:00 AM.",
            ph.forecast(mom, t(monday, 9, 10), 7 * 60 + 40, usedToday = false, expectedByT = null),
        )
        assertEquals(null, ph.forecast(mom, t(monday, 7, 0), 7 * 60 + 40, usedToday = false, expectedByT = null))
    }

    @Test
    fun `template notes pass the claims guard in both languages`() {
        for (lang in Lang.entries) for (first in listOf(null, 6 * 60, 7 * 60 + 40, 9 * 60 + 5)) for (learning in listOf(true, false)) {
            val f = facts(lang, first, learning)
            val note = Phrases(lang).dailyNote(f)
            val v = ClaimsGuard.check(note, f)
            assertTrue(v.ok, "$lang $first $learning: $note -> ${v.problems}")
        }
        assertEquals(
            "Mom started later than usual today and has been active since 9:05 AM. Quieter than a usual Monday so far; last used at 11:02 AM.",
            Phrases(Lang.EN).dailyNote(facts()),
        )
    }

    @Test
    fun `claims guard blocks health, all-clear and invented facts`() {
        val f = facts()
        assertFalse(ClaimsGuard.check("Mom is fine and has been active since 9:05 AM.", f).ok)
        assertFalse(ClaimsGuard.check("Mom may have fallen; no phone use since 9:05 AM.", f).ok)
        assertFalse(ClaimsGuard.check("Mom was probably sleeping in until 9:05 AM.", f).ok)
        assertFalse(ClaimsGuard.check("Mom started at 8:15 AM today.", f).ok) // time not in facts
        assertFalse(ClaimsGuard.check("Her battery is at 80%.", f).ok) // percent not in facts
        assertFalse(ClaimsGuard.check("माँ ठीक हैं।", f.copy(lang = Lang.HI)).ok)
        assertTrue(ClaimsGuard.check("Mom got going later than usual today, first picking up her phone at 9:05 AM.", f).ok)
    }

    @Test
    fun `note writer falls back to the template whenever the model misbehaves`() {
        val f = facts()
        val good = "Mom got going later than usual, first picking up her phone at 9:05 AM, and last used it at 11:02 AM."
        fun model(out: () -> String?) = object : TextModel {
            override val id = "test-model"
            override fun generate(prompt: String) = out()
        }
        assertEquals("test-model", NoteWriter(model { good }).write(f).source)
        assertEquals(good, NoteWriter(model { "\"$good\"" }).write(f).text)
        assertEquals(Note.TEMPLATE, NoteWriter(model { "Mom is safe and well." }).write(f).source)
        assertEquals(Note.TEMPLATE, NoteWriter(model { null }).write(f).source)
        assertEquals(Note.TEMPLATE, NoteWriter(model { error("model crashed") }).write(f).source)
        assertEquals(Note.TEMPLATE, NoteWriter(null as TextModel?).write(f).source)
        // A rejected first model falls through to the next one.
        assertEquals("test-model", NoteWriter(listOf(model { "Mom is fine." }, model { good })).write(f).source)
    }

    @Test
    fun `prompt carries only the structured facts`() {
        val p = NotePrompt.build(facts())
        assertTrue(p.contains("First phone use today: 9:05 AM"))
        assertTrue(p.contains("Usual first phone use on this kind of day: 7:40 AM"))
        assertFalse(p.contains("learning", ignoreCase = false))
    }

    @Test
    fun `hindi wording agrees with the parent`() {
        val q = NewRoutineQuestion(shiftMinutes = 60, weeks = 3, sinceDay = monday)
        assertEquals("Dad's mornings have started about an hour later for 3 weeks. Is this his new routine?", Phrases(Lang.EN).routineQuestion(dad, q))
        assertTrue(Phrases(Lang.HI).forecast(mom, t(monday, 9, 10), 7 * 60 + 40, false, null)!!.contains("लेती हैं"))
        assertTrue(Phrases(Lang.HI).forecast(dad, t(monday, 9, 10), 7 * 60 + 40, false, null)!!.contains("लेते हैं"))
    }
}
