package com.arkiv.player.data.sync

import com.arkiv.player.data.db.PluginInstallEntity
import com.arkiv.player.data.plugin.NuvioPluginConverter
import com.arkiv.player.data.plugin.sync.PluginReach
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PluginInstallMappersTest {
    private val base = PluginInstallEntity(
        id = "archive", address = "kinotvapp/kino-plugin-archive", name = "Internet Archive", version = "1.2.0",
        sha256 = "a".repeat(64), enabled = false,
        approvedJson = PluginReach(hosts = listOf("archive.org"), anyVideoHost = true).toJson().toString(),
        settingsJson = JSONObject().put("quality", "720").toString(), updatedAt = 42, deleted = false, secretsAt = 40,
    )

    private fun wire(change: JSONObject.() -> Unit = {}) = pluginInstallToJson(base).apply(change)

    @Test fun `a row survives the wire unchanged`() {
        val back = jsonToPluginInstall(wire())!!
        assertEquals(base.copy(approvedJson = back.approvedJson, settingsJson = back.settingsJson), back)
        assertEquals(PluginReach.fromJson(JSONObject(base.approvedJson)), PluginReach.fromJson(JSONObject(back.approvedJson)))
        assertEquals("720", JSONObject(back.settingsJson).getString("quality"))
    }

    @Test fun `reserved and malformed ids are refused`() {
        assertNull(jsonToPluginInstall(wire { put("id", "own") }))
        assertNull(jsonToPluginInstall(wire { put("id", "magis") }))
        assertNull(jsonToPluginInstall(wire { put("id", "../x") }))
        assertNull(jsonToPluginInstall(wire { put("id", "A") }))
    }

    @Test fun `only a canonical GitHub address is accepted`() {
        assertNull(jsonToPluginInstall(wire { put("address", "https://evil.example.com/x") }))
        assertNull(jsonToPluginInstall(wire { put("address", "https://github.com/kinotvapp/kino-plugin-archive") }))
        assertNull(jsonToPluginInstall(wire { put("address", "") }))
        assertNotNull(jsonToPluginInstall(wire { put("address", "kinotvapp/kino-plugin-archive@dev") }))
    }

    @Test fun `only the official repos may be xuper and they may be nothing else`() {
        assertNull(jsonToPluginInstall(wire { put("id", "xuper") }))
        assertNull(jsonToPluginInstall(wire { put("address", "xuper-plugin/kino-plugin-xuper") }))
        assertNotNull(jsonToPluginInstall(wire { put("id", "xuper"); put("address", "xuper-plugin/kino-plugin-xuper") }))
        assertNotNull(jsonToPluginInstall(wire { put("id", "xuper"); put("address", "kinotvapp/kino-plugin-xuper") }))
    }

    @Test fun `a nuvio row must carry the id its repo and scraper derive`() {
        val repo = "yoruix/nuvio-providers@main"
        val id = NuvioPluginConverter.idFor("showbox", repo)
        val ok = wire { put("id", id); put("address", repo); put("nuvioRepo", repo); put("nuvioScraperId", "showbox") }
        assertNotNull(jsonToPluginInstall(ok))
        assertNull(jsonToPluginInstall(wire { put("id", "archive"); put("address", repo); put("nuvioRepo", repo); put("nuvioScraperId", "showbox") }))
        assertNull(jsonToPluginInstall(wire { put("id", id); put("address", "kinotvapp/kino-plugin-archive"); put("nuvioRepo", repo); put("nuvioScraperId", "showbox") }))
        assertNull(jsonToPluginInstall(wire { put("id", id); put("address", repo); put("nuvioRepo", repo) }))
    }

    @Test fun `version, hash and name are kept short and plain`() {
        assertNull(jsonToPluginInstall(wire { put("version", "1".repeat(40)) }))
        assertNull(jsonToPluginInstall(wire { put("sha256", "zz") }))
        assertEquals(80, jsonToPluginInstall(wire { put("name", "n".repeat(500)) })!!.name.length)
        assertEquals("archive", jsonToPluginInstall(wire { put("name", "\u0000") })!!.name)
    }
}
