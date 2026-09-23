package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The real case that motivated all of this (verified on the phone): DAN DA DAN ended up saved
 * under two different items and the SAME chapter (same pageUrl hash, `31fe74c5`) showed up
 * `completed` in one and `queued` in the other — 461 MB about to be downloaded a second time.
 */
class DuplicateDownloadPolicyTest {

    private val alreadyDownloaded = EpisodeOrigin("web:series:tt30217403::31fe74c5", torrentFileIndex = null)
    private val theDuplicate = EpisodeOrigin("web:series:anilist171018::31fe74c5", torrentFileIndex = null)

    @Test
    fun `the same web chapter under another item is a duplicate`() {
        assertEquals(
            alreadyDownloaded.episodeId,
            DuplicateDownloadPolicy.completedDuplicateOf(theDuplicate, listOf(alreadyDownloaded)),
        )
    }

    @Test
    fun `another chapter of the same series isn't a duplicate`() {
        val anotherChapter = EpisodeOrigin("web:series:anilist171018::aa11bb22", torrentFileIndex = null)
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(anotherChapter, listOf(alreadyDownloaded)))
    }

    @Test
    fun `with nothing downloaded there's no duplicate`() {
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(theDuplicate, emptyList()))
    }

    /** Whether THIS episode is already downloaded is the queue's business, not this cross-item detection. */
    @Test
    fun `it's not considered a duplicate of itself`() {
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(alreadyDownloaded, listOf(alreadyDownloaded)))
    }

    // --- Torrent: the suffix is the infohash, and the chosen file goes separately -----------------------

    @Test
    fun `the same torrent and file under another item is a duplicate`() {
        val downloaded = EpisodeOrigin("torrent:series:tt30217403::abc123", torrentFileIndex = 4)
        val duplicate = EpisodeOrigin("torrent:anime:171018::abc123", torrentFileIndex = 4)
        assertEquals(
            downloaded.episodeId,
            DuplicateDownloadPolicy.completedDuplicateOf(duplicate, listOf(downloaded)),
        )
    }

    /** Same pack, different file = different chapter: downloading too much beats blocking too much. */
    @Test
    fun `the same torrent with a different file isn't a duplicate`() {
        val downloaded = EpisodeOrigin("torrent:series:tt30217403::abc123", torrentFileIndex = 4)
        val other = EpisodeOrigin("torrent:anime:171018::abc123", torrentFileIndex = 7)
        assertNull(DuplicateDownloadPolicy.completedDuplicateOf(other, listOf(downloaded)))
    }

    /** Magnets don't carry a file index (it's null on both): they're still the same chapter. */
    @Test
    fun `two magnets of the same infohash are a duplicate`() {
        val downloaded = EpisodeOrigin("torrent:series:tt30217403::abc123", torrentFileIndex = null)
        val duplicate = EpisodeOrigin("torrent:series:tmdb240411::abc123", torrentFileIndex = null)
        assertNotNull(DuplicateDownloadPolicy.completedDuplicateOf(duplicate, listOf(downloaded)))
    }

    // --- Sources where the same content CAN'T end up under two items --------------------------

    /**
     * These ids deliberately have no key: on archive.org the itemId IS the (unique) identifier, and
     * on standalone web/torrent movies the itemId is already the origin's hash, so the same content
     * always yields the same episodeId. And comparing them would be worse than doing nothing: in
     * `torrent:<hash>::<index>` the suffix is a file index, which collides between different
     * torrents (they all have a file 0) and would pass off another movie as "already downloaded".
     */
    @Test
    fun `archive and standalone movies aren't part of the detection`() {
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("dragon-ball-gt::ep01.mp4", null)))
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("web:9f2a1b::0", null)))
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("torrent:abc123::0", 0)))
    }

    @Test
    fun `an id with no suffix has no key`() {
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("web:series:tt30217403", null)))
        assertNull(DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("", null)))
    }

    /** Web and torrent don't cross even if the suffix happened to match. */
    @Test
    fun `web and torrent keys don't collide`() {
        val web = DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("web:series:tt1::abc", null))
        val torrent = DuplicateDownloadPolicy.originKeyOf(EpisodeOrigin("torrent:series:tt1::abc", null))
        assertNotNull(web)
        assertNotNull(torrent)
        assert(web != torrent)
    }

    /**
     * The device's EXACT case: the duplicate row was already `queued` from before the fix, so no
     * `enqueue` will ever evaluate it again. The worker's gate catches it right before marking it
     * `downloading`, which is the last chance not to download 461 MB all over again.
     */
    @Test
    fun `a row that was already queued gets detected too`() {
        val alreadyQueued = EpisodeOrigin("web:series:anilist171018::31fe74c5", torrentFileIndex = null)
        assertEquals(
            "web:series:tt30217403::31fe74c5",
            DuplicateDownloadPolicy.completedDuplicateOf(alreadyQueued, listOf(alreadyDownloaded)),
        )
    }

    // --- Deleting the shared file ---------------------------------------------------------
    //
    // When adopting the twin's file, two rows point at the SAME `filePath`. `remove()` deletes
    // through two paths (the row's explicit path and a sweep by name) and BOTH have to respect
    // that, or the surviving row ends up saying "Listo" over something that's no longer there.

    /** The real file, named after the ORIGINAL twin's episodeId (the one that actually downloaded it). */
    private val sharedFile = "/data/Movies/web_series_tt30217403__31fe74c5.mkv"

    @Test
    fun `the file isn't deleted if another row references it`() {
        assertEquals(true, DuplicateDownloadPolicy.canDeleteFile(sharedFile, emptySet()))
        assertEquals(false, DuplicateDownloadPolicy.canDeleteFile(sharedFile, setOf(sharedFile)))
    }

    /**
     * The hole that had to be patched: removing the ORIGINAL twin (A) correctly skipped the
     * explicit delete, but the prefix sweep deletes by NAME and the file happens to be named after
     * A's episodeId — so it took it anyway and the adopter (B) was left lying. The path filter
     * covers both paths with the same rule.
     */
    @Test
    fun `the prefix sweep doesn't delete the file another row adopted either`() {
        val candidates = listOf(sharedFile)
        assertEquals(
            emptyList<String>(),
            DuplicateDownloadPolicy.deletablePaths(candidates, referencedByOthers = setOf(sharedFile)),
        )
    }

    /** The sweep's partials are never another row's filePath: they keep getting deleted. */
    @Test
    fun `the sweep keeps cleaning up the partials`() {
        val part = "/data/Movies/web_series_tt30217403__31fe74c5.mkv.part"
        val src = "$part.src"
        assertEquals(
            listOf(part, src),
            DuplicateDownloadPolicy.deletablePaths(
                listOf(sharedFile, part, src),
                referencedByOthers = setOf(sharedFile),
            ),
        )
    }

    /** With nobody else referencing it, `remove` deletes everything it swept, as always. */
    @Test
    fun `with no rows sharing it, everything gets deleted`() {
        val candidates = listOf(sharedFile, "$sharedFile.part")
        assertEquals(candidates, DuplicateDownloadPolicy.deletablePaths(candidates, emptySet()))
    }

    // --- Notice to the user -----------------------------------------------------------------------

    @Test
    fun `the notice only shows up if something got skipped`() {
        assertNull(DuplicateDownloadPolicy.skippedNotice(0))
        assertNull(DuplicateDownloadPolicy.skippedNotice(-1))
        assertEquals("Ya lo tienes descargado en el dispositivo", DuplicateDownloadPolicy.skippedNotice(1))
        assertEquals(
            "12 capítulos ya estaban descargados en el dispositivo",
            DuplicateDownloadPolicy.skippedNotice(12),
        )
    }
}
