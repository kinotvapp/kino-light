package com.arkiv.player.data.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A plugin's settings as the plugin reads them (`kino.config`): defaults applied, unset keys absent. */
data class PluginConfig(val values: Map<String, Any>) {
    fun toJson(): String = JSONObject(values).toString()

    companion object {
        val EMPTY = PluginConfig(emptyMap())
    }
}

/** Where password values live; the app's is Keystore-backed (`EncryptedSecretStore`). Tests use a map. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * The person's answers to a plugin's `settings`. Non-secret values go to
 * `plugin-data/<id>/config.json`; `password` values to the [SecretStore] under
 * `plugin.<id>.<key>` — never to `installed.json`, never to a log. `config.json` also lists WHICH
 * passwords are set (names only), so "Falta configurar" is known without opening the Keystore.
 * Uninstall deletes both ([clear]).
 *
 * Every method does disk or Keystore IO: call it off the main thread.
 */
class PluginConfigStore(private val dataDir: (pluginId: String) -> File, private val secrets: SecretStore) {

    /** Every value with defaults applied; passwords included. */
    fun read(pluginId: String, settings: List<PluginSetting>): PluginConfig {
        val stored = readFile(pluginId)
        val out = LinkedHashMap<String, Any>()
        for (s in settings) {
            val value: Any? = when (s.type) {
                SettingType.PASSWORD -> if (s.key in stored.secrets) secrets.get(secretKey(pluginId, s.key)) else null
                else -> stored.values.opt(s.key)?.takeIf { it != JSONObject.NULL && PluginSettings.validateValue(s, it) == null }
            }
            val effective = value?.takeUnless { it is String && it.isEmpty() } ?: s.default
            if (effective != null) out[s.key] = effective
        }
        return PluginConfig(out)
    }

    /**
     * For the registry: typed servers, missing required settings and the save-revision, from
     * `config.json` alone — never the Keystore (see [missing]'s KDoc: fix round 2 moved the
     * Keystore-reading part of finding 5's fix out of this hot, thread-agnostic path entirely).
     * [PluginSetupState.revision] is what makes `reload()` produce a genuinely different
     * `InstalledPlugin` on every save (fix round 2, finding 4): it becomes part of that data
     * class, so a save that changes only the user/password — leaving hosts, `needsSetup` and every
     * other field bit-for-bit equal — still makes the registry's `StateFlow` emit for real, instead
     * of being silently dropped by its structural-equality dedup.
     */
    fun setupState(pluginId: String, settings: List<PluginSetting>): PluginSetupState {
        if (settings.isEmpty()) return PluginSetupState()
        val stored = readFile(pluginId)
        val userHosts = settings.filter { it.type == SettingType.URL }
            .mapNotNull { (stored.values.opt(it.key) as? String)?.let(PluginHosts::userHostOf) }
            .distinct()
        return PluginSetupState(userHosts, missing(pluginId, settings).map { it.key }, stored.revision)
    }

    /**
     * The required settings still without a value, from `config.json` ALONE — no Keystore access,
     * on purpose: this (via [setupState]) backs `PluginRegistry.reload()`, which fix round 1's own
     * version of this method made reachable from the Keystore on whatever thread called `reload()`
     * — including Main (`graph.pluginsChanged`/`graph.pluginRegistry`, read directly from
     * `viewModelFactory` initializers in HomeScreen/LibraryScreen/TvHomeScreen/PlayerScreen during
     * composition). Fix round 2 (new breakage 1) moved the LIVE Keystore check to [reconcileSecrets]
     * instead: a password `config.json` lists as set but the [SecretStore] can no longer actually
     * produce (a Keystore reset, a restored backup — see [EncryptedSecretStore]'s KDoc) is caught
     * there, off Main, by rewriting `config.json`'s own "secrets" list to drop it — so THIS method
     * stays correct by reading a file that's already been made to tell the truth, rather than by
     * checking the Keystore itself on every call.
     */
    fun missing(pluginId: String, settings: List<PluginSetting>): List<PluginSetting> {
        if (settings.none { it.required }) return emptyList()
        val stored = readFile(pluginId)
        return settings.filter { s ->
            s.required && when (s.type) {
                SettingType.PASSWORD -> s.key !in stored.secrets
                else -> (stored.values.opt(s.key) as? String).isNullOrBlank()
            }
        }
    }

    /**
     * Reconciles `config.json`'s "secrets" list against what the [SecretStore] can ACTUALLY produce
     * right now, for one plugin: a password listed as set but no longer readable is dropped from
     * the list and the file rewritten, so [missing]/[setupState] — which never touch the Keystore —
     * read it back as unset from then on (fix round 2, finding 5's own regression: the Keystore
     * check has to happen SOMEWHERE, just never inside the reload()-reachable path). A no-op, no
     * write, when nothing has actually changed.
     *
     * Does Keystore IO: call it off the main thread. AppGraph runs this once per installed plugin
     * during warm-up (already IO-dispatched), before `pluginRegistry` is ever touched from the UI.
     */
    fun reconcileSecrets(pluginId: String, settings: List<PluginSetting>) {
        val passwordKeys = settings.filter { it.type == SettingType.PASSWORD }.map { it.key }
        if (passwordKeys.isEmpty()) return
        val stored = readFile(pluginId)
        if (stored.secrets.none { it in passwordKeys }) return
        val actuallyPresent = stored.secrets.filter { it !in passwordKeys || secrets.get(secretKey(pluginId, it)) != null }.toSet()
        if (actuallyPresent == stored.secrets) return
        val json = JSONObject().put("values", stored.values).put("secrets", JSONArray(actuallyPresent)).put("revision", stored.revision)
        writeFileAtomically(File(dataDir(pluginId), FILE_NAME), json.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Validates [input] (every setting's value, as the Configurar screen holds them) and saves it.
     * Returns null when saved, or the Spanish reason it wasn't (nothing is written then).
     *
     * Bumps [Stored.revision] (fix round 2, finding 4): read by [setupState] into
     * [PluginSetupState.revision], which becomes part of `InstalledPlugin` — see [setupState]'s
     * KDoc for why that's what actually makes a user/password-only save visible to `reload()`'s
     * `StateFlow`.
     */
    fun save(pluginId: String, settings: List<PluginSetting>, input: Map<String, Any?>): String? {
        for (s in settings) {
            val v = input[s.key]
            if (v == null || (v is String && v.isBlank())) {
                if (s.required) return "Completa \"${s.label}\""
                continue
            }
            PluginSettings.validateValue(s, v)?.let { return it }
        }
        val previousRevision = readFile(pluginId).revision
        val values = JSONObject()
        val setSecrets = ArrayList<String>()
        for (s in settings) {
            val v = input[s.key]
            val blank = v == null || (v is String && v.isBlank())
            if (s.type == SettingType.PASSWORD) {
                if (blank) secrets.remove(secretKey(pluginId, s.key)) else {
                    secrets.put(secretKey(pluginId, s.key), v as String)
                    setSecrets += s.key
                }
            } else if (!blank) {
                values.put(s.key, if (v is String) v.trim() else v)
            }
        }
        val json = JSONObject().put("values", values).put("secrets", JSONArray(setSecrets)).put("revision", previousRevision + 1)
        writeFileAtomically(File(dataDir(pluginId), FILE_NAME), json.toString().toByteArray(Charsets.UTF_8))
        return null
    }

    /** Uninstall: the file (usually already gone with the data dir) and every secret of [settings]. */
    fun clear(pluginId: String, settings: List<PluginSetting>) {
        File(dataDir(pluginId), FILE_NAME).delete()
        settings.filter { it.type == SettingType.PASSWORD }.forEach { secrets.remove(secretKey(pluginId, it.key)) }
    }

    private data class Stored(val values: JSONObject, val secrets: Set<String>, val revision: Int = 0)

    private fun readFile(pluginId: String): Stored {
        val file = File(dataDir(pluginId), FILE_NAME)
        val o = runCatching { file.takeIf { it.length() <= MAX_FILE_BYTES }?.readText()?.let(::JSONObject) }.getOrNull()
            ?: return Stored(JSONObject(), emptySet())
        val names = o.optJSONArray("secrets")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }.orEmpty()
        return Stored(o.optJSONObject("values") ?: JSONObject(), names, o.optInt("revision", 0))
    }

    companion object {
        const val FILE_NAME = "config.json"

        /** 12 settings of at most 2 KB each fit many times over; anything bigger isn't ours. */
        private const val MAX_FILE_BYTES = 256L * 1024

        fun secretKey(pluginId: String, key: String) = "plugin.$pluginId.$key"
    }
}
