package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `docs/plugins/contract.json` is what authors, `sdk/validate.mjs`, `sdk/run.mjs` and the guide's
 * tables read; this pins every value to the Kotlin constant that enforces it. A change on one side
 * only fails here. (The guide and the code drifted apart once already.)
 */
class PluginContractParityTest {
    private val c = JSONObject(File("../docs/plugins/contract.json").readText())

    private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun obj(path: String): JSONObject = path.split('.').fold(c) { o, k -> o.getJSONObject(k) }

    @Test fun `api version and capabilities`() {
        assertEquals(ManifestParser.SUPPORTED_API, c.getInt("apiVersion"))
        assertEquals(ManifestParser.SUPPORTED_API, c.getInt("maxApiVersion"))
        assertEquals(ManifestParser.CAPABILITIES, obj("capabilities").strings("names").toSet())
        assertEquals(ManifestParser.REQUIRED_CAPABILITIES, obj("capabilities").strings("required"))
        assertEquals(ManifestParser.AT_LEAST_ONE_OF_CAPABILITIES, obj("capabilities").strings("atLeastOneOf"))
    }

    @Test fun `manifest rules`() {
        val m = obj("manifest")
        assertEquals(ManifestParser.MAX_BYTES, m.getInt("maxBytes"))
        assertEquals(ManifestParser.RESERVED_IDS, m.strings("reservedIds").toSet())
        assertEquals(ManifestParser.ID.pattern, m.getString("idPattern"))
        assertEquals(ManifestParser.MAX_NAME_CHARS, m.getInt("nameMaxChars"))
        assertEquals(ManifestParser.MAX_DESCRIPTION_CHARS, m.getInt("descriptionMaxChars"))
        assertEquals(ManifestParser.MAX_AUTHOR_CHARS, m.getInt("authorMaxChars"))
        assertEquals(ManifestParser.MAX_HOMEPAGE_CHARS, m.getInt("homepageMaxChars"))
        assertEquals(ManifestParser.MIN_HOSTS, m.getInt("minHosts"))
        assertEquals(ManifestParser.MAX_HOSTS, m.getInt("maxHosts"))
        assertEquals(ManifestParser.COLOR.pattern, m.getString("colorPattern"))
        assertEquals(PluginInstaller.MAX_SCRIPT_BYTES, m.getInt("entryMaxBytes"))
        assertEquals(PluginInstaller.MAX_ICON_BYTES, m.getInt("iconMaxBytes"))
    }

    @Test fun `permissions and settings`() {
        assertEquals(PluginSettings.PERMISSIONS, c.strings("permissions").toSet())
        assertEquals(JSONArray::class, c.get("permissions")::class)
        val s = obj("settings")
        assertEquals(PluginSettings.MAX_SETTINGS, s.getInt("max"))
        assertEquals(PluginSettings.KEY.pattern, s.getString("keyPattern"))
        assertEquals(PluginSettings.MAX_LABEL_CHARS, s.getInt("labelMaxChars"))
        assertEquals(PluginSettings.MAX_HINT_CHARS, s.getInt("hintMaxChars"))
        assertEquals(PluginSettings.MAX_OPTIONS, s.getInt("maxOptions"))
        assertEquals(PluginSettings.MAX_OPTION_VALUE_CHARS, s.getInt("optionValueMaxChars"))
        assertEquals(PluginSettings.MAX_OPTION_LABEL_CHARS, s.getInt("optionLabelMaxChars"))
        val types = s.getJSONObject("types")
        assertEquals(SettingType.entries.map { it.wire }.toSet(), types.keySet())
        SettingType.entries.forEach { t ->
            val o = types.getJSONObject(t.wire)
            assertEquals(t.wire, t.canBeRequired, o.getBoolean("canBeRequired"))
            if (t.maxChars > 0) assertEquals(t.wire, t.maxChars, o.getInt("maxChars")) else assertEquals(t.wire, false, o.has("maxChars"))
        }
    }

    @Test fun `runtime, storage, sleep and errors`() {
        val r = obj("runtime")
        val env = PluginEnv(appVersion = "x")
        assertEquals(env.memoryLimitBytes, r.getLong("memoryBytes"))
        assertEquals(env.maxStackBytes, r.getLong("stackBytes"))
        assertEquals(PluginRuntime.MAX_LOG_CHARS, r.getInt("maxLogChars"))
        assertEquals(PluginRuntime.MAX_ERROR_CHARS, r.getInt("maxErrorChars"))
        assertEquals(PluginRuntimePool.DEFAULT_IDLE_MS, r.getLong("idleCloseMs"))
        assertEquals(PluginRuntimePool.DEFAULT_MAX_TIMEOUTS, r.getInt("timeoutsBeforeUnresponsive"))
        assertEquals(PluginStorage.MAX_BYTES, obj("storage").getInt("maxTotalBytes"))
        assertEquals(PluginRuntime.MAX_SLEEP_MS, obj("sleep").getInt("maxMs"))
        assertEquals(PluginErrors.CODES, obj("errors").strings("codes"))
        assertEquals(PluginErrors.MAX_MESSAGE_CHARS, obj("errors").getInt("maxMessageChars"))
    }

    @Test fun `crypto`() {
        val k = obj("crypto")
        assertEquals(PluginCrypto.HASHES, k.strings("hashes"))
        assertEquals(PluginCrypto.CIPHERS, k.strings("ciphers"))
        assertEquals(PluginCrypto.ENCODINGS, k.strings("encodings"))
        assertEquals(PluginCrypto.PBKDF2_HASHES, k.strings("pbkdf2Hashes"))
        assertEquals(PluginCrypto.PBKDF2_MAX_ITERATIONS, k.getInt("pbkdf2MaxIterations"))
        assertEquals(PluginCrypto.PBKDF2_MAX_KEY_BYTES, k.getInt("pbkdf2MaxKeyBytes"))
        assertEquals(PluginCrypto.RANDOM_MAX_BYTES, k.getInt("randomMaxBytes"))
        assertEquals(PluginCrypto.MAX_DATA_BYTES, k.getInt("maxDataBytes"))
        assertEquals(PluginCrypto.ERROR_CODE, k.getString("errorCode"))
    }

    // Each later task adds the contract section it enforces above this line.
}
