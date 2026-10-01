package com.arkiv.player.data.live

/**
 * A small, forgiving JSON reader for lists people pass around by hand (Wiseplay "W3U" lists):
 * a BOM, `//` and block comments, trailing commas, single-quoted strings, raw control characters
 * inside strings and HTML around the document are all tolerated; whatever follows the first
 * complete value is ignored. Objects are `Map<String, Any?>` (a repeated key keeps its last value),
 * arrays `List<Any?>`, numbers `Long` or `Double`.
 *
 * Untrusted input, so it is bounded: nesting past [MAX_DEPTH] and more than [MAX_NODES] values are
 * refused with [Invalid] before they can overflow the stack or the heap. Never evaluates anything.
 */
object LenientJson {
    const val MAX_DEPTH = 32
    const val MAX_NODES = 200_000

    class Invalid(message: String) : Exception(message)

    /** Where the first `{` or `[` is, or null when [text] holds none. */
    fun start(text: String): Int? {
        val i = text.indexOfFirst { it == '{' || it == '[' }
        return if (i < 0) null else i
    }

    /** Whether [text] is a JSON document by its first visible character (a BOM and whitespace skipped). */
    fun looksLikeJson(text: String): Boolean {
        val c = text.firstOrNull { !it.isWhitespace() && it != '﻿' } ?: return false
        return c == '{' || c == '['
    }

    fun parse(text: String, maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): Any? {
        val from = start(text) ?: throw Invalid("no JSON document")
        return Reader(text, from, maxDepth, maxNodes).value(0)
    }

    private class Reader(val s: String, var i: Int, val maxDepth: Int, val maxNodes: Int) {
        var nodes = 0

        fun fail(what: String): Nothing = throw Invalid("$what at $i")

        fun skip() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() || c == '﻿' -> i++
                    c == '/' && s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                    c == '/' && s.startsWith("/*", i) -> {
                        val end = s.indexOf("*/", i + 2)
                        i = if (end < 0) s.length else end + 2
                    }
                    else -> return
                }
            }
        }

        fun value(depth: Int): Any? {
            if (++nodes > maxNodes) throw Invalid("too many values")
            skip()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"', '\'' -> str(c)
                else -> literal()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            if (depth > maxDepth) throw Invalid("nested too deep")
            i++
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skip()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    '}' -> { i++; return out }
                    '"', '\'' -> Unit
                    else -> fail("expected a key")
                }
                val key = str(s[i])
                skip()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                out[key] = value(depth)
                skip()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++   // a trailing comma before '}' is fine: the loop sees '}' next
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            if (depth > maxDepth) throw Invalid("nested too deep")
            i++
            val out = ArrayList<Any?>()
            while (true) {
                skip()
                if (i >= s.length) fail("unterminated array")
                if (s[i] == ']') { i++; return out }
                out += value(depth)
                skip()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun str(quote: Char): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == quote -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated string")
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad escape")
                                b.append(code.toChar())
                                i += 4
                            }
                            else -> b.append(e)   // \" \' \\ \/ and anything else: the character itself
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun literal(): Any? {
            val begin = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "+-.")) i++
            val word = s.substring(begin, i)
            return when (word) {
                "true" -> true
                "false" -> false
                "null" -> null
                "" -> fail("unexpected '${s[i]}'")
                else -> word.toLongOrNull() ?: word.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("bad value '$word'")
            }
        }
    }
}
