package app.mapa.core.cloud

import app.mapa.core.language.TextModel
import java.net.HttpURLConnection
import java.net.URI

/**
 * A cloud model reached through an OpenAI-compatible chat endpoint. Only the two Meta hosts below are
 * allowed in a shipped build. Only the family app uses this; the parent's phone never calls a cloud model.
 * A shipped build has no key: it goes through [proxy], the MaPa server function that holds the keys.
 */
class CloudProvider internal constructor(
    val id: String,
    val baseUrl: String,
    val model: String,
    internal val apiKey: String,
    internal val proxy: CloudProxy? = null,
) {
    val usable: Boolean get() = apiKey.isNotBlank() || proxy != null
}

/**
 * The `chat` Cloud Function (backend/functions). [idToken] returns the signed-in family member's Firebase
 * ID token; the function refuses anonymous (parent) accounts. [emulator] allows the local Emulator Suite host.
 */
class CloudProxy(val url: String, val emulator: Boolean = false, val idToken: () -> String?)

object CloudProviders {
    /** Meta Model API. Never the "-contributor" model: it lets Meta train on prompts. */
    fun museSpark(key: String, proxy: CloudProxy? = null) =
        CloudProvider("muse-spark-1.3", "https://api.meta.ai/v1", "muse-spark-1.3", key, proxy)

    /** Meta open-weights model served by Baseten (hackathon model name, exactly as issued). */
    fun baseten(key: String, proxy: CloudProxy? = null) =
        CloudProvider("muse-spark-open-1026", "https://inference.baseten.co/v1", "meta-models/muse-spark-open-1026", key, proxy)

    /** Both models through the server function: no key in the app. */
    fun viaProxy(proxy: CloudProxy) = listOf(museSpark("", proxy), baseten("", proxy))

    val ALLOWED_HOSTS = setOf("api.meta.ai", "inference.baseten.co")
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    internal fun proxyHostAllowed(proxy: CloudProxy): Boolean {
        val url = URI.create(proxy.url).toURL()
        return (url.protocol == "https" && url.host.endsWith(".cloudfunctions.net")) || (proxy.emulator || url.host in LOCAL_HOSTS)
    }
}

/** [budget] and [kind] cap the spend: no call goes out once the budget says stop, and every attempt is counted. */
class ChatClient(
    private val p: CloudProvider,
    private val timeoutMs: Int = 20_000,
    private val budget: AiBudget? = null,
    private val kind: AiKind = AiKind.ASK,
) {
    /** Last failure, for the "Under the hood" screen. Never contains prompt text. */
    @Volatile var lastError: String? = null
        private set

    /**
     * One user message in, one text out. [jsonSchema] asks the model for guaranteed JSON of that shape.
     * Returns null on any failure; callers fall back to the template note.
     */
    fun complete(prompt: String, jsonSchema: String? = null, maxTokens: Int = kind.maxTokens): String? {
        if (!p.usable) { lastError = "no key"; return null }
        budget?.refusal(kind)?.let { lastError = it; return null }
        usageIn = 0; usageOut = 0
        return try {
            val content = if (p.proxy != null) viaProxy(p.proxy, prompt, jsonSchema, maxTokens) else direct(prompt, jsonSchema, maxTokens)
            if (content.isNullOrBlank()) { if (lastError == null) lastError = "empty answer"; null } else { lastError = null; content }
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            null
        } finally {
            budget?.record(kind, usageIn, usageOut)
        }
    }

    // Token counts from the provider's "usage" field, for the budget.
    private var usageIn = 0L
    private var usageOut = 0L

    private fun direct(prompt: String, jsonSchema: String?, maxTokens: Int): String? {
        val url = p.baseUrl.trimEnd('/') + "/chat/completions"
        val host = URI.create(url).toURL().host
        require(host in CloudProviders.ALLOWED_HOSTS || host == "localhost" || host == "127.0.0.1") { "host not allowed" }
        val body = buildString {
            append("""{"model":${MiniJson.quote(p.model)},"messages":[{"role":"user","content":${MiniJson.quote(prompt)}}],""")
            append(""""max_tokens":$maxTokens,"temperature":0.2""")
            // The open model on Baseten reasons first; without this its whole token budget goes to hidden reasoning
            // and the answer comes back empty (measured 1 Nov 2026).
            if (host == "inference.baseten.co") append(""","reasoning_effort":"none","chat_template_kwargs":{"enable_thinking":false}""")
            if (jsonSchema != null) {
                append(""","response_format":{"type":"json_schema","json_schema":{"name":"answer","strict":true,"schema":$jsonSchema}}""")
            }
            append("}")
        }
        val text = post(url, body, p.apiKey) ?: return null
        val root = MiniJson.parse(text)
        usageIn = (MiniJson.path(root, "usage", "prompt_tokens") as? Number)?.toLong() ?: 0
        usageOut = (MiniJson.path(root, "usage", "completion_tokens") as? Number)?.toLong() ?: 0
        return MiniJson.path(root, "choices", 0, "message", "content") as? String
    }

    /** Firebase callable protocol: {"data": {...}} in, {"result": {...}} out. The server adds the key. */
    private fun viaProxy(proxy: CloudProxy, prompt: String, jsonSchema: String?, maxTokens: Int): String? {
        require(CloudProviders.proxyHostAllowed(proxy)) { "host not allowed" }
        val token = proxy.idToken()
        if (token.isNullOrBlank()) { lastError = "not signed in"; return null }
        val body = buildString {
            append("""{"data":{"provider":${MiniJson.quote(p.id)},"prompt":${MiniJson.quote(prompt)},"maxTokens":$maxTokens""")
            if (jsonSchema != null) append(""","schema":${MiniJson.quote(jsonSchema)}""")
            append("}}")
        }
        val text = post(proxy.url, body, token) ?: return null
        return MiniJson.path(MiniJson.parse(text), "result", "content") as? String
    }

    private fun post(url: String, body: String, bearer: String): String? {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $bearer")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) { lastError = "HTTP $code"; return null }
        return conn.inputStream.use { String(it.readBytes()) }
    }
}

/** A [TextModel] for the daily note. [remote] makes the note writer hide the parent's name from the prompt. */
class CloudTextModel(private val p: CloudProvider, private val client: ChatClient = ChatClient(p)) : TextModel {
    override val id = p.id
    override val remote = true
    override fun generate(prompt: String): String? = client.complete(prompt)
    val lastError: String? get() = client.lastError
}
