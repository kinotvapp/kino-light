package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsTest {
    private fun manifest(extra: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("id", "demo").put("name", "Demo").put("version", "1.0.0").put("apiVersion", 1)
        .put("entry", "plugin.js").put("hosts", JSONArray(listOf("example.com")))
        .put("capabilities", JSONArray(listOf("search", "resolve")))
        .apply(extra).toString()

    private fun settings(vararg s: String) = JSONArray("[" + s.joinToString(",") + "]")

    private fun invalid(json: String): ManifestResult.Invalid = ManifestParser.parse(json) as ManifestResult.Invalid
    private fun valid(json: String): PluginManifest = (ManifestParser.parse(json) as ManifestResult.Valid).manifest

    @Test fun `a manifest without settings or permissions is unchanged`() {
        val m = valid(manifest())
        assertEquals(emptyList<PluginSetting>(), m.settings)
        assertEquals(emptyList<String>(), m.permissions)
    }

    @Test fun `browse is a known capability`() {
        assertEquals(setOf("search", "browse", "resolve"), valid(manifest { put("capabilities", JSONArray(listOf("search", "browse", "resolve"))) }).capabilities)
    }

    @Test fun `every permission is refused in SDK v1 with its name`() {
        val r = invalid(manifest { put("permissions", JSONArray(listOf("local-network"))) })
        assertEquals("permissions", r.field)
        assertEquals("permiso desconocido: local-network", r.message)
        assertEquals("permissions", invalid(manifest { put("permissions", "local-network") }).field)
        assertEquals(emptyList<String>(), valid(manifest { put("permissions", JSONArray()) }).permissions)
    }

    @Test fun `the spec example parses with defaults applied`() {
        val m = valid(manifest {
            put("settings", settings(
                """{"key":"server","label":"Servidor","type":"url","required":true,"hint":"http://192.168.1.10:8096"}""",
                """{"key":"user","label":"Usuario","type":"text","required":true}""",
                """{"key":"password","label":"Contraseña","type":"password","required":true}""",
                """{"key":"hd","label":"Solo HD","type":"toggle","default":false}""",
                """{"key":"quality","label":"Calidad","type":"select","default":"auto","options":[{"value":"auto","label":"Automática"},{"value":"720","label":"720p"}]}""",
            ))
        })
        assertEquals(listOf("server", "user", "password", "hd", "quality"), m.settings.map { it.key })
        assertEquals(SettingType.URL, m.settings[0].type)
        assertTrue(m.settings[0].required)
        assertEquals("http://192.168.1.10:8096", m.settings[0].hint)
        assertEquals(false, m.settings[3].default)
        assertEquals("auto", m.settings[4].default)
        assertEquals(listOf(SettingOption("auto", "Automática"), SettingOption("720", "720p")), m.settings[4].options)
    }

    @Test fun `a toggle defaults to false and a select to its first option`() {
        val m = valid(manifest {
            put("settings", settings(
                """{"key":"t","label":"T","type":"toggle"}""",
                """{"key":"s","label":"S","type":"select","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            ))
        })
        assertEquals(false, m.settings[0].default)
        assertEquals("a", m.settings[1].default)
    }

    @Test fun `each broken setting is refused with a Spanish reason`() {
        val cases = mapOf(
            """{"key":"Server","label":"x","type":"text"}""" to "clave inválida",
            """{"key":"a-b","label":"x","type":"text"}""" to "clave inválida",
            """{"key":"${"a".repeat(33)}","label":"x","type":"text"}""" to "clave inválida",
            """{"key":"k","label":"","type":"text"}""" to "necesita un nombre",
            """{"key":"k","label":"${"x".repeat(41)}","type":"text"}""" to "necesita un nombre",
            """{"key":"k","label":"x","type":"number"}""" to "tipo desconocido",
            """{"key":"k","label":"x","type":"text","hint":"${"h".repeat(81)}"}""" to "ayuda",
            """{"key":"k","label":"x","type":"toggle","required":true}""" to "no puede ser obligatorio",
            """{"key":"k","label":"x","type":"select","required":true,"options":[{"value":"a","label":"A"}]}""" to "no puede ser obligatorio",
            """{"key":"k","label":"x","type":"text","required":"yes"}""" to "true o false",
            """{"key":"k","label":"x","type":"select"}""" to "necesita opciones",
            """{"key":"k","label":"x","type":"select","options":[{"value":"a","label":"A"},{"value":"a","label":"B"}]}""" to "repite la opción",
            """{"key":"k","label":"x","type":"select","options":[{"value":"${"v".repeat(41)}","label":"A"}]}""" to "no es válida",
            """{"key":"k","label":"x","type":"toggle","default":"yes"}""" to "valor por defecto",
            """{"key":"k","label":"x","type":"select","default":"z","options":[{"value":"a","label":"A"}]}""" to "valor por defecto",
            """{"key":"k","label":"x","type":"url","default":"http://127.0.0.1/"}""" to "valor por defecto",
            """{"key":"k","label":"x","type":"text","default":"${"t".repeat(501)}"}""" to "valor por defecto",
            "42" to "no es válido",
        )
        cases.forEach { (setting, reason) ->
            val r = invalid(manifest { put("settings", settings(setting)) })
            assertEquals(setting, "settings", r.field)
            assertTrue("$setting -> ${r.message}", r.message.contains(reason))
        }
    }

    @Test fun `a url setting can't ship a default, only the person types a server`() {
        listOf("\"http://192.168.1.1\"", "\"https://example.com\"", "\"\"").forEach { value ->
            val setting = """{"key":"server","label":"Servidor","type":"url","default":$value}"""
            val r = invalid(manifest { put("settings", settings(setting)) })
            assertEquals(setting, "settings", r.field)
            assertEquals(setting, "El ajuste \"server\" de tipo url no puede tener valor por defecto: usa \"hint\"", r.message)
        }
        val m = valid(manifest { put("settings", settings("""{"key":"server","label":"Servidor","type":"url","hint":"http://192.168.1.10:8096"}""", """{"key":"server2","label":"Otro","type":"url","default":null}""")) })
        assertNull(m.settings[0].default)
        assertNull(m.settings[1].default)
    }

    @Test fun `twenty options pass, twenty-one don't`() {
        fun opts(n: Int) = (1..n).joinToString(",") { """{"value":"v$it","label":"L$it"}""" }
        valid(manifest { put("settings", settings("""{"key":"k","label":"x","type":"select","options":[${opts(20)}]}""")) })
        assertTrue(invalid(manifest { put("settings", settings("""{"key":"k","label":"x","type":"select","options":[${opts(21)}]}""")) }).message.contains("más de 20"))
    }

    @Test fun `duplicate keys, more than twelve settings and a non-list are refused`() {
        assertTrue(invalid(manifest { put("settings", settings("""{"key":"a","label":"x","type":"text"}""", """{"key":"a","label":"y","type":"text"}""")) }).message.contains("repetido"))
        val thirteen = (1..13).map { """{"key":"k$it","label":"x","type":"text"}""" }.toTypedArray()
        assertTrue(invalid(manifest { put("settings", settings(*thirteen)) }).message.contains("más de 12"))
        assertEquals("settings", invalid(manifest { put("settings", JSONObject()) }).field)
    }

    @Test fun `values are checked against the type and its length`() {
        val text = PluginSetting("t", "Usuario", SettingType.TEXT)
        val url = PluginSetting("u", "Servidor", SettingType.URL)
        val pass = PluginSetting("p", "Clave", SettingType.PASSWORD)
        val toggle = PluginSetting("h", "HD", SettingType.TOGGLE)
        val select = PluginSetting("q", "Calidad", SettingType.SELECT, options = listOf(SettingOption("a", "A")))
        assertNull(PluginSettings.validateValue(text, "x".repeat(500)))
        assertNotNull(PluginSettings.validateValue(text, "x".repeat(501)))
        assertNull(PluginSettings.validateValue(pass, "p".repeat(500)))
        assertNotNull(PluginSettings.validateValue(pass, "p".repeat(501)))
        assertNull(PluginSettings.validateValue(url, "http://192.168.1.10:8096"))
        assertNull(PluginSettings.validateValue(url, ""))
        assertNotNull(PluginSettings.validateValue(url, "no es una url"))
        assertNotNull(PluginSettings.validateValue(url, "https://" + "a".repeat(2048) + ".example"))
        assertNotNull(PluginSettings.validateValue(url, "http://127.0.0.1:8096"))
        assertNull(PluginSettings.validateValue(toggle, true))
        assertNotNull(PluginSettings.validateValue(toggle, "true"))
        assertNull(PluginSettings.validateValue(select, "a"))
        assertNotNull(PluginSettings.validateValue(select, "b"))
        assertNotNull(PluginSettings.validateValue(text, 5))
    }

    @Test fun `missing required settings are the blank ones`() {
        val s = listOf(
            PluginSetting("server", "Servidor", SettingType.URL, required = true),
            PluginSetting("user", "Usuario", SettingType.TEXT, required = true),
            PluginSetting("nick", "Apodo", SettingType.TEXT),
        )
        assertEquals(listOf("server", "user"), PluginSettings.missingRequired(s, emptyMap()).map { it.key })
        assertEquals(listOf("user"), PluginSettings.missingRequired(s, mapOf("server" to "http://10.0.0.2", "user" to "  ")).map { it.key })
        assertEquals(emptyList<PluginSetting>(), PluginSettings.missingRequired(s, mapOf("server" to "http://10.0.0.2", "user" to "ana")))
    }
}

class PluginHostsTest {
    private val url = PluginSetting("server", "Servidor", SettingType.URL)
    private val url2 = PluginSetting("mirror", "Espejo", SettingType.URL)
    private val text = PluginSetting("user", "Usuario", SettingType.TEXT)

    @Test fun `a typed server becomes a user host with its exact scheme, host and port`() {
        val hosts = PluginHosts.effective(listOf("api.example.com"), listOf(url, text), mapOf("server" to "http://192.168.1.10:8096/web", "user" to "http://evil.example"))
        assertEquals(listOf("api.example.com"), hosts.declared)
        assertEquals(listOf(UserHost("http", "192.168.1.10", 8096)), hosts.user)
        assertEquals(listOf("api.example.com", "http://192.168.1.10:8096"), hosts.labels)
        assertEquals(hosts.user[0], hosts.userHostFor("http://192.168.1.10:8096/Items?x=1".toHttpUrl()))
        assertNull(hosts.userHostFor("https://192.168.1.10:8096/".toHttpUrl()))
        assertNull(hosts.userHostFor("http://192.168.1.10:8097/".toHttpUrl()))
        assertNull(hosts.userHostFor("http://192.168.1.11:8096/".toHttpUrl()))
    }

    @Test fun `default ports are explicit and names stay names`() {
        assertEquals(UserHost("https", "jellyfin.example.org", 443), PluginHosts.userHostOf("https://Jellyfin.Example.org/"))
        assertEquals(UserHost("http", "nas.local", 80), PluginHosts.userHostOf("http://nas.local"))
        assertEquals(setOf("nas.local"), EffectiveHosts(emptyList(), listOf(UserHost("http", "nas.local", 80), UserHost("http", "10.0.0.2", 80))).userHostNames)
    }

    @Test fun `loopback, link-local, unspecified and localhost are never user hosts`() {
        listOf(
            "http://localhost:8096", "http://LOCALHOST", "http://app.localhost", "http://127.0.0.1", "http://127.8.9.10:80",
            "http://[::1]:8096", "http://169.254.1.1", "http://[fe80::1]", "http://0.0.0.0", "http://[::]",
            "http://[::ffff:127.0.0.1]", "ftp://192.168.1.1", "nada", "", "http://",
        ).forEach { assertNull(it, PluginHosts.userHostOf(it)) }
    }

    @Test fun `private LAN and public addresses are allowed`() {
        listOf("http://192.168.0.5", "http://10.0.2.2:8096", "http://172.16.4.4", "http://[fd00::1]:8920", "https://203.0.113.9", "http://nas.lan:32400")
            .forEach { assertNotNull(it, PluginHosts.userHostOf(it)) }
    }

    @Test fun `unset, blank and invalid url settings add nothing`() {
        assertEquals(emptyList<UserHost>(), PluginHosts.effective(listOf("a.example"), listOf(url, url2), mapOf("server" to "", "mirror" to "http://127.0.0.1")).user)
        assertEquals(1, PluginHosts.effective(emptyList(), listOf(url, url2), mapOf("server" to "http://10.0.0.2", "mirror" to "http://10.0.0.2/x")).user.size)
    }

    @Test fun `ip literals are recognised the way OkHttp stores them`() {
        assertTrue(PluginHosts.isIpLiteral("192.168.1.10"))
        assertTrue(PluginHosts.isIpLiteral("fd00::1"))
        assertFalse(PluginHosts.isIpLiteral("nas.local"))
        assertFalse(PluginHosts.isIpLiteral("2130706433"))
    }
}
