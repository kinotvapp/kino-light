package com.arkiv.player.ui.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettleDebounceTest {
    @Test fun `a burst of zaps runs only the last one, after it has been quiet`() = runTest {
        val ran = mutableListOf<String>()
        val settle = SettleDebounce(this, 350)
        settle.run { ran += "a" }
        advanceTimeBy(100); settle.run { ran += "b" }
        advanceTimeBy(100); settle.run { ran += "c" }
        advanceTimeBy(349); runCurrent()
        assertEquals("nothing yet: still inside the quiet window", emptyList<String>(), ran)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("c"), ran)
    }

    @Test fun `a single zap runs once, after the quiet window`() = runTest {
        val ran = mutableListOf<String>()
        SettleDebounce(this, 350).run { ran += "a" }
        advanceTimeBy(349); runCurrent()
        assertEquals(emptyList<String>(), ran)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("a"), ran)
    }

    @Test fun `cancel drops a pending zap, for an open that goes straight through`() = runTest {
        val ran = mutableListOf<String>()
        val settle = SettleDebounce(this, 350)
        settle.run { ran += "a" }
        settle.cancel()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(emptyList<String>(), ran)
    }

    @Test fun `zaps spaced apart each run`() = runTest {
        val ran = mutableListOf<String>()
        val settle = SettleDebounce(this, 350)
        settle.run { ran += "a" }
        advanceTimeBy(500); runCurrent()
        settle.run { ran += "b" }
        advanceTimeBy(500); runCurrent()
        assertEquals(listOf("a", "b"), ran)
    }

    @Test fun `an action that cancels the debounce from inside still finishes`() = runTest {
        val ran = mutableListOf<String>()
        val settle = SettleDebounce(this, 350)
        settle.run { settle.cancel(); ran += "opened" }
        advanceTimeBy(400); runCurrent()
        assertEquals(listOf("opened"), ran)
    }
}
