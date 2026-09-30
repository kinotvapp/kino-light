package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTest {
    private fun base() = JSONObject()
        .put("id", "archive-org").put("name", "Internet Archive").put("version", "1.0.0")
        .put("apiVersion", 1).put("entry", "plugin.js")
        .put("description", "  Películas de dominio público  ").put("author", "lordmacu")
        .put("hosts", JSONArray(listOf("archive.org", "*.archive.org")))
        .put("capabilities", JSONArray(listOf("search", "home", "episodes", "resolve")))
        .put("color", "#e0a030").put("icon", "icon.png")

    private fun invalidField(json: JSONObject): String =
        (ManifestParser.parse(json.toString()) as ManifestResult.Invalid).field

    @Test fun `a complete manifest is valid and normalized`() {
        val m = (ManifestParser.parse(base().toString()) as ManifestResult.Valid).manifest
        assertEquals("archive-org", m.id)
        assertEquals("Películas de dominio público", m.description)
        assertEquals("#E0A030", m.color)
        assertEquals(setOf("search", "home", "episodes", "resolve"), m.capabilities)
    }

    @Test fun `id rules`() {
        listOf("A", "a", "-abc", "has_underscore", "x".repeat(41), "magis", "ditu", "live", "local", "unknown", "plugin", "own")
            .forEach { assertEquals(it, "id", invalidField(base().put("id", it))) }
    }

    @Test fun `the reserved id of the built-in live provider is the one OwnLive uses`() {
        assertEquals("own", com.arkiv.player.data.live.OwnLive.PLUGIN_ID)
        assertEquals("id", invalidField(base().put("id", com.arkiv.player.data.live.OwnLive.PLUGIN_ID)))
    }

    @Test fun `name must be 1 to 40 chars`() {
        assertEquals("name", invalidField(base().put("name", "   ")))
        assertEquals("name", invalidField(base().put("name", "x".repeat(41))))
    }

    @Test fun `version must be semver`() = assertEquals("version", invalidField(base().put("version", "1.0")))

    @Test fun `apiVersion above the supported one says Kino must be updated`() {
        val r = ManifestParser.parse(base().put("apiVersion", 5).toString()) as ManifestResult.Invalid
        assertEquals("apiVersion", r.field)
        assertEquals("Este plugin necesita una versión más nueva de Kino", r.message)
        assertEquals("apiVersion", invalidField(base().put("apiVersion", "1")))
        assertEquals("apiVersion", invalidField(base().put("apiVersion", 0)))
    }

    @Test fun `channels needs apiVersion 3`() {
        val caps = JSONArray(listOf("home", "resolve", "channels"))
        val v1 = ManifestParser.parse(base().put("capabilities", caps).toString()) as ManifestResult.Invalid
        assertEquals("capabilities", v1.field)
        assertEquals("Esta capacidad necesita apiVersion 3", v1.message)
        val v2 = ManifestParser.parse(base().put("apiVersion", 2).put("capabilities", caps).toString()) as ManifestResult.Invalid
        assertEquals("Esta capacidad necesita apiVersion 3", v2.message)
        val v3 = (ManifestParser.parse(base().put("apiVersion", 3).put("capabilities", caps).toString()) as ManifestResult.Valid).manifest
        assertEquals(3, v3.apiVersion)
        assertTrue("channels" in v3.capabilities)
    }

    @Test fun `v1 keeps the exact round-2 refusals and v3 keeps every v2 declaration`() {
        val download = ManifestParser.parse(base().put("capabilities", JSONArray(listOf("search", "resolve", "download"))).toString()) as ManifestResult.Invalid
        assertEquals("Esta capacidad necesita apiVersion 2", download.message)
        val insecure = JSONObject().put("host", "x.example.com").put("insecureHttp", true)
        val host = ManifestParser.parse(base().put("hosts", JSONArray(listOf(insecure))).toString()) as ManifestResult.Invalid
        assertEquals("Un host con \"insecureHttp\" necesita apiVersion 2", host.message)
        val v3 = ManifestParser.parse(
            base().put("apiVersion", 3).put("hosts", JSONArray(listOf(insecure)))
                .put("capabilities", JSONArray(listOf("search", "resolve", "download", "drm", "channels"))).toString(),
        ) as ManifestResult.Valid
        assertEquals(setOf("x.example.com"), v3.manifest.insecureHosts)
    }

    @Test fun `a channels-only manifest still needs search or home`() {
        val r = ManifestParser.parse(base().put("apiVersion", 3).put("capabilities", JSONArray(listOf("resolve", "channels"))).toString()) as ManifestResult.Invalid
        assertEquals("El plugin debe declarar \"search\" o \"home\"", r.message)
    }

    @Test fun `the exports each capability needs`() {
        assertEquals(setOf("search", "resolve"), ManifestParser.requiredExports(setOf("search", "resolve", "download", "drm")))
        assertEquals(
            setOf("home", "resolve", "liveCategories", "liveChannels"),
            ManifestParser.requiredExports(setOf("home", "resolve", "channels")),
        )
    }

    @Test fun `entry must be a safe relative js path`() {
        listOf("../plugin.js", "/plugin.js", "plugin.mjs", "a/../b.js", "", "dir\\p.js")
            .forEach { assertEquals(it, "entry", invalidField(base().put("entry", it))) }
        assertTrue(ManifestParser.parse(base().put("entry", "dist/plugin.js").toString()) is ManifestResult.Valid)
    }

    @Test fun `hosts rules`() {
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray())))
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray(listOf("*")))))
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray(listOf("localhost")))))
        assertEquals("hosts", invalidField(base().put("hosts", JSONArray((1..21).map { "h$it.example.com" }))))
        assertEquals("hosts", invalidField(base().apply { remove("hosts") }))
    }

    private val serverSetting = JSONObject().put("key", "server").put("label", "Servidor").put("type", "url").put("required", true)

    /** A plugin whose only reach is the server the person types needs no placeholder host (apiVersion 2). */
    @Test fun `empty hosts are valid on apiVersion 2 with a url setting, and nowhere else`() {
        val ok = ManifestParser.parse(
            base().put("apiVersion", 2).put("hosts", JSONArray()).put("settings", JSONArray(listOf(serverSetting))).toString(),
        ) as ManifestResult.Valid
        assertEquals(emptyList<String>(), ok.manifest.hosts)

        // apiVersion 1: refused exactly as always, with or without the setting.
        val v1 = ManifestParser.parse(base().put("hosts", JSONArray()).put("settings", JSONArray(listOf(serverSetting))).toString()) as ManifestResult.Invalid
        assertEquals("hosts", v1.field)
        assertEquals("El campo \"hosts\" debe tener de 1 a 20 dominios", v1.message)

        // apiVersion 2 with no url setting: it could reach nothing at all.
        val text = JSONObject().put("key", "user").put("label", "Usuario").put("type", "text")
        val none = ManifestParser.parse(base().put("apiVersion", 2).put("hosts", JSONArray()).put("settings", JSONArray(listOf(text))).toString()) as ManifestResult.Invalid
        assertEquals("hosts", none.field)
        assertEquals(ManifestParser.NO_HOSTS_NEEDS_URL_SETTING, none.message)
        assertEquals("hosts", invalidField(base().put("apiVersion", 2).put("hosts", JSONArray())))
    }

    @Test fun `capabilities rules`() {
        assertEquals("capabilities", invalidField(base().put("capabilities", JSONArray(listOf("search")))))
        assertEquals("capabilities", invalidField(base().put("capabilities", JSONArray(listOf("resolve", "episodes")))))
        assertEquals("capabilities", invalidField(base().put("capabilities", JSONArray(listOf("resolve", "search", "download")))))
        assertTrue(ManifestParser.parse(base().put("capabilities", JSONArray(listOf("home", "resolve"))).toString()) is ManifestResult.Valid)
    }

    @Test fun `download and drm need apiVersion 2`() {
        val downloadV1 = ManifestParser.parse(base().put("capabilities", JSONArray(listOf("search", "resolve", "download"))).toString()) as ManifestResult.Invalid
        assertEquals("capabilities", downloadV1.field)
        assertEquals("Esta capacidad necesita apiVersion 2", downloadV1.message)
        val drmV1 = ManifestParser.parse(base().put("capabilities", JSONArray(listOf("search", "resolve", "drm"))).toString()) as ManifestResult.Invalid
        assertEquals("Esta capacidad necesita apiVersion 2", drmV1.message)

        val m = (
            ManifestParser.parse(
                base().put("apiVersion", 2).put("capabilities", JSONArray(listOf("search", "resolve", "download", "drm"))).toString(),
            ) as ManifestResult.Valid
        ).manifest
        assertEquals(setOf("search", "resolve", "download", "drm"), m.capabilities)
        assertEquals(2, m.apiVersion)
    }

    @Test fun `an insecureHttp host object needs apiVersion 2, and never a wildcard or a private-LAN suffix`() {
        val insecure = JSONObject().put("host", "x.example.com").put("insecureHttp", true)

        val v1 = ManifestParser.parse(base().put("hosts", JSONArray(listOf(insecure))).toString()) as ManifestResult.Invalid
        assertEquals("hosts", v1.field)

        val wildcard = JSONObject().put("host", "*.example.com").put("insecureHttp", true)
        val wc = ManifestParser.parse(base().put("apiVersion", 2).put("hosts", JSONArray(listOf(wildcard))).toString()) as ManifestResult.Invalid
        assertEquals("hosts", wc.field)

        val lan = JSONObject().put("host", "nas.local").put("insecureHttp", true)
        val laninv = ManifestParser.parse(base().put("apiVersion", 2).put("hosts", JSONArray(listOf(lan))).toString()) as ManifestResult.Invalid
        assertEquals("hosts", laninv.field)

        val m = (
            ManifestParser.parse(base().put("apiVersion", 2).put("hosts", JSONArray(listOf("archive.org", insecure))).toString()) as ManifestResult.Valid
        ).manifest
        assertEquals(listOf("archive.org", "x.example.com"), m.hosts)
        assertEquals(setOf("x.example.com"), m.insecureHosts)
    }

    @Test fun `a single placeholder host with all five capabilities validates (Xuper's shape)`() {
        // Confirms the existing schema needs no change for the Xuper plugin: one host (MIN_HOSTS
        // is 1, no real Magis domain needed) and all five capabilities together.
        val m = (
            ManifestParser.parse(
                base()
                    .put("id", "xuper").put("name", "Xuper")
                    .put("hosts", JSONArray(listOf("kino-plugin-xuper.example")))
                    .put("capabilities", JSONArray(listOf("search", "home", "browse", "episodes", "resolve")))
                    .toString(),
            ) as ManifestResult.Valid
        ).manifest
        assertEquals(listOf("kino-plugin-xuper.example"), m.hosts)
        assertEquals(setOf("search", "home", "browse", "episodes", "resolve"), m.capabilities)
    }

    @Test fun `color and icon are optional but checked`() {
        assertEquals("color", invalidField(base().put("color", "orange")))
        assertEquals("icon", invalidField(base().put("icon", "../icon.png")))
        val m = (ManifestParser.parse(base().apply { remove("color"); remove("icon") }.toString()) as ManifestResult.Valid).manifest
        assertEquals(null, m.color)
        assertEquals(null, m.icon)
    }

    @Test fun `not json and oversized are refused`() {
        assertEquals("kino-plugin.json", (ManifestParser.parse("{nope") as ManifestResult.Invalid).field)
        assertEquals("kino-plugin.json", invalidField(base().put("description", "x".repeat(17_000))))
    }

    @Test fun `streamHosts any needs apiVersion 4, no capability, and only the value any`() {
        val ok = (ManifestParser.parse(base().put("apiVersion", 4).put("streamHosts", "any").toString()) as ManifestResult.Valid).manifest
        assertTrue(ok.streamHostsAny)
        for (api in 1..3) {
            val old = (ManifestParser.parse(base().put("apiVersion", api).put("streamHosts", "any").toString()) as ManifestResult.Valid).manifest
            assertEquals(false, old.streamHostsAny)
        }
        val other = ManifestParser.parse(base().put("apiVersion", 4).put("streamHosts", "all").toString()) as ManifestResult.Invalid
        assertEquals("El campo \"streamHosts\" solo admite \"any\"", other.message)
        assertEquals("streamHosts", other.field)
        assertEquals(false, (ManifestParser.parse(base().put("apiVersion", 4).toString()) as ManifestResult.Valid).manifest.streamHostsAny)
    }

    @Test fun `liveStreamHosts any needs apiVersion 3 and the channels capability`() {
        val caps = JSONArray(listOf("home", "resolve", "channels"))
        val ok = (ManifestParser.parse(base().put("apiVersion", 3).put("capabilities", caps).put("liveStreamHosts", "any").toString()) as ManifestResult.Valid).manifest
        assertTrue(ok.liveStreamHostsAny)
        // v1/v2 are byte-for-byte as before: an unknown field there is ignored, never refused nor honoured.
        listOf<Any>("any", "all", true).forEach { v ->
            listOf(1, 2).forEach { api ->
                val old = (ManifestParser.parse(base().put("apiVersion", api).put("liveStreamHosts", v).toString()) as ManifestResult.Valid).manifest
                assertEquals(false, old.liveStreamHostsAny)
            }
        }
        val noChannels = ManifestParser.parse(base().put("apiVersion", 3).put("liveStreamHosts", "any").toString()) as ManifestResult.Invalid
        assertEquals("\"liveStreamHosts\" necesita la capacidad \"channels\"", noChannels.message)
        val other = ManifestParser.parse(base().put("apiVersion", 3).put("capabilities", caps).put("liveStreamHosts", "all").toString()) as ManifestResult.Invalid
        assertEquals("El campo \"liveStreamHosts\" solo admite \"any\"", other.message)
        assertEquals("liveStreamHosts", other.field)
        // Not a string at all (true, a list) is the same refusal, never a silent "any".
        listOf<Any>(true, JSONArray(listOf("any"))).forEach { v ->
            val bad = ManifestParser.parse(base().put("apiVersion", 3).put("capabilities", caps).put("liveStreamHosts", v).toString()) as ManifestResult.Invalid
            assertEquals("El campo \"liveStreamHosts\" solo admite \"any\"", bad.message)
        }
        assertEquals(false, (ManifestParser.parse(base().toString()) as ManifestResult.Valid).manifest.liveStreamHostsAny)
    }

    @Test fun `discoverable is an optional boolean at every apiVersion, true by default`() {
        assertTrue((ManifestParser.parse(base().toString()) as ManifestResult.Valid).manifest.discoverable)
        for (api in 1..ManifestParser.SUPPORTED_API) {
            val m = (ManifestParser.parse(base().put("apiVersion", api).put("discoverable", false).toString()) as ManifestResult.Valid).manifest
            assertEquals(false, m.discoverable)
            val t = (ManifestParser.parse(base().put("apiVersion", api).put("discoverable", true).toString()) as ManifestResult.Valid).manifest
            assertEquals(true, t.discoverable)
        }
        listOf<Any>("no", 0, JSONObject.NULL, JSONArray()).forEach { value ->
            val r = ManifestParser.parse(base().put("discoverable", value).toString()) as ManifestResult.Invalid
            assertEquals("discoverable", r.field)
            assertEquals("El campo \"discoverable\" debe ser true o false", r.message)
        }
    }

    @Test fun `secrets parse with apiVersion 4`() {
        val seal = TestSealing.seal("v", "owner/repo", "apiKey")
        val secrets = JSONObject().put("apiKey", seal)
        val m = (ManifestParser.parse(base().put("apiVersion", 4).put("secrets", secrets).toString()) as ManifestResult.Valid).manifest
        assertEquals(mapOf("apiKey" to seal), m.secrets)
    }

    @Test fun `secrets are ignored below apiVersion 4`() {
        val seal = TestSealing.seal("v", "owner/repo", "apiKey")
        val secrets = JSONObject().put("apiKey", seal)
        for (api in 1..3) {
            val m = (ManifestParser.parse(base().put("apiVersion", api).put("secrets", secrets).toString()) as ManifestResult.Valid).manifest
            assertEquals(emptyMap<String, String>(), m.secrets)
        }
    }

    @Test fun `malformed seals are invalid manifests`() {
        listOf(
            "kino-sealed:v2:" + TestSealing.seal("v", "owner/repo", "apiKey").substringAfter(SealedSecrets.PREFIX_V1),
            SealedSecrets.PREFIX_V1 + "@@@@",
            SealedSecrets.PREFIX_V1 + "AAAA",
        ).forEach { badSeal ->
            val secrets = JSONObject().put("apiKey", badSeal)
            val r = ManifestParser.parse(base().put("apiVersion", 4).put("secrets", secrets).toString()) as ManifestResult.Invalid
            assertEquals("secrets", r.field)
            assertEquals("El secreto \"apiKey\" no es un sello de Kino válido", r.message)
        }
    }

    @Test fun `bad names and too many secrets are invalid`() {
        val seal = TestSealing.seal("v", "owner/repo", "x")

        val badName = JSONObject().put("2x", seal)
        val r1 = ManifestParser.parse(base().put("apiVersion", 4).put("secrets", badName).toString()) as ManifestResult.Invalid
        assertEquals("secrets", r1.field)
        assertEquals("El secreto \"2x\" tiene un nombre inválido", r1.message)

        val many = JSONObject()
        for (i in 1..17) many.put("s$i", seal)
        val r2 = ManifestParser.parse(base().put("apiVersion", 4).put("secrets", many).toString()) as ManifestResult.Invalid
        assertEquals("secrets", r2.field)
        assertEquals("El campo \"secrets\" admite hasta 16 secretos", r2.message)

        val r3 = ManifestParser.parse(base().put("apiVersion", 4).put("secrets", JSONArray()).toString()) as ManifestResult.Invalid
        assertEquals("secrets", r3.field)
        assertEquals("El campo \"secrets\" debe ser un objeto", r3.message)
    }
}
