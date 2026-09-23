package com.arkiv.player.data.caracol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The six real qualities of the episode measured on 2026-09-13 ("Dulce Amor" E1). */
private fun realManifest() = listOf(
    CaracolTrack(group = 0, track = 0, isVideo = true, height = 144, bitsPerSecond = 164_344),
    CaracolTrack(group = 0, track = 1, isVideo = true, height = 240, bitsPerSecond = 330_000),
    CaracolTrack(group = 0, track = 2, isVideo = true, height = 360, bitsPerSecond = 700_000),
    CaracolTrack(group = 0, track = 3, isVideo = true, height = 480, bitsPerSecond = 1_200_000),
    CaracolTrack(group = 0, track = 4, isVideo = true, height = 720, bitsPerSecond = 2_400_000),
    CaracolTrack(group = 0, track = 5, isVideo = true, height = 1080, bitsPerSecond = 4_452_000),
    CaracolTrack(group = 1, track = 0, isVideo = false, height = 0, bitsPerSecond = 64_000),
    CaracolTrack(group = 1, track = 1, isVideo = false, height = 0, bitsPerSecond = 97_768),
)

class CaracolQualityTest {

    @Test
    fun `takes the best video that does not exceed the ceiling`() {
        val chosen = CaracolQuality.choose(realManifest(), targetHeight = 480)
        assertEquals(480, chosen.first { it.isVideo }.height)
    }

    @Test
    fun `the default ceiling leaves 720 and not 1080`() {
        val chosen = CaracolQuality.choose(realManifest())
        assertEquals(720, chosen.first { it.isVideo }.height)
    }

    @Test
    fun `audio is the one with most bits because next to video it weighs nothing`() {
        val chosen = CaracolQuality.choose(realManifest())
        assertEquals(97_768, chosen.first { !it.isVideo }.bitsPerSecond)
    }

    @Test
    fun `a single video one and a single audio one`() {
        val chosen = CaracolQuality.choose(realManifest())
        assertEquals(1, chosen.count { it.isVideo })
        assertEquals(1, chosen.count { !it.isVideo })
    }

    @Test
    fun `if all exceed the ceiling the smallest is downloaded instead of giving up`() {
        val onlyLarge = realManifest().filter { !it.isVideo || it.height >= 720 }
        val chosen = CaracolQuality.choose(onlyLarge, targetHeight = 360)
        assertEquals(720, chosen.first { it.isVideo }.height)
    }

    @Test
    fun `with no video there is nothing to offer`() {
        val onlyAudio = realManifest().filter { !it.isVideo }
        assertTrue(CaracolQuality.choose(onlyAudio).isEmpty())
    }

    @Test
    fun `with no audio the video is still downloaded`() {
        val onlyVideo = realManifest().filter { it.isVideo }
        val chosen = CaracolQuality.choose(onlyVideo)
        assertEquals(1, chosen.size)
        assertTrue(chosen.single().isVideo)
    }

    @Test
    fun `the estimate reproduces the 97 MB measured on the phone`() {
        // 144p + the 97 kbps audio, 2_814_505 ms: the spike left 97 MB on disk.
        val tracks = realManifest().filter { (it.isVideo && it.height == 144) || it.bitsPerSecond == 97_768 }
        val bytes = CaracolQuality.estimatedBytes(CaracolQuality.choose(tracks), 2_814_505)
        assertEquals(92L, bytes / 1_000_000)
    }

    @Test
    fun `with no duration it does not make up a weight`() {
        assertEquals(0L, CaracolQuality.estimatedBytes(CaracolQuality.choose(realManifest()), 0))
    }
}
