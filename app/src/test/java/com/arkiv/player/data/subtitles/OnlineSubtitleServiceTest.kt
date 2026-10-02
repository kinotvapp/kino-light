package com.arkiv.player.data.subtitles

import com.arkiv.player.data.plugin.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The search end to end with fake providers: no key, unknown title, groups, the downloaded file. */
class OnlineSubtitleServiceTest {

    @get:Rule val tmp = TemporaryFolder()

    private class MemorySecrets : SecretStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class FakeProvider(
        override val id: SubtitleProviderId,
        val results: SubtitleResult<List<OnlineSubtitle>>,
        val file: SubtitleResult<ByteArray> = SubtitleResult.Ok(ByteArray(0)),
    ) : SubtitleProvider {
        val keysSeen = mutableListOf<String>()
        override suspend fun search(query: SubtitleQuery, auth: ProviderAuth) = results.also { keysSeen += auth.apiKey }
        override suspend fun download(subtitle: OnlineSubtitle, auth: ProviderAuth, query: SubtitleQuery) = file
        override suspend fun test(auth: ProviderAuth): SubtitleResult<Unit> = SubtitleResult.Ok(Unit)
    }

    private val latin1Srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nAñoranza, ¿qué?\r\n".toByteArray(Charsets.ISO_8859_1)

    private fun service(
        shared: String = "",
        providers: List<SubtitleProvider>,
        subject: SubtitleSubject? = SubtitleSubject("movie", tmdbId = 603, imdbId = "tt0133093", title = "Matrix"),
    ): OnlineSubtitleService {
        val keys = SubtitleKeys(MemorySecrets(), { if (it == SubtitleProviderId.OPENSUBTITLES) shared else "" }, null)
        return OnlineSubtitleService(
            keys, providers,
            SubtitleIdResolver({ _, _ -> null }, { _, _, _ -> null }),
            subjectFor = { subject },
            languages = { listOf("es", "en") },
            cache = OnlineSubtitleCache(tmp.newFolder()),
            prefs = null,
        )
    }

    @Test
    fun `no key anywhere hides the search`() = runBlocking {
        val s = service(providers = listOf(FakeProvider(SubtitleProviderId.OPENSUBTITLES, SubtitleResult.Ok(emptyList()))))
        assertEquals(false, s.available())
        assertEquals(OnlineSearchOutcome.NoKey, s.search("ep"))
    }

    @Test
    fun `a title the library does not know cannot be searched`() = runBlocking {
        val s = service("kino", listOf(FakeProvider(SubtitleProviderId.OPENSUBTITLES, SubtitleResult.Ok(emptyList()))), subject = null)
        assertEquals(OnlineSearchOutcome.Unidentified, s.search("ep"))
    }

    @Test
    fun `each provider with a key answers in its group, a failed one with its reason`() = runBlocking {
        val os = FakeProvider(
            SubtitleProviderId.OPENSUBTITLES,
            SubtitleResult.Ok(listOf(OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "1", "en", "a", 50), OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "2", "es", "b", 1))),
        )
        val sd = FakeProvider(SubtitleProviderId.SUBDL, SubtitleResult.Failed(SubtitleFailure.BAD_KEY))
        val s = service("kino-shared", listOf(os, sd))
        val found = s.search("ep") as OnlineSearchOutcome.Found
        // SubDL has no key (neither shared nor the person's): not asked.
        assertEquals(listOf(SubtitleProviderId.OPENSUBTITLES), found.groups.map { it.provider })
        assertEquals(listOf("2", "1"), found.groups[0].results.map { it.ref })
        assertEquals(listOf("kino-shared"), os.keysSeen)
    }

    @Test
    fun `a Windows-1252 download is kept as UTF-8 SRT and labelled for the menu`() = runBlocking {
        val os = FakeProvider(SubtitleProviderId.OPENSUBTITLES, SubtitleResult.Ok(emptyList()), SubtitleResult.Ok(latin1Srt))
        val s = service("kino", listOf(os))
        val sub = OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "9", "es", "Rel")
        val d = (s.download("ep", sub) as SubtitleResult.Ok).value
        val text = File(d.path).readText(Charsets.UTF_8)
        assertTrue(text.contains("Añoranza, ¿qué?"))
        assertTrue(text.contains("00:00:01,000 --> 00:00:02,500"))
        assertEquals("Español · OpenSubtitles · Rel", d.label)
        assertEquals("es", d.lang)
    }

    @Test
    fun `a quota or an empty file reaches the menu as its failure`() = runBlocking {
        val quota = FakeProvider(SubtitleProviderId.OPENSUBTITLES, SubtitleResult.Ok(emptyList()), SubtitleResult.Failed(SubtitleFailure.QUOTA))
        val sub = OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "9", "es", "")
        assertEquals(SubtitleResult.Failed(SubtitleFailure.QUOTA), service("kino", listOf(quota)).download("ep", sub))
        val garbage = FakeProvider(SubtitleProviderId.OPENSUBTITLES, SubtitleResult.Ok(emptyList()), SubtitleResult.Ok("no cues".toByteArray()))
        assertEquals(SubtitleResult.Failed(SubtitleFailure.NO_FILE), service("kino", listOf(garbage)).download("ep", sub))
        assertNull(OnlineSubtitleService.normalize(ByteArray(0)))
    }

    @Test
    fun `the cache drops the least recently used past its cap, never the new file`() {
        val dir = tmp.newFolder("lru")
        val cache = OnlineSubtitleCache(dir, maxBytes = 25)
        val a = cache.store("a", ByteArray(10))!!
        a.setLastModified(1_000)
        val b = cache.store("b", ByteArray(10))!!
        b.setLastModified(2_000)
        val c = cache.store("c", ByteArray(10))!!
        assertTrue(!a.exists())
        assertTrue(b.exists() && c.exists())
        assertNotNull(cache.existing(c.path))
        assertNull(cache.existing("/etc/passwd"))
    }

    @Test
    fun `each title remembers its newest online subtitles`() {
        var entries = emptyList<Pair<String, SavedOnlineSubtitle>>()
        for (i in 1..4) entries = OnlineSubtitleMemory.add(entries, "ep1", SavedOnlineSubtitle("es", "L$i", "/p$i"))
        entries = OnlineSubtitleMemory.add(entries, "ep2", SavedOnlineSubtitle("en", "X", "/x"))
        entries = OnlineSubtitleMemory.decode(OnlineSubtitleMemory.encode(entries))
        assertEquals(listOf("L2", "L3", "L4"), OnlineSubtitleMemory.of(entries, "ep1").map { it.label })
        assertEquals(listOf("/x"), OnlineSubtitleMemory.of(entries, "ep2").map { it.path })
    }
}
