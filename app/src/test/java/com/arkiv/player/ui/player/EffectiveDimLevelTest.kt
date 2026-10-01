package com.arkiv.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveDimLevelTest {
    @Test
    fun `live always plays at full brightness whatever was saved`() {
        assertEquals(0, effectiveDimLevel(saved = 0, isLive = true))
        assertEquals(0, effectiveDimLevel(saved = 5, isLive = true))
        assertEquals(0, effectiveDimLevel(saved = DIM_MAX_LEVEL, isLive = true))
        assertEquals(0, effectiveDimLevel(saved = 99, isLive = true))
    }

    @Test
    fun `vod keeps the saved level`() {
        assertEquals(0, effectiveDimLevel(saved = 0, isLive = false))
        assertEquals(4, effectiveDimLevel(saved = 4, isLive = false))
        assertEquals(DIM_MAX_LEVEL, effectiveDimLevel(saved = DIM_MAX_LEVEL, isLive = false))
    }

    @Test
    fun `vod clamps an out-of-range saved level`() {
        assertEquals(DIM_MAX_LEVEL, effectiveDimLevel(saved = DIM_MAX_LEVEL + 7, isLive = false))
        assertEquals(0, effectiveDimLevel(saved = -3, isLive = false))
    }
}
