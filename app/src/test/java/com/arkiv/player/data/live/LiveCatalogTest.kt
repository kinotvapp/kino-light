package com.arkiv.player.data.live

import com.arkiv.player.data.gateway.LiveChannel
import com.arkiv.player.data.gateway.LiveProgram
import com.arkiv.player.data.plugin.InstalledPlugin
import com.arkiv.player.data.plugin.InstalledRecord
import com.arkiv.player.data.plugin.PluginManifest
import com.arkiv.player.data.plugin.PluginsPlace
import com.arkiv.player.data.plugin.XuperPrivilege
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCatalogTest {
    private fun plugin(
        id: String, api: Int = 3, caps: Set<String> = setOf("home", "resolve", "channels"), address: String = "o/$id",
        enabled: Boolean = true, unresponsive: Boolean = false, damaged: Boolean = false,
        missing: List<String> = emptyList(), name: String = id.uppercase(), version: String = "1.0.0",
    ) = InstalledPlugin(
        PluginManifest(id, name, version, api, "plugin.js", "", "", "", listOf("example.com"), caps, null, null),
        InstalledRecord(address, version, "sha", listOf("example.com"), 1L, enabled = enabled, unresponsive = unresponsive, damaged = damaged),
        null,
        missingSettings = missing,
    )

    private fun xuper(enabled: Boolean = true, caps: Set<String> = setOf("home", "resolve")) =
        plugin("xuper", api = 1, caps = caps, address = XuperPrivilege.SOURCE_REPO, enabled = enabled, name = "Xuper")

    private class Fake(
        override val id: String,
        override val notice: StateFlow<String?> = MutableStateFlow(null),
    ) : LiveChannelProvider {
        var closed = 0
        override val name = id
        override val color = 0L
        override fun initialCategory(): String? = null
        override fun hasGuide() = false
        override suspend fun categories(includeAdults: Boolean) = emptyList<ProviderCategory>()
        override suspend fun channels(categoryId: String, force: Boolean) = emptyList<LiveChannel>()
        override suspend fun guide(channels: List<LiveChannel>) = emptyMap<String, List<LiveProgram>>() to emptyList<String>()
        override suspend fun open(channel: LiveChannel): LiveOpening = LiveOpening.Proxied("x")
        override fun close() { closed++ }
    }

    @Test fun `xuper comes first, then every plugin that adds channels, in registry order`() {
        assertEquals(listOf("xuper", "plugin:tv1", "plugin:tv2", OwnLive.PROVIDER), liveProviderIds(listOf(plugin("tv1"), xuper(), plugin("tv2"))))
    }

    @Test fun `a plugin adds channels only while usable, configured, on apiVersion 3 and declaring channels`() {
        assertEquals(
            listOf(OwnLive.PROVIDER),
            liveProviderIds(
                listOf(
                    plugin("off", enabled = false), plugin("dmg", damaged = true), plugin("slow", unresponsive = true),
                    plugin("setup", missing = listOf("server")), plugin("old", api = 2, caps = setOf("home", "resolve")),
                    plugin("nolive", caps = setOf("home", "resolve")),
                ),
            ),
        )
    }

    @Test fun `the xuper install is never a plugin provider, and without it there is no xuper`() {
        assertEquals(listOf("xuper", OwnLive.PROVIDER), liveProviderIds(listOf(xuper(caps = setOf("home", "resolve", "channels")))))
        assertEquals(listOf("plugin:tv1", OwnLive.PROVIDER), liveProviderIds(listOf(xuper(enabled = false), plugin("tv1"))))
        assertEquals(listOf(OwnLive.PROVIDER), liveProviderIds(emptyList()))
    }

    @Test fun `blocked messages say what to do`() {
        assertEquals("Instala el plugin Xuper para ver este canal", liveBlockedMessage("xuper", emptyList()))
        assertEquals("Activa el plugin TV1 para ver este canal", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", enabled = false))))
        assertEquals("El plugin TV1 tiene archivos dañados, reinstálalo", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", damaged = true))))
        assertEquals("Configura TV1 en Ajustes ▸ Plugins", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", missing = listOf("server"))), PluginsPlace.TV))
        assertEquals("El plugin TV1 no responde ahora; revísalo en Ajustes ▸ Plugins", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", unresponsive = true)), PluginsPlace.TV))
        assertEquals("Configura TV1 en Menú ▸ Plugins", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", missing = listOf("server"))), PluginsPlace.PHONE))
        assertEquals("El plugin TV1 no responde ahora; revísalo en Menú ▸ Plugins", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", unresponsive = true)), PluginsPlace.PHONE))
        assertEquals("El plugin TV1 ya no ofrece canales en vivo", liveBlockedMessage("plugin:tv1", listOf(plugin("tv1", caps = setOf("home", "resolve")))))
        assertEquals("Este canal venía de un plugin que ya no está instalado", liveBlockedMessage("plugin:tv1", emptyList()))
    }

    @Test fun `providers follow the registry and keep their instance while the plugin is unchanged`() = runTest {
        val registry = MutableStateFlow(listOf(xuper(), plugin("tv1")))
        var built = 0
        val catalog = LiveCatalog(registry, backgroundScope, xuperProvider = { Fake("xuper") }, pluginProvider = { p -> built++; Fake("plugin:${p.id}") }, ownProvider = { Fake(OwnLive.PROVIDER) })
        assertEquals(listOf("xuper", "plugin:tv1", OwnLive.PROVIDER), catalog.providers.value.map { it.id })
        val first = catalog.provider("plugin:tv1")
        registry.value = listOf(plugin("tv1"))
        runCurrent()
        assertEquals(listOf("plugin:tv1", OwnLive.PROVIDER), catalog.providers.value.map { it.id })
        assertSame(first, catalog.provider("plugin:tv1"))
        assertEquals(1, built)
        assertEquals(listOf(LiveProviderTab("plugin:tv1", "plugin:tv1", 0L), LiveProviderTab(OwnLive.PROVIDER, OwnLive.PROVIDER, 0L)), catalog.tabs.value)
        assertTrue(catalog.available.value)
        registry.value = listOf(plugin("tv1", version = "1.1.0"))
        runCurrent()
        assertEquals(2, built)
        registry.value = emptyList()
        runCurrent()
        // The person's own channels are always there: En vivo stays reachable with nothing installed.
        assertTrue(catalog.available.value)
        assertEquals(listOf(OwnLive.PROVIDER), catalog.providers.value.map { it.id })
        assertEquals("Este canal venía de un plugin que ya no está instalado", catalog.blockedMessage("plugin:tv1"))
    }

    @Test fun `a replaced or dropped provider is closed once, a kept one never`() = runTest {
        val registry = MutableStateFlow(listOf(xuper(), plugin("tv1"), plugin("tv2")))
        val catalog = LiveCatalog(registry, backgroundScope, xuperProvider = { Fake("xuper") }, pluginProvider = { p -> Fake("plugin:${p.id}") }, ownProvider = { Fake(OwnLive.PROVIDER) })
        val xuperOne = catalog.provider("xuper") as Fake
        val tv1 = catalog.provider("plugin:tv1") as Fake
        val tv2 = catalog.provider("plugin:tv2") as Fake
        // A settings change (config revision) makes a new tv1; tv2 is disabled; xuper stays.
        registry.value = listOf(xuper(), plugin("tv1").copy(configRevision = 1), plugin("tv2", enabled = false))
        runCurrent()
        assertNotSame(tv1, catalog.provider("plugin:tv1"))
        assertEquals(1, tv1.closed)
        assertEquals(1, tv2.closed)
        assertEquals(0, xuperOne.closed)
        assertSame(xuperOne, catalog.provider("xuper"))
        // Mid-playback the channel's provider is gone: the player reads this message (R13).
        assertEquals("Activa el plugin TV2 para ver este canal", catalog.blockedMessage("plugin:tv2"))
        registry.value = emptyList()
        runCurrent()
        assertEquals(1, xuperOne.closed)
        assertEquals(1, tv1.closed)
        assertEquals(listOf(OwnLive.PROVIDER), catalog.providers.value.map { it.id })
    }

    @Test fun `forget closes a plugin's provider at once, before the registry drops it, and only once`() = runTest {
        val registry = MutableStateFlow(listOf(plugin("tv1"), plugin("tv2")))
        val catalog = LiveCatalog(registry, backgroundScope, xuperProvider = { Fake("xuper") }, pluginProvider = { p -> Fake("plugin:${p.id}") }, ownProvider = { Fake(OwnLive.PROVIDER) })
        val tv1 = catalog.provider("plugin:tv1") as Fake
        val tv2 = catalog.provider("plugin:tv2") as Fake
        catalog.forget("tv1")
        assertEquals(1, tv1.closed)
        assertEquals(0, tv2.closed)
        registry.value = listOf(plugin("tv2"))
        runCurrent()
        assertEquals(1, tv1.closed)
        assertEquals(listOf("plugin:tv2", OwnLive.PROVIDER), catalog.providers.value.map { it.id })
        catalog.forget("nothing")
    }

    @Test fun `only plugin providers have a notice`() = runTest {
        val catalog = LiveCatalog(MutableStateFlow(listOf(xuper())), backgroundScope, xuperProvider = { Fake("xuper", MutableStateFlow("x")) }, pluginProvider = { Fake("plugin:${it.id}") }, ownProvider = { Fake(OwnLive.PROVIDER) })
        assertEquals(null, catalog.noticeFor("tv1").first())
        assertEquals(null, catalog.noticeFor("xuper").first())
    }

    @Test fun `a plugin's notice follows its current provider, a replacement and its removal`() = runTest {
        val registry = MutableStateFlow(listOf(plugin("tv1")))
        val notices = ArrayDeque(listOf("Lista recortada: 5000 de 9000 canales", "Lista recortada: 5000 de 7000 canales"))
        val catalog = LiveCatalog(registry, backgroundScope, xuperProvider = { Fake("xuper") }, pluginProvider = { p -> Fake("plugin:${p.id}", MutableStateFlow(notices.removeFirst())) }, ownProvider = { Fake(OwnLive.PROVIDER) })
        val seen = mutableListOf<String?>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { catalog.noticeFor("tv1").collect { seen += it } }
        assertEquals(listOf<String?>("Lista recortada: 5000 de 9000 canales"), seen)
        registry.value = listOf(plugin("tv1", version = "1.1.0"))
        runCurrent()
        assertEquals("Lista recortada: 5000 de 7000 canales", seen.last())
        registry.value = emptyList()
        runCurrent()
        assertEquals(null, seen.last())
    }

    @Test fun `the own provider is always the last one, with no plugins at all`() {
        assertEquals(listOf(OwnLive.PROVIDER), liveProviderIds(emptyList()))
    }

    @Test fun `the own provider never says its plugin is not installed`() {
        assertEquals("Este canal no está disponible ahora", liveBlockedMessage(OwnLive.PROVIDER, emptyList()))
    }

    @Test fun `the catalog builds the own provider once and keeps the instance, and En vivo is reachable with nothing installed`() = runTest {
        var built = 0
        val registry = MutableStateFlow<List<InstalledPlugin>>(emptyList())
        val catalog = LiveCatalog(
            registry, backgroundScope,
            xuperProvider = { error("no xuper") }, pluginProvider = { error("no plugin") },
            ownProvider = { built++; Fake(OwnLive.PROVIDER) },
        )
        assertEquals(listOf(OwnLive.PROVIDER), catalog.providers.value.map { it.id })
        assertTrue(catalog.available.value)
        val first = catalog.providers.value.single()
        registry.value = listOf(plugin("tv1"))
        assertSame(first, catalog.providers.value.last())
        assertEquals(1, built)
    }
}
