package app.mapa.parent.rhythm

import org.json.JSONArray
import org.json.JSONObject

/** Keeps the last uploaded documents on the phone, so the parent sees exactly what the family sees. */
object JsonMaps {
    fun toJson(m: Map<String, Any?>): String = JSONObject(m.mapValues { wrap(it.value) }).toString()

    fun fromJson(s: String): Map<String, Any?>? = runCatching { unwrap(JSONObject(s)) as Map<String, Any?> }.getOrNull()

    private fun wrap(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(v.entries.associate { it.key.toString() to wrap(it.value) })
        is List<*> -> JSONArray(v.map { wrap(it) })
        else -> v
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { unwrap(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        else -> v
    }
}
