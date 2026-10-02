package com.arkiv.player.data.subtitles

import com.arkiv.player.data.plugin.SecretStore
import com.arkiv.player.playback.TrackLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key precedence, provider settings, languages, ordering and the names the person reads. */
class OnlineSubtitleRulesTest {

    private class MemorySecrets : SecretStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `the person's key wins over Kino's shared one, and neither is none`() {
        assertEquals(EffectiveKey(ProviderAuth("mine"), KeySource.USER), KeyPrecedence.effective(" mine ", "shared"))
        assertEquals(EffectiveKey(ProviderAuth("shared"), KeySource.SHARED), KeyPrecedence.effective("  ", "shared"))
        assertEquals(EffectiveKey(ProviderAuth("shared"), KeySource.SHARED), KeyPrecedence.effective(null, "shared"))
        assertNull(KeyPrecedence.effective("", ""))
        assertNull(KeyPrecedence.effective(null, null))
        assertEquals("ana", KeyPrecedence.effective("k", null, " ana ", "p")!!.auth.username)
    }

    @Test
    fun `the keys store uses the precedence, per provider and only enabled ones`() {
        val secrets = MemorySecrets()
        val shared = mutableMapOf(SubtitleProviderId.OPENSUBTITLES to "kino-os", SubtitleProviderId.SUBDL to "")
        val keys = SubtitleKeys(secrets, { shared[it] }, null)
        assertEquals(listOf(SubtitleProviderId.OPENSUBTITLES), keys.usable().map { it.first })
        keys.setUserKey(SubtitleProviderId.SUBDL, " my-subdl ")
        assertEquals(KeySource.USER, keys.effective(SubtitleProviderId.SUBDL)!!.source)
        assertEquals("my-subdl", keys.userKey(SubtitleProviderId.SUBDL))
        keys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "my-os")
        keys.setAccount("ana", "pw")
        assertEquals(ProviderAuth("my-os", "ana", "pw"), keys.effective(SubtitleProviderId.OPENSUBTITLES)!!.auth)
        // Clearing the person's key falls back to Kino's.
        keys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "")
        assertEquals(KeySource.SHARED, keys.effective(SubtitleProviderId.OPENSUBTITLES)!!.source)
        keys.setProviders(keys.providers.value.toggled(SubtitleProviderId.OPENSUBTITLES))
        assertEquals(listOf(SubtitleProviderId.SUBDL), keys.usable().map { it.first })
        assertTrue(secrets.map.keys.all { it.startsWith("subtitles.") })
    }

    @Test
    fun `provider settings survive a round trip and pick up a new provider`() {
        val s = ProviderSettings().movedUp(SubtitleProviderId.SUBDL).toggled(SubtitleProviderId.OPENSUBTITLES)
        assertEquals(listOf(SubtitleProviderId.SUBDL, SubtitleProviderId.OPENSUBTITLES), s.order)
        assertEquals(listOf(SubtitleProviderId.SUBDL), s.enabledInOrder)
        assertEquals(s, ProviderSettings.decode(s.encode()))
        assertEquals(ProviderSettings(), ProviderSettings.decode(null))
        assertEquals(SubtitleProviderId.entries.toList(), ProviderSettings.decode("SUBDL;").order.sorted())
        assertEquals(s.order, ProviderSettings.decode("SUBDL,GONE;OPENSUBTITLES").order)
    }

    @Test
    fun `languages come from the subtitle order, Spanish always and English as fallback`() {
        assertEquals(listOf("es", "en"), OnlineSubtitleRules.languagesFor(listOf(TrackLang.LATINO, TrackLang.CASTELLANO)))
        assertEquals(listOf("en", "ja", "es"), OnlineSubtitleRules.languagesFor(listOf(TrackLang.ENGLISH, TrackLang.JAPANESE)))
    }

    @Test
    fun `results go preferred language first, then most downloaded, capped`() {
        fun s(lang: String, dl: Int) = OnlineSubtitle(SubtitleProviderId.SUBDL, "$lang$dl", lang, "", dl)
        val sorted = OnlineSubtitleRules.sort(listOf(s("en", 900), s("es", 10), s("fr", 5000), s("es", 300)), listOf("es", "en"), cap = 3)
        assertEquals(listOf("es300", "es10", "en900"), sorted.map { it.ref })
    }

    @Test
    fun `names, labels, ids and the masked key`() {
        assertEquals("Español", OnlineSubtitleRules.languageName("es"))
        assertEquals("Inglés", OnlineSubtitleRules.languageName("EN"))
        assertEquals(
            "Español · OpenSubtitles · The.Matrix.1999.1080p.BluRay",
            OnlineSubtitleRules.trackLabel(OnlineSubtitle(SubtitleProviderId.OPENSUBTITLES, "1", "es", "The.Matrix.1999.1080p.BluRay.x264")),
        )
        assertEquals("tt0133093", OnlineSubtitleRules.imdbIn("web:series:tt0133093"))
        assertNull(OnlineSubtitleRules.imdbIn("plugin:abc:123"))
        assertEquals(1999, OnlineSubtitleRules.yearIn("Matrix (1999)"))
        assertEquals("Matrix", OnlineSubtitleRules.bareTitle("Matrix (1999)"))
        val masked = OnlineSubtitleRules.mask("abcdef123456")
        assertFalse(masked.contains("abcdef"))
        assertEquals("<none>", OnlineSubtitleRules.mask(""))
    }

    @Test
    fun `every failure reads as a Spanish sentence, the quota one as the owner asked`() {
        assertEquals("Límite diario de descargas alcanzado", SubtitleFailure.QUOTA.message)
        assertEquals(SubtitleFailure.QUOTA, SubtitleHttp.failureOf(406))
        assertEquals(SubtitleFailure.BAD_KEY, SubtitleHttp.failureOf(401))
        assertEquals(SubtitleFailure.BAD_KEY, SubtitleHttp.failureOf(403))
        assertEquals(SubtitleFailure.RATE_LIMITED, SubtitleHttp.failureOf(429))
        assertEquals(SubtitleFailure.UNAVAILABLE, SubtitleHttp.failureOf(503))
        assertTrue(SubtitleFailure.entries.all { it.message.isNotBlank() })
    }
}
