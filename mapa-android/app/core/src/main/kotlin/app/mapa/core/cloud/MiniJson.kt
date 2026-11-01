package app.mapa.core.cloud

/** A small JSON reader and string escaper, so the pure-JVM core needs no JSON library. */
object MiniJson {
    fun parse(s: String): Any? = Reader(s).run { skip(); readValue().also { skip(); require(i == s.length) { "trailing text" } } }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    @Suppress("UNCHECKED_CAST")
    fun path(root: Any?, vararg keys: Any): Any? {
        var cur = root
        for (k in keys) {
            cur = when (k) {
                is String -> (cur as? Map<String, Any?>)?.get(k)
                is Int -> (cur as? List<Any?>)?.getOrNull(k)
                else -> null
            } ?: return null
        }
        return cur
    }

    private class Reader(val s: String) {
        var i = 0

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun readValue(): Any? {
            skip()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun readNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += readValue()
                skip()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> error("bad array at $i") }
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip()
                require(s[i] == '"') { "object key expected at $i" }
                val k = readString()
                skip()
                require(s[i++] == ':') { "colon expected at $i" }
                out[k] = readValue()
                skip()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> error("bad object at $i") }
            }
        }
    }
}
