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

    /** Every key it holds, so uninstall can also remove keys no current manifest declares. */
    fun keys(): Set<String> = emptySet()
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
                SettingType.LIST -> listFrom(s, stored.values.opt(s.key))
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
        val userHosts = (
            settings.filter { it.type == SettingType.URL }.mapNotNull { (stored.values.opt(it.key) as? String)?.let(PluginHosts::userHostOf) } +
                settings.filter { it.type == SettingType.LIST }.flatMap { list ->
                    val urlFields = list.fields.filter { it.type == SettingType.URL }
                    (listFrom(list, stored.values.opt(list.key)) ?: emptyList()).flatMap { e -> urlFields.mapNotNull { f -> e[f.key]?.let(PluginHosts::userHostOf) } }
                }
            ).distinct()
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
                SettingType.LIST -> listFrom(s, stored.values.opt(s.key)).isNullOrEmpty()
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
     * [reconcileSecrets] for every plugin in [plugins] (id to its settings), one at a time. A
     * plugin whose secret store throws (the Keystore/Tink misbehave on cheap Android boxes) goes to
     * [onFailure] and is skipped: it never stops the other plugins, nor the caller — AppGraph runs
     * this inside the warm-up that pre-builds Xuper's own lazies off Main.
     */
    fun reconcileAllSecrets(plugins: List<Pair<String, List<PluginSetting>>>, onFailure: (pluginId: String, error: Throwable) -> Unit) {
        for ((id, settings) in plugins) runCatching { reconcileSecrets(id, settings) }.onFailure { onFailure(id, it) }
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
    fun save(pluginId: String, settings: List<PluginSetting>, rawInput: Map<String, Any?>): String? {
        // A list is trimmed and loses its blank entries before anything is checked or written.
        val input = rawInput.toMutableMap()
        for (s in settings) if (s.type == SettingType.LIST) PluginSettings.entriesOf(input[s.key])?.let { input[s.key] = cleanList(s, it) }
        for (s in settings) {
            val v = input[s.key]
            if (v == null || (v is String && v.isBlank()) || (v is List<*> && v.isEmpty())) {
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
            val blank = v == null || (v is String && v.isBlank()) || (v is List<*> && v.isEmpty())
            if (s.type == SettingType.LIST) {
                if (!blank) values.put(s.key, JSONArray().also { a -> PluginSettings.entriesOf(v)!!.forEach { e -> a.put(JSONObject().also { o -> s.fields.forEach { f -> o.put(f.key, e[f.key].orEmpty()) } }) } })
            } else if (s.type == SettingType.PASSWORD) {
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

    /**
     * The person's own answers to the settings that travel to their other devices
     * ([com.arkiv.player.data.plugin.sync.SharedSettings.isShared]): only what they set, never a
     * default, never a password.
     */
    fun sharedValues(pluginId: String, settings: List<PluginSetting>): Map<String, Any> {
        val stored = readFile(pluginId)
        val out = LinkedHashMap<String, Any>()
        for (s in settings.filter(com.arkiv.player.data.plugin.sync.SharedSettings::isShared)) {
            val value: Any? = if (s.type == SettingType.LIST) listFrom(s, stored.values.opt(s.key))
            else stored.values.opt(s.key)?.takeIf { it != JSONObject.NULL && PluginSettings.validateValue(s, it) == null }
            if (value != null && !(value is String && value.isEmpty())) out[s.key] = value
        }
        return out
    }

    /**
     * Writes the shared answers another of the person's devices sent ([incoming]) over this device's:
     * each shared setting the other device has a value for takes it when it fits this manifest; one it
     * has no value for keeps this device's (a device that never configured the plugin must not wipe
     * the answers given here). A typed server is validated like a save by hand. Passwords and anything this manifest does not declare as
     * shared are left exactly as they are. Returns whether anything changed (and the revision moved);
     * a value that does not fit is skipped, never stored.
     */
    fun applyShared(pluginId: String, settings: List<PluginSetting>, incoming: Map<String, Any>): Boolean {
        val shared = settings.filter(com.arkiv.player.data.plugin.sync.SharedSettings::isShared)
        if (shared.isEmpty()) return false
        val current = sharedValues(pluginId, settings)
        val wanted = LinkedHashMap<String, Any>(current)
        for (s in shared) {
            val v = incoming[s.key] ?: continue
            val clean: Any = if (s.type == SettingType.LIST) PluginSettings.entriesOf(v)?.let { cleanList(s, it) } ?: continue else v
            if (clean is String && clean.isBlank()) continue
            if (clean is List<*> && clean.isEmpty()) continue
            if (PluginSettings.validateValue(s, clean) != null) continue
            wanted[s.key] = if (clean is String) clean.trim() else clean
        }
        if (wanted == current) return false
        val stored = readFile(pluginId)
        val values = JSONObject(stored.values.toString())
        for (s in shared) {
            val v = wanted[s.key]
            when {
                v == null -> values.remove(s.key)
                s.type == SettingType.LIST -> values.put(s.key, JSONArray().also { a -> PluginSettings.entriesOf(v)!!.forEach { e -> a.put(JSONObject().also { o -> s.fields.forEach { f -> o.put(f.key, e[f.key].orEmpty()) } }) } })
                else -> values.put(s.key, v)
            }
        }
        val json = JSONObject().put("values", values).put("secrets", JSONArray(stored.secrets.toList())).put("revision", stored.revision + 1)
        writeFileAtomically(File(dataDir(pluginId), FILE_NAME), json.toString().toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * The passwords the person set for this plugin (setting key to value), for plugin sync to seal
     * end-to-end for their other devices. Keystore IO. Never logged.
     */
    fun secretValues(pluginId: String, settings: List<PluginSetting>): Map<String, String> {
        val stored = readFile(pluginId)
        val out = LinkedHashMap<String, String>()
        for (s in settings) {
            if (s.type != SettingType.PASSWORD || s.key !in stored.secrets) continue
            secrets.get(secretKey(pluginId, s.key))?.takeIf { it.isNotEmpty() }?.let { out[s.key] = it }
        }
        return out
    }

    /**
     * Passwords another of the person's devices sent (already opened end-to-end): each one this
     * manifest declares as a password is stored and listed as set. Only sets, never clears: a peer
     * without a value (an older plugin version, nothing typed there) leaves this device's alone.
     * Returns whether anything changed (and the revision moved). Keystore IO.
     */
    fun applySecrets(pluginId: String, settings: List<PluginSetting>, values: Map<String, String>): Boolean {
        val stored = readFile(pluginId)
        val names = stored.secrets.toMutableSet()
        var changed = false
        for (s in settings) {
            if (s.type != SettingType.PASSWORD) continue
            val v = values[s.key]?.takeIf { it.isNotEmpty() && it.length <= MAX_SECRET_CHARS } ?: continue
            val key = secretKey(pluginId, s.key)
            if (s.key in names && secrets.get(key) == v) continue
            secrets.put(key, v)
            names += s.key
            changed = true
        }
        if (!changed) return false
        val json = JSONObject().put("values", stored.values).put("secrets", JSONArray(names.toList())).put("revision", stored.revision + 1)
        writeFileAtomically(File(dataDir(pluginId), FILE_NAME), json.toString().toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * Passwords for a plugin not installed here yet: kept in the [SecretStore] only (no `config.json`
     * names them), until [adoptSecrets] runs once the plugin is installed. Keys outside the plugin's
     * own `plugin.<id>.` namespace can't be written.
     */
    fun storeSecrets(pluginId: String, values: Map<String, String>) {
        for ((k, v) in values) {
            if (!SECRET_NAME.matches(k) || v.isEmpty() || v.length > MAX_SECRET_CHARS) continue
            secrets.put(secretKey(pluginId, k), v)
        }
    }

    /**
     * Just installed: the passwords [storeSecrets] kept for it that its manifest declares are listed
     * as set. Returns whether anything changed (and the revision moved). Keystore IO.
     */
    fun adoptSecrets(pluginId: String, settings: List<PluginSetting>): Boolean {
        val stored = readFile(pluginId)
        val adopt = settings.filter { it.type == SettingType.PASSWORD && it.key !in stored.secrets && !secrets.get(secretKey(pluginId, it.key)).isNullOrEmpty() }
        if (adopt.isEmpty()) return false
        val names = stored.secrets + adopt.map { it.key }
        val json = JSONObject().put("values", stored.values).put("secrets", JSONArray(names.toList())).put("revision", stored.revision + 1)
        writeFileAtomically(File(dataDir(pluginId), FILE_NAME), json.toString().toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * Uninstall: the file (usually already gone with the data dir) and every secret of the plugin —
     * the ones [settings] declares AND any `plugin.<id>.` key an earlier version declared and a
     * later update renamed or dropped (spec §1.3: uninstall deletes both).
     */
    fun clear(pluginId: String, settings: List<PluginSetting>) {
        File(dataDir(pluginId), FILE_NAME).delete()
        val prefix = secretKey(pluginId, "")
        val declared = settings.filter { it.type == SettingType.PASSWORD }.map { secretKey(pluginId, it.key) }
        // Listing decrypts the whole store: if that fails, still remove the declared ones.
        val leftovers = runCatching { secrets.keys().filter { it.startsWith(prefix) } }.getOrDefault(emptyList())
        (declared + leftovers).toSet().forEach(secrets::remove)
    }

    /** [entries] trimmed, keeping only the list's own fields, without the entries whose every field is blank. */
    private fun cleanList(s: PluginSetting, entries: List<Map<String, String>>): List<Map<String, String>> =
        entries.map { e -> s.fields.associate { it.key to e[it.key].orEmpty().trim() } }.filter { e -> e.values.any { it.isNotEmpty() } }

    /** A stored list as entries, or null when it is not one, or no longer fits the manifest (a field that became invalid, past `max`). */
    private fun listFrom(s: PluginSetting, raw: Any?): List<Map<String, String>>? {
        val array = raw as? JSONArray ?: return null
        val entries = (0 until array.length()).map { i ->
            val o = array.optJSONObject(i) ?: return null
            s.fields.associate { it.key to (o.opt(it.key) as? String).orEmpty().trim() }
        }
        val clean = cleanList(s, entries)
        return clean.takeIf { it.isNotEmpty() && PluginSettings.validateValue(s, it) == null }
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

        /** A password value from another device longer than this is not one a person typed. */
        const val MAX_SECRET_CHARS = 2048

        /** A setting key, as a manifest may declare it. */
        private val SECRET_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")

        /** 12 settings of at most 2 KB each fit many times over; anything bigger isn't ours. */
        private const val MAX_FILE_BYTES = 256L * 1024

        fun secretKey(pluginId: String, key: String) = "plugin.$pluginId.$key"
    }
}
