package app.mapa.parent.ui

import android.content.Context
import app.mapa.core.language.Lang
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Live translation for fixed UI labels. Only the English interface catalog leaves the phone. */
internal object ParentTranslations {
    private const val endpoint = "https://shreyash-sharma--mapa-web-web.us-east.modal.run/api/app-translate/"
    private const val prefsName = "mapa_parent_translations"

    fun cached(context: Context, lang: Lang): Map<String, String> =
        if (lang in liveLanguages) read(context, lang) else emptyMap()

    fun fetch(context: Context, lang: Lang): Map<String, String> {
        if (lang !in liveLanguages) return emptyMap()
        val source = ParentText(Lang.EN).staticCatalog()
        val body = JSONObject().put("strings", JSONArray(source)).toString()
        val connection = (URL(endpoint + lang.code).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return read(context, lang)
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val translated = JSONObject(payload).optJSONObject("translations") ?: return read(context, lang)
            val result = buildMap {
                source.forEachIndexed { index, english ->
                    translated.optString(index.toString()).takeIf(String::isNotBlank)?.let { put(english, it) }
                }
            }
            if (result.isNotEmpty()) write(context, lang, result)
            result.ifEmpty { read(context, lang) }
        } finally {
            connection.disconnect()
        }
    }

    private val liveLanguages = setOf(Lang.TA, Lang.FR, Lang.MR)

    private fun read(context: Context, lang: Lang): Map<String, String> = runCatching {
        val values = JSONObject(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(lang.code, "{}") ?: "{}")
        buildMap { values.keys().forEach { key -> put(key, values.getString(key)) } }
    }.getOrDefault(emptyMap())

    private fun write(context: Context, lang: Lang, values: Map<String, String>) {
        val json = JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(lang.code, json.toString()).apply()
    }
}
