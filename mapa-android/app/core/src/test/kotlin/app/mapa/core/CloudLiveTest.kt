package app.mapa.core

import app.mapa.core.cloud.AiBudget
import app.mapa.core.cloud.AiKind
import app.mapa.core.cloud.AskFacts
import app.mapa.core.cloud.AskMapa
import app.mapa.core.cloud.ChatClient
import app.mapa.core.cloud.CheckIn
import app.mapa.core.cloud.CloudProviders
import app.mapa.core.cloud.CloudTextModel
import app.mapa.core.cloud.MemoryCounters
import app.mapa.core.cloud.WeekDay
import app.mapa.core.language.Lang
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.NoteWriter
import app.mapa.core.language.Person
import app.mapa.core.language.Role
import app.mapa.routine.RoutineState
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real calls to the Meta open model on Baseten, the same code the family app runs. Only with
 * `-PliveAi` (key from the git-ignored .env); otherwise it does nothing. Writes a report with the
 * answers, the call count, the tokens and the estimated spend. Never prints the key.
 */
class CloudLiveTest {
    private val key = System.getProperty("mapa.basetenKey").orEmpty()
    private val budget = AiBudget(MemoryCounters(), { "test" }, maxCalls = 60, maxUsd = 1.0)
    private val provider = CloudProviders.baseten(key)

    private fun facts(lang: Lang) = NoteFacts(
        person = Person("Asha", Role.MOTHER), lang = lang, weekday = 2, nowMinute = 18 * 60,
        state = RoutineState.ACTIVE, firstActivityTodayMinute = 7 * 60 + 40, usualFirstMinute = 7 * 60 + 30,
        lastInteractionMinute = 17 * 60 + 50, lastInteractionToday = true, activeBinsToday = 22, usualActiveBinsSoFar = 20.0,
        battery = 64, charging = false, learning = false,
    )

    private fun askFacts() = AskFacts(
        lang = Lang.EN, nowMinute = 18 * 60, stale = false, stateLabel = "Active", usualFirstMinute = 7 * 60 + 30,
        days = listOf(
            WeekDay("Mon 26 Oct", 7 * 60 + 40, 30, 96, emptyList()),
            WeekDay("Tue 27 Oct", 8 * 60 + 5, 22, 96, listOf("travelling")),
            WeekDay("Wed 28 Oct", 7 * 60 + 35, 28, 96, emptyList()),
            WeekDay("Thu 29 Oct", 7 * 60 + 25, 31, 96, emptyList()),
            WeekDay("Fri 30 Oct", 7 * 60 + 50, 26, 90, emptyList()),
        ),
        trendLines = listOf("First phone use this week was about 10 minutes later than usual."), routineQuestion = null, battery = 64,
    )

    @Test
    fun liveMetaOpenModelOnBaseten() {
        if (key.isBlank()) return
        val out = StringBuilder("# Live Baseten check (muse-spark-open-1026)\n\n")

        out.append("## Daily notes\n")
        for (lang in listOf(Lang.EN, Lang.HI)) {
            val note = NoteWriter(listOf(CloudTextModel(provider, ChatClient(provider, budget = budget, kind = AiKind.NOTE)))).write(facts(lang))
            out.append("- ${lang.code} [${note.source}]: ${note.text}\n")
        }

        out.append("\n## Daily note in Marathi and Tamil (checked by back-translation)\n")
        val en = NoteWriter(listOf(CloudTextModel(provider, ChatClient(provider, budget = budget, kind = AiKind.NOTE)))).write(facts(Lang.EN))
        val tr = ChatClient(provider, budget = budget, kind = AiKind.NOTE)
        for (l in app.mapa.core.cloud.NoteLang.entries) {
            val t = app.mapa.core.cloud.NoteTranslator.translate(en.text, facts(Lang.EN), l) { tr.complete(it) }
            out.append("- ${l.code}: ${t ?: "FAILED the check, English is shown (${tr.lastError ?: "rejected"})"}\n")
        }

        out.append("\n## Life tags from the family's words\n")
        val now = ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val tagClient = ChatClient(provider, budget = budget, kind = AiKind.TAG)
        listOf(
            "She is at my sister's place in Pune till Monday",
            "Guests are staying with them this weekend",
            "Diwali at home, the whole family is here",
            "Papa has a cold, he is resting today",
            "They went to the temple trip, back on Sunday night",
            "Mummy left her phone at home today",
            "She is travelling to Varanasi by train tomorrow",
            "My cousins are visiting for three days",
            "Ma ki tabiyat theek nahi hai aaj",
            "Nothing special, just a normal day",
        ).forEach { s ->
            val reply = tagClient.complete(CheckIn.prompt(s, now), CheckIn.SCHEMA)
            val tag = reply?.let { CheckIn.parse(it, now) }
            out.append("- \"$s\" -> ${tag?.let { "${it.kind} ${java.time.Instant.ofEpochMilli(it.startMs)}..${java.time.Instant.ofEpochMilli(it.endMs)}" } ?: "no tag (${tagClient.lastError ?: "rejected"})"}\n")
        }

        out.append("\n## Ask MaPa (health questions must be answered on the phone, with no call)\n")
        val askModels = listOf(CloudTextModel(provider, ChatClient(provider, budget = budget, kind = AiKind.ASK)))
        listOf(
            "How has Asha been this week?",
            "Was today different from usual?",
            "When does she usually start her day?",
            "Which day was the quietest?",
            "Did anything change this week?",
            "Is she sick?",
            "Is my mother safe?",
            "Did she take her medicine?",
            "Is she sleeping well?",
            "Has she fallen?",
        ).forEach { q ->
            val before = budget.calls
            val a = AskMapa.answer(q, "Asha", askFacts(), askModels)
            val called = budget.calls > before
            out.append("- \"$q\" [${a.source}${if (called) ", 1 call" else ", no call"}]: ${a.text}\n")
        }

        out.append("\n## Spend\n${budget.line()}\n")
        File(System.getProperty("java.io.tmpdir"), "mapa-live-ai.md").writeText(out.toString())
        println(out)
        assertTrue(budget.calls > 0, "at least one real call")
    }
}
