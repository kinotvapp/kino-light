package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    // Ids need 2+ chars (manifest rule), hence "pa", "pb"…
    private lateinit var store: PluginStore
    private lateinit var registry: PluginRegistry

    @Before fun setUp() {
        store = PluginStore(File(tmp.root, "plugins"), File(tmp.root, "plugin-data"))
        registry = PluginRegistry(store)
    }

    private fun install(id: String, name: String, record: InstalledRecord.() -> InstalledRecord = { this }) {
        val manifest = JSONObject().put("id", id).put("name", name).put("version", "1.0.0").put("apiVersion", 1)
            .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
            .put("capabilities", JSONArray(listOf("search", "resolve"))).toString()
        val script = "export async function search(){}".toByteArray()
        val staging = store.newStaging(id)
        store.writeFiles(staging, manifest, "plugin.js", script, null,
            InstalledRecord("o/$id", "1.0.0", sha256Hex(script), listOf("example.com"), 1L).record())
        store.commit(staging, id)
        registry.reload()
    }

    @Test fun `status follows the record`() {
        install("pa", "A")
        install("pb", "B") { copy(enabled = false) }
        install("pc", "C") { copy(unresponsive = true) }
        install("pd", "D") { copy(pendingVersion = "2.0.0") }
        install("pe", "E") { copy(damaged = true) }
        assertEquals(
            listOf(PluginStatus.ACTIVE, PluginStatus.DISABLED, PluginStatus.UNRESPONSIVE, PluginStatus.UPDATE_PENDING, PluginStatus.DAMAGED),
            registry.plugins.value.map { it.status },
        )
        assertEquals(listOf("pa", "pd"), registry.usable().map { it.id })
    }

    @Test fun `enabling clears no responde`() {
        install("pc", "C") { copy(unresponsive = true, enabled = false) }
        registry.setEnabled("pc", true)
        assertEquals(PluginStatus.ACTIVE, registry.find("pc")!!.status)
    }

    @Test fun `uninstall removes files and data but remembers the name`() {
        install("pa", "Archivo")
        File(store.dataDir("pa"), "storage.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        registry.uninstall("pa")
        assertNull(registry.find("pa"))
        assertFalse(File(tmp.root, "plugins/pa").exists())
        assertFalse(store.dataDir("pa").exists())
        assertEquals("Archivo", registry.nameOf("pa"))
    }

    @Test fun `access and the message the player shows`() {
        install("ok", "Bueno")
        install("off", "Apagado") { copy(enabled = false) }
        install("bad", "Roto") { copy(damaged = true) }
        install("gone", "Ido")
        registry.uninstall("gone")
        assertNull(registry.accessFor("ok").blockedMessage())
        assertEquals("Activa el plugin Apagado para ver esto", registry.accessFor("off").blockedMessage())
        assertEquals("El plugin Roto tiene archivos dañados, reinstálalo", registry.accessFor("bad").blockedMessage())
        assertEquals("Esto venía del plugin Ido, que ya no está instalado", registry.accessFor("gone").blockedMessage())
        assertTrue(registry.accessFor(null) is PluginAccess.Uninstalled)
    }
}
