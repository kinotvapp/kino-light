package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuBenchTest {

    @Test
    fun `the workload is deterministic and depends on the iteration count`() {
        assertEquals(CpuBench.workload(10_000), CpuBench.workload(10_000))
        assertNotEquals(CpuBench.workload(10_000), CpuBench.workload(10_001))
    }

    @Test
    fun `it does real work, not a constant`() {
        assertNotEquals(0L, CpuBench.workload(1_000))
    }

    @Test
    fun `the fastest run counts, so a run interrupted by other work does not make a device look slow`() {
        // Three runs read the clock twice each: durations 9 ms, 4 ms and 7 ms.
        val readings = ArrayDeque(listOf(0L, 9_000_000L, 10_000_000L, 14_000_000L, 15_000_000L, 22_000_000L))
        val ms = CpuBench.measureMs(runs = 3, iterations = 1, nanoTime = { readings.removeFirst() })
        assertEquals(4.0f, ms, 0.0001f)
    }

    @Test
    fun `the real clock reports a positive time`() {
        assertTrue(CpuBench.measureMs(runs = 1, iterations = 200_000) > 0f)
    }
}
