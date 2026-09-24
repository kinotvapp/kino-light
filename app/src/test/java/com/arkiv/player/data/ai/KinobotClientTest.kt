package com.arkiv.player.data.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KinobotClientTest {

    /** Runs a reply over a scripted stream and returns the emitted chunks. */
    private suspend fun run(vararg deltas: String): List<KinobotChunk> =
        KinobotClient { flowOf(*deltas) }.reply(emptyList()).toList()

    private fun List<KinobotChunk>.visible() =
        filterIsInstance<KinobotChunk.Delta>().joinToString("") { it.text }

    @Test fun `plain reply streams the text and ends with empty suggestions`() = runTest {
        val chunks = run("Te ", "recomiendo ", "Akira")
        assertEquals("Te recomiendo Akira", chunks.visible())
        assertEquals(KinobotChunk.Done(emptyList()), chunks.last())
    }

    @Test fun `trailing suggestions marker becomes chips and is never shown`() = runTest {
        val chunks = run("Mira esto.", "\n[[SUGERENCIAS]] Akira | Ghost in the Shell")
        assertFalse(chunks.visible().contains("[[SUGERENCIAS]]"))
        assertEquals("Mira esto.", chunks.visible().trim())
        assertEquals(KinobotChunk.Done(listOf("Akira", "Ghost in the Shell")), chunks.last())
    }

    @Test fun `a suggestions marker split across deltas is still parsed, not shown`() = runTest {
        val chunks = run("Hola.\n[[SUGE", "RENCIAS]] Akira")
        assertFalse(chunks.visible().contains("[[SUGE"))
        assertEquals("Hola.", chunks.visible().trim())
        assertEquals(KinobotChunk.Done(listOf("Akira")), chunks.last())
    }

    @Test fun `a leading offtopic marker yields the fixed refusal and no model text`() = runTest {
        val chunks = run("[[OFFTOPIC]]")
        assertEquals(listOf(KinobotChunk.Refusal(KinobotClient.REFUSAL)), chunks)
        assertEquals("", chunks.visible())
    }

    @Test fun `the offtopic marker is caught even when split across deltas`() = runTest {
        val chunks = run("[[OFF", "TOPIC]]")
        assertEquals(listOf(KinobotChunk.Refusal(KinobotClient.REFUSAL)), chunks)
    }

    @Test fun `suggestions are de-duplicated and capped at six`() = runTest {
        val chunks = run("Ve esto.\n[[SUGERENCIAS]] A | B | C | D | E | F | G | A")
        val done = chunks.last() as KinobotChunk.Done
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), done.suggestions)
    }

    @Test fun `an empty stream ends in Failed`() = runTest {
        val chunks = run()
        assertEquals(listOf(KinobotChunk.Failed), chunks)
    }
}
