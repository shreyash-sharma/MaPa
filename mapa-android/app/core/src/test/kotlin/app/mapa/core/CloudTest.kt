package app.mapa.core

import app.mapa.core.cloud.AskFacts
import app.mapa.core.cloud.AskMapa
import app.mapa.core.cloud.WeekDay
import app.mapa.core.cloud.ChatClient
import app.mapa.core.cloud.CheckIn
import app.mapa.core.cloud.CloudProvider
import app.mapa.core.cloud.CloudProviders
import app.mapa.core.cloud.CloudProxy
import app.mapa.core.cloud.CloudTextModel
import app.mapa.core.cloud.MiniJson
import app.mapa.core.language.Lang
import app.mapa.core.language.Note
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.NoteWriter
import app.mapa.core.language.Person
import app.mapa.core.language.Role
import app.mapa.core.language.TextModel
import app.mapa.routine.LifeTagKind
import app.mapa.routine.RoutineState
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, ist)

    private fun facts(name: String = "Asha") = NoteFacts(
        person = Person(name, Role.MOTHER), lang = Lang.EN, weekday = 2, nowMinute = 10 * 60,
        state = RoutineState.ACTIVE, firstActivityTodayMinute = 7 * 60 + 40, usualFirstMinute = 7 * 60 + 30,
        lastInteractionMinute = 9 * 60 + 50, lastInteractionToday = true, activeBinsToday = 6, usualActiveBinsSoFar = 6.0,
        battery = 80, charging = false, learning = false,
    )

    @Test
    fun jsonRoundTrip() {
        val j = MiniJson.parse("""{"a":[1,2.5,{"b":"x\n\"yé"}],"c":null,"d":true}""")
        assertEquals("x\n\"yé", MiniJson.path(j, "a", 2, "b"))
        assertEquals(true, MiniJson.path(j, "d"))
        assertNull(MiniJson.path(j, "c"))
        assertEquals("\"a\\\"b\\n\"", MiniJson.quote("a\"b\n"))
    }

    @Test
    fun cloudPromptNeverCarriesTheName() {
        var seen = ""
        val model = object : TextModel {
            override val id = "fake-cloud"
            override val remote = true
            override fun generate(prompt: String): String? { seen = prompt; return "[NAME] picked up the phone at 7:40 AM." }
        }
        val note = NoteWriter(model).write(facts("Asha"))
        assertFalse(seen.contains("Asha"), "prompt leaked the name: $seen")
        assertTrue(seen.contains("[NAME]"))
        assertEquals("fake-cloud", note.source)
        assertTrue(note.text.startsWith("Asha "), note.text)
    }

    @Test
    fun cloudNoteStillFailsTheClaimsGuard() {
        val bad = object : TextModel {
            override val id = "fake-cloud"
            override val remote = true
            override fun generate(prompt: String) = "[NAME] is fine and safe today."
        }
        assertEquals(Note.TEMPLATE, NoteWriter(bad).write(facts()).source)
    }

    @Test
    fun checkInAcceptsAReasonableTagAndRejectsTheRest() {
        val ok = CheckIn.parse("""{"kind":"travelling","start":"2026-10-30T08:00","end":"2026-11-02T20:00"}""", now)
        assertNotNull(ok)
        assertEquals(LifeTagKind.TRAVELLING, ok.kind)
        assertEquals(ist.let { ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, it) }.toInstant().toEpochMilli(), ok.startMs)
        assertEquals(LifeTagKind.OTHER, CheckIn.parse("""{"kind":"away_from_phone","start":"2026-10-28T09:00","end":"2026-10-28T13:00"}""", now)?.kind)
        assertNull(CheckIn.parse("""{"kind":"none","start":"2026-10-28T09:00","end":"2026-10-28T13:00"}""", now))
        assertNull(CheckIn.parse("""{"kind":"travelling","start":"2026-10-30T08:00","end":"2026-12-02T20:00"}""", now), "longer than 14 days")
        assertNull(CheckIn.parse("""{"kind":"travelling","start":"2026-10-20T08:00","end":"2026-10-22T20:00"}""", now), "too far in the past")
        assertNull(CheckIn.parse("""{"kind":"travelling","start":"2026-10-30T20:00","end":"2026-10-30T08:00"}""", now), "end before start")
        assertNull(CheckIn.parse("not json", now))
        assertNotNull(CheckIn.parse("```json\n{\"kind\":\"guests\",\"start\":\"2026-10-28T10:00\",\"end\":\"2026-10-29T10:00\"}\n```", now))
    }

    @Test
    fun checkInPromptHasOnlyTheTextAndTheTime() {
        val p = CheckIn.prompt("she went to the temple and left her phone", now)
        assertTrue(p.contains("temple"))
        assertTrue(p.contains("2026-10-28T10:00"))
        assertFalse(p.contains("Asha"))
    }

    @Test
    fun chatClientSpeaksTheOpenAiProtocolAndOnlyToAllowedHosts() {
        var auth = ""
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            body = String(ex.requestBody.readBytes())
            val out = """{"choices":[{"message":{"content":"{\"kind\":\"guests\",\"start\":\"2026-10-28T10:00\",\"end\":\"2026-10-29T10:00\"}"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val local = providerAt("http://127.0.0.1:${server.address.port}/v1", "meta-models/muse-spark-open-1026", "secret-key")
            val reply = ChatClient(local).complete(CheckIn.prompt("guests are coming", now), CheckIn.SCHEMA)
            assertNotNull(reply)
            assertEquals("Bearer secret-key", auth)
            assertTrue(body.contains(""""model":"meta-models/muse-spark-open-1026""""))
            assertTrue(body.contains("json_schema"))
            assertEquals(LifeTagKind.GUESTS, CheckIn.parse(reply, now)?.kind)
        } finally {
            server.stop(0)
        }
        assertNull(ChatClient(providerAt("https://evil.example.com/v1", "m", "k")).complete("hi"))
        assertNull(ChatClient(CloudProviders.museSpark("")).complete("hi"), "no key means no call")
        assertEquals("meta-models/muse-spark-open-1026", CloudProviders.baseten("k").model)
        assertEquals("muse-spark-1.3", CloudProviders.museSpark("k").model)
        assertTrue(CloudTextModel(CloudProviders.baseten("k")).remote)
    }

    @Test
    fun proxyCarriesNoKeyAndSendsTheFamilyToken() {
        var auth = ""
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/demo-mapa/asia-south1/chat") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            body = String(ex.requestBody.readBytes())
            val out = """{"result":{"content":"A calm morning."}}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val proxy = CloudProxy("http://127.0.0.1:${server.address.port}/demo-mapa/asia-south1/chat", emulator = true) { "id-token" }
            val muse = CloudProviders.viaProxy(proxy).first()
            assertTrue(muse.usable, "a proxy needs no key in the app")
            assertEquals("A calm morning.", ChatClient(muse).complete("note", CheckIn.SCHEMA))
            assertEquals("Bearer id-token", auth)
            assertTrue(body.contains(""""provider":"muse-spark-1.3""""))
            assertTrue(body.contains(""""schema":"""))
            val signedOut = ChatClient(CloudProviders.museSpark("", CloudProxy(proxy.url, emulator = true) { null }))
            assertNull(signedOut.complete("note"))
            assertEquals("not signed in", signedOut.lastError)
        } finally {
            server.stop(0)
        }
        assertNull(ChatClient(CloudProviders.museSpark("", CloudProxy("https://evil.example.com/chat") { "t" })).complete("hi"))
    }

    private fun askFacts(stale: Boolean = false) = AskFacts(
        lang = Lang.EN, nowMinute = 10 * 60, stale = stale, stateLabel = "Quiet", usualFirstMinute = 7 * 60 + 30,
        days = listOf(
            WeekDay("Mon 26 Oct", 7 * 60 + 40, 30, 96, emptyList()),
            WeekDay("Tue 27 Oct", 8 * 60 + 5, 22, 96, listOf("travelling")),
            WeekDay("Wed 28 Oct", null, 0, 40, emptyList()),
        ),
        trendLines = listOf("First phone use this week was about 30 minutes later than usual."), routineQuestion = null, battery = 80,
    )

    private fun fake(reply: String?, seen: MutableList<String> = mutableListOf()) = object : TextModel {
        override val id = "fake-cloud"
        override val remote = true
        override fun generate(prompt: String): String? { seen += prompt; return reply }
    }

    @Test
    fun askMapaSendsNoNameAndKeepsAGroundedAnswer() {
        val seen = mutableListOf<String>()
        val a = AskMapa.answer("How has Asha been this week?", "Asha", askFacts(), listOf(fake("[NAME] first used the phone around 7:40 AM on Monday and 8:05 AM on Tuesday.", seen)))
        assertFalse(seen.single().contains("Asha"), seen.single())
        assertTrue(seen.single().contains("[NAME]"))
        assertEquals("fake-cloud", a.source)
        assertTrue(a.text.startsWith("Asha first used"), a.text)
    }

    @Test
    fun askMapaRejectsInventedTimesAndHealthTalk() {
        val bad = listOf("[NAME] first used the phone at 6:10 AM.", "[NAME] seems tired and may be unwell.", "[NAME] is fine this week.")
        bad.forEach { r ->
            val a = AskMapa.answer("How was this week?", "Asha", askFacts(), listOf(fake(r)))
            assertEquals(Note.TEMPLATE, a.source, r)
            assertTrue(a.text.contains("8:05 AM") || a.text.contains("7:40 AM") || a.text.contains("Phone use showed"), a.text)
        }
    }

    @Test
    fun askMapaAnswersJudgementQuestionsLocally() {
        val seen = mutableListOf<String>()
        val a = AskMapa.answer("Is Mom okay?", "Asha", askFacts(), listOf(fake("Yes", seen)))
        assertTrue(seen.isEmpty(), "a judgement question must not reach the model")
        assertTrue(a.text.startsWith("I can only describe phone activity patterns"), a.text)
        assertEquals(Note.TEMPLATE, a.source)
        assertTrue(AskMapa.answer("Is she unwell?", "Asha", askFacts(), emptyList()).text.contains("call"))
    }

    @Test
    fun askMapaNeverReadsAsCurrentWhenDataIsStale() {
        val a = AskMapa.answer("How has Asha been this week?", "Asha", askFacts(stale = true), listOf(fake("[NAME] first used the phone around 7:40 AM on Monday.")))
        assertTrue(a.text.endsWith("so this may not describe now."), a.text)
        val none = AskMapa.answer("How has Asha been this week?", "Asha", askFacts(stale = true), emptyList())
        assertTrue(none.text.endsWith("so this may not describe now."), none.text)
    }

    private fun providerAt(url: String, model: String, key: String): CloudProvider =
        CloudProvider::class.java.declaredConstructors.first { it.parameterCount == 5 }.apply { isAccessible = true }
            .newInstance("test", url, model, key, null) as CloudProvider

    @Test fun shortCallNameIsMaskedAsAWholeWordOnly() {
        assertEquals("Did [NAME] use MaPa many times?", AskMapa.maskName("Did Ma use MaPa many times?", "Ma"))
        assertEquals("[NAME] ने आज फ़ोन उठाया?", AskMapa.maskName("माँ ने आज फ़ोन उठाया?", "माँ"))
    }
}
