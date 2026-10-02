package com.arkiv.player.data.local

import com.arkiv.player.data.subtitles.SavedOnlineSubtitle
import com.arkiv.player.playback.LocalMp4Audio
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [Mp4Prep] over real files: a finished download (the two-audio TS fixture) becomes `<id>.mp4`, the
 * rows follow it, the original goes (or waits while it is open), and older downloads are prepared
 * lazily the first time they are opened -- never twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Mp4PrepTest {

    private lateinit var dir: File
    private val rows = HashMap<String, String>()
    private val moves = mutableListOf<Pair<String, String>>()
    private val scheduled = mutableListOf<List<String>>()
    private val failures = mutableListOf<String>()
    private var free = Long.MAX_VALUE / 4
    private var tv = false
    private var online: List<SavedOnlineSubtitle> = emptyList()

    private val prep by lazy {
        Mp4Prep(
            completedPath = { id -> rows[id]?.takeIf { File(it).exists() } },
            movePath = { old, new ->
                moves += old to new
                rows.replaceAll { _, p -> if (p == old) new else p }
            },
            completedIds = { rows.keys.toList() },
            onlineSubtitles = { online },
            schedule = { scheduled += it },
            isTelevision = { tv },
            freeSpace = { free },
            reportFailure = { id, _ -> failures += id },
        )
    }

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "mp4-prep-${System.nanoTime()}").apply { mkdirs() }
        LocalFileUse.playing = null
        LocalFileUse.playingEpisode = null
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        LocalFileUse.playing = null
        LocalFileUse.playingEpisode = null
    }

    private fun download(id: String, resource: String = "mp4-repack-2audio.ts"): File {
        val f = File(dir, "${LocalFilePaths.sanitize(id)}.ts")
        f.writeBytes(Mp4PrepTest::class.java.classLoader!!.getResourceAsStream(resource)!!.use { it.readBytes() })
        rows[id] = f.absolutePath
        return f
    }

    @Test fun `a finished TS becomes a faststart MP4 with both audios, the rows follow and the original goes`() = runBlocking {
        val ts = download("magis:7")
        rows["magis:twin"] = ts.absolutePath
        val percents = mutableListOf<Int>()
        assertEquals(PrepState.READY, prep.prepare("magis:7") { percents += it })
        val mp4 = File(dir, "magis_7.mp4")
        assertTrue(mp4.exists())
        assertTrue(Mp4FastStart.isFastStart(mp4))
        assertEquals(2, LocalMp4Audio.audioTracks(mp4))
        // Cast to a Chromecast: the first audio goes as the file, the second needs its single-audio copy.
        assertFalse(LocalMp4Audio.needsCopy(mp4, null))
        assertFalse(LocalMp4Audio.needsCopy(mp4, 0))
        assertTrue(LocalMp4Audio.needsCopy(mp4, 1))
        assertFalse("the original is gone", ts.exists())
        assertEquals(listOf(ts.absolutePath to mp4.absolutePath), moves)
        assertEquals("the twin that adopted the file follows it", mp4.absolutePath, rows["magis:twin"])
        assertEquals(100, percents.last())
        assertEquals(PrepState.READY, prep.readMarker(dir, "magis:7")?.state)
        assertEquals(PrepStatus(PrepState.READY), prep.status.value["magis:7"])
        // Prepared once: another pass changes nothing.
        moves.clear()
        assertEquals(PrepState.READY, prep.prepare("magis:7"))
        assertTrue(moves.isEmpty())
    }

    @Test fun `an original still playing is kept until nothing holds it`() = runBlocking {
        val ts = download("magis:7")
        LocalFileUse.playing = ts.absolutePath
        assertEquals(PrepState.READY, prep.prepare("magis:7"))
        assertTrue("still open on the phone", ts.exists())
        assertEquals(listOf(ts.absolutePath), prep.readMarker(dir, "magis:7")!!.pendingDelete)
        // The player opens the MP4 next time: the original is let go.
        prep.onOpened("magis:7", File(dir, "magis_7.mp4").absolutePath)
        assertFalse(ts.exists())
        assertEquals(emptyList<String>(), prep.readMarker(dir, "magis:7")!!.pendingDelete)
    }

    @Test fun `an older download is prepared the first time it is opened, and only then`() = runBlocking {
        val ts = download("magis:old")
        prep.onOpened("magis:old", ts.absolutePath)
        assertEquals(listOf(listOf("magis:old")), scheduled)
        assertEquals("in use from now on", ts.absolutePath, LocalFileUse.playing)
        assertEquals("magis:old", LocalFileUse.playingEpisode)
        // The job runs (in WorkManager); opening it again schedules nothing more.
        prep.prepare("magis:old")
        prep.onOpened("magis:old", rows["magis:old"]!!)
        assertEquals(1, scheduled.size)
    }

    @Test fun `no room, the original stays, marked, and a later pass converts it`() = runBlocking {
        val ts = download("magis:7")
        free = ts.length() // less than the file again plus the reserve
        assertEquals(PrepState.NO_SPACE, prep.prepare("magis:7"))
        assertTrue(ts.exists())
        assertFalse(File(dir, "magis_7.mp4").exists())
        assertEquals(listOf("magis:7"), prep.sweepIds(dir))
        free = Long.MAX_VALUE / 4
        assertEquals(PrepState.READY, prep.prepare("magis:7"))
        assertEquals(emptyList<String>(), prep.sweepIds(dir))
    }

    @Test fun `AC-3 audio is kept as it is for good, subtitles prepared anyway`() = runBlocking {
        val ts = download("magis:ac3", "mp4-repack-ac3.ts")
        val sub = File(dir, "x.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHola\n") }
        online = listOf(SavedOnlineSubtitle("es", "Español", sub.path))
        assertEquals(PrepState.UNSUPPORTED, prep.prepare("magis:ac3"))
        assertTrue(ts.exists())
        assertTrue(File(dir, "magis_ac3.es.srt").exists())
        assertTrue(failures.isEmpty())
        assertEquals(emptyList<String>(), prep.sweepIds(dir))
        prep.onOpened("magis:ac3", ts.absolutePath)
        assertTrue("never scheduled again", scheduled.isEmpty())
    }

    @Test fun `an MP4 download only gets its subtitles`() = runBlocking {
        val mp4 = File(dir, "plugin_a.mp4")
        assertTrue(Mp4Repackager().repack(download("plugin:a"), mp4) is Mp4Repackager.Result.Done)
        rows["plugin:a"] = mp4.absolutePath
        assertEquals(PrepState.READY, prep.prepare("plugin:a"))
        assertTrue(moves.isEmpty())
    }

    @Test fun `nothing on a TV, nothing without a finished download`() = runBlocking {
        download("magis:7")
        tv = true
        assertNull(prep.prepare("magis:7"))
        prep.onOpened("magis:7", rows["magis:7"]!!)
        assertTrue(scheduled.isEmpty())
        tv = false
        assertNull(prep.prepare("magis:none"))
    }

    @Test fun `removed while it converted, what was written goes`() = runBlocking {
        val ts = download("magis:7")
        val gone = Mp4Prep(
            completedPath = { id -> if (moves.isEmpty() && File(ts.path).exists() && !File(dir, "magis_7.mp4").exists()) rows[id] else null },
            movePath = { o, n -> moves += o to n },
            completedIds = { emptyList() },
            onlineSubtitles = { emptyList() },
            schedule = {},
            freeSpace = { Long.MAX_VALUE / 4 },
        )
        assertNull(gone.prepare("magis:7"))
        assertFalse(File(dir, "magis_7.mp4").exists())
        assertTrue(moves.isEmpty())
    }
}
