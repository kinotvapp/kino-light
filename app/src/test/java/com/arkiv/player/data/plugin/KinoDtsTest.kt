package com.arkiv.player.data.plugin

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `docs/plugins/kino.d.ts` promises authors a `kino` API; this checks the runtime's prelude
 * exposes exactly those members (names, and function vs. value), no more and no fewer.
 */
class KinoDtsTest {
    /** "kino.fetch=function", "kino.crypto.hash=function", "kino.apiVersion=value", … from `declare namespace kino`. */
    private fun declared(): Set<String> {
        val lines = File("../docs/plugins/kino.d.ts").readLines()
        val start = lines.indexOfFirst { it.startsWith("declare namespace kino") }
        val path = ArrayDeque<String>()
        val out = mutableSetOf<String>()
        var depth = 0
        for (raw in lines.drop(start)) {
            val line = raw.trim()
            Regex("^(?:declare )?namespace (\\w+) \\{$").find(line)?.let { path.addLast(it.groupValues[1]); depth++; return@let }
                ?: run {
                    Regex("^function (\\w+)\\(").find(line)?.let { out += (path + it.groupValues[1]).joinToString(".") + "=function" }
                    Regex("^const (\\w+):").find(line)?.let { out += (path + it.groupValues[1]).joinToString(".") + "=value" }
                    if (line == "}") {
                        path.removeLast()
                        depth--
                    }
                }
            if (depth == 0) break
        }
        return out
    }

    private fun runtime(): Set<String> = runBlocking {
        val rt = PluginRuntime.open(
            "dts",
            """
            export async function home() {
              const out = [];
              const walk = (o, p) => Object.keys(o).forEach((k) => {
                const v = o[k];
                if (typeof v === 'function') out.push(p + '.' + k + '=function');
                else if (v !== null && typeof v === 'object') walk(v, p + '.' + k);
                else out.push(p + '.' + k + '=value');
              });
              walk(kino, 'kino');
              return out;
            }
            """.trimIndent(),
            object : PluginHost {
                override suspend fun fetch(requestJson: String) = "{}"
                override fun select(html: String, css: String) = "[]"
                override fun storageGet(key: String): String? = null
                override fun storageSet(key: String, value: String) = Unit
                override fun storageRemove(key: String) = Unit
                override fun log(level: String, message: String) = Unit
            },
            PluginEnv(appVersion = "x"),
        )
        try {
            JSONArray(rt.call("home", "null", 5_000)).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        } finally {
            rt.close()
        }
    }

    @Test fun `kino d ts declares exactly what the runtime exposes`() {
        assertEquals(runtime().sorted(), declared().sorted())
    }
}
