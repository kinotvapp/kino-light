package com.arkiv.player.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kino speaks tuteo (Colombian Spanish), never voseo: "Toca", "agregas", "Elige", not "Tocá",
 * "agregás", "Elegí". Scans every string literal in the app's code for the voseo forms that have
 * slipped in before (the home tour, the source picker), so a new one fails here instead of on a
 * screen. Comments are skipped: only what can reach the person counts.
 */
class NoVoseoTest {

    private val voseo = listOf(
        "agregás", "tenés", "podés", "querés", "sabés", "elegís", "buscás", "tocás", "mirás", "usás",
        "necesitás", "instalás", "activás", "escribís", "abrís", "volvés", "ponés", "hacés", "probás",
        "Tocá", "Mirá", "Buscá", "Elegí", "Poné", "Hacé", "Volvé", "Probá", "Esperá", "Abrí", "Escribí",
        "Instalá", "Activá", "Revisá", "Agregá", "Configurá", "Usá", "Entrá", "Dejá", "Cerrá",
        "Reintentá", "Intentá", "Seguí", "Cambiá", "Pegá", "Copiá", "Guardá", "Apretá", "Presioná",
        "Fijate", "Acordate", "Decime", "Avisame",
    )

    private val wordPatterns = voseo.map { Regex("(?<![\\p{L}])" + Regex.escape(it) + "(?![\\p{L}])", RegexOption.IGNORE_CASE) }
    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    @Test fun `no user-facing string uses voseo`() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val found = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .flatMap { file ->
                file.readLines().withIndex().flatMap { (i, line) ->
                    val code = line.trim()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        emptyList()
                    } else {
                        literal.findAll(line).flatMap { m ->
                            wordPatterns.filter { it.containsMatchIn(m.groupValues[1]) }
                                .map { "${file.path}:${i + 1}: ${m.value}" }
                        }.toList()
                    }
                }
            }
            .toList()
        assertTrue("voseo in user-facing strings:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
