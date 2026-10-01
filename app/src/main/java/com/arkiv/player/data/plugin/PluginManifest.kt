package com.arkiv.player.data.plugin

import org.json.JSONObject

/** A validated `kino-plugin.json`. Build it only through [ManifestParser.parse]. */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val entry: String,
    val description: String,
    val author: String,
    val homepage: String,
    val hosts: List<String>,
    val capabilities: Set<String>,
    /** `#RRGGBB` uppercased, or null for the neutral default (`PluginColors.DEFAULT`). */
    val color: String?,
    val icon: String?,
    /** From the closed list in [PluginSettings.PERMISSIONS] (empty in SDK v1); shown on the consent sheet. */
    val permissions: List<String> = emptyList(),
    /** What the plugin asks the person to configure (Plugins ▸ Configurar). */
    val settings: List<PluginSetting> = emptyList(),
    /** The subset of [hosts] the manifest marked `{ "host", "insecureHttp": true }` (apiVersion 2 only). */
    val insecureHosts: Set<String> = emptySet(),
    /**
     * `"liveStreamHosts": "any"` (apiVersion 3, with `channels`): the plugin asks that its live
     * channels' streams may be on any public server. What the gate honours is the INSTALLED record's
     * copy ([InstalledRecord.liveStreamHostsAny]), the one the person approved in red.
     */
    val liveStreamHostsAny: Boolean = false,
    /**
     * `"streamHosts": "any"` (apiVersion 4): what the plugin plays -- a movie, an episode or a live
     * channel -- may be on any public server. For a movie or an episode it is the same rule as the
     * broad video permission ([InstalledRecord.videoFromAnyHost]): the player's video, its manifest's
     * requests and its side subtitles/audio; `kino.fetch`, images, DRM licenses and downloads stay on
     * the declared hosts. What the gate honours is the INSTALLED record's copy
     * ([InstalledRecord.streamHostsAny]), the one the person approved in red.
     */
    val streamHostsAny: Boolean = false,
    /**
     * `"fetchHosts": "any"` (apiVersion 4): the plugin's `kino.fetch` may reach any PUBLIC host
     * without asking host by host. Only [NuvioPluginConverter] writes it, and only a Nuvio-converted
     * install honours it: a hand-written plugin may declare it (the manifest stays valid) but the
     * installer neither shows nor stores it (see [InstalledRecord.fetchFromAnyHost]).
     */
    val fetchHostsAny: Boolean = false,
    /**
     * `"discoverable": false` keeps the plugin out of Kino's community search (Recomendados ▸ "De la
     * comunidad" and "Elige tus fuentes"). Only discovery reads it, never the runtime, so it is valid at
     * every apiVersion. Default [ManifestParser.DISCOVERABLE_DEFAULT].
     */
    val discoverable: Boolean = true,
    /**
     * `"secrets": { "<name>": "kino-sealed:v1:…" }` (apiVersion 4): sealed values the plugin may ask
     * for with `kino.secret(name)`. Never the plain value -- the seal stays sealed until a runtime
     * opens it (see [SealedSecrets]).
     */
    val secrets: Map<String, String> = emptyMap(),
    /**
     * apiVersion 5's `sealedEntry`: [entry] names a `.kjs` file (the entry script sealed to Kino and
     * signed by its author, see [SealedCode]) instead of a plain `.js`. False for every plugin
     * written before it existed, so nothing about them changes.
     */
    val entrySealed: Boolean = false,
)

sealed interface ManifestResult {
    data class Valid(val manifest: PluginManifest) : ManifestResult
    /** [message] is shown to the person as-is: Spanish, naming the field. */
    data class Invalid(val field: String, val message: String) : ManifestResult
}

/**
 * Parses and validates a plugin manifest. Every rule here is in the spec's validation table; the
 * installer refuses on the first failure and shows [ManifestResult.Invalid.message].
 */
object ManifestParser {
    /**
     * The highest `apiVersion` a manifest may declare, and the value this build reports at runtime
     * as `kino.apiVersion`. Each feature gates on its OWN constant below
     * ([CAPABILITY_API_VERSIONS], [INSECURE_HOST_API_VERSION], [NO_HOSTS_API_VERSION],
     * [SECRETS_API_VERSION], [STREAM_HOSTS_API_VERSION], `PluginSettings.LIST_API_VERSION`,
     * `PluginOutput.LIVE_API_VERSION`), never on this one: raising it must
     * not move an older gate.
     */
    const val SUPPORTED_API = 5
    /** `sealedEntry` (the entry script sealed to Kino and signed by its author, [SealedCode]) arrived with apiVersion 5. */
    const val SEALED_CODE_API_VERSION = 5
    const val BOTH_ENTRIES = "Usa \"entry\" o \"sealedEntry\", no los dos"
    const val SEALED_ENTRY_PATH = "El campo \"sealedEntry\" debe ser una ruta relativa a un archivo .kjs"
    /** The `{ "host", "insecureHttp": true }` host object arrived with apiVersion 2. */
    const val INSECURE_HOST_API_VERSION = 2
    /** The `secrets` field (sealed values a plugin may ask for with `kino.secret(name)`) arrived with apiVersion 4. */
    const val SECRETS_API_VERSION = 4
    /** apiVersion 3: the plugin adds channels to the En vivo module (see `data/live/PluginLiveProvider`). */
    const val CHANNELS = "channels"
    /** The only value `liveStreamHosts` admits: a live channel's stream may be on any public host. */
    const val LIVE_STREAM_HOSTS_ANY = "any"
    /** `streamHosts` (any kind of title, no capability needed) arrived with apiVersion 4; its only value is [LIVE_STREAM_HOSTS_ANY]. */
    const val STREAM_HOSTS_API_VERSION = 4
    /** `fetchHosts` (Nuvio-converted plugins only, see [PluginManifest.fetchHostsAny]) arrived with apiVersion 4; its only value is [LIVE_STREAM_HOSTS_ANY]. */
    const val FETCH_HOSTS_API_VERSION = 4
    /** `liveStreamHosts` arrived with apiVersion 3 (and needs [CHANNELS]). */
    const val LIVE_STREAM_HOSTS_API_VERSION = 3
    /** What a manifest without `discoverable` means: listed when its repo carries the `kino-plugin` topic. */
    const val DISCOVERABLE_DEFAULT = true
    const val DISCOVERABLE_NOT_BOOLEAN = "El campo \"discoverable\" debe ser true o false"
    const val MAX_BYTES = 16 * 1024
    const val MIN_HOSTS = 1
    /**
     * From this apiVersion `hosts` may be empty when the manifest has a `url` setting: a plugin
     * whose only reach is the server the person types needs no placeholder host. Its own constant,
     * not [SUPPORTED_API], so a later round cannot move it by accident.
     */
    const val NO_HOSTS_API_VERSION = 2
    const val NO_HOSTS_NEEDS_URL_SETTING = "El campo \"hosts\" solo puede estar vacío si el plugin tiene un ajuste de tipo \"url\""
    /**
     * The most `hosts` entries Kino 0.9.44 and older accept. NOT enforced here any more: from 0.9.45
     * a manifest may declare any number of hosts, bounded in practice only by [MAX_BYTES] (a few
     * hundred real domain names). Kept so `contract.json` can tell plugin authors (sdk/validate.mjs
     * warns) that a longer list is refused by older apps.
     */
    const val LEGACY_MAX_HOSTS = 20
    const val MAX_NAME_CHARS = 40
    const val MAX_DESCRIPTION_CHARS = 300
    const val MAX_AUTHOR_CHARS = 60
    const val MAX_HOMEPAGE_CHARS = 200
    // "own" is the built-in live provider "Mis canales" (data/live/OwnLive.PLUGIN_ID): no real plugin may claim it.
    val RESERVED_IDS = setOf("magis", "ditu", "live", "local", "unknown", "plugin", "own")
    val CAPABILITIES = setOf("search", "home", "browse", "episodes", "resolve", "download", "drm", CHANNELS)
    val REQUIRED_CAPABILITIES = listOf("resolve")
    val AT_LEAST_ONE_OF_CAPABILITIES = listOf("search", "home")
    /**
     * Capabilities the app itself acts on (a download button, DRM playback) rather than functions
     * the plugin exports: [requiredExports] never includes them.
     */
    val DECLARATIVE_CAPABILITIES = setOf("download", "drm")
    /** The lowest apiVersion that may declare each capability; any capability not listed is apiVersion 1. */
    val CAPABILITY_API_VERSIONS: Map<String, Int> = mapOf("download" to 2, "drm" to 2, CHANNELS to 3)
    /** A plugin update that newly declares one of these waits for the person's approval (new reach). */
    val APPROVAL_CAPABILITIES: Set<String> = setOf("download", "drm", CHANNELS)
    /** Capabilities whose exported functions are not named like the capability. */
    val EXPORTS_FOR: Map<String, Set<String>> = mapOf(CHANNELS to setOf("liveCategories", "liveChannels"))
    /** Functions a capability MAY export; the app copes with their absence (never checked at install). */
    val OPTIONAL_EXPORTS_FOR: Map<String, Set<String>> = mapOf(CHANNELS to setOf("guide"))

    /** The functions the entry file must export for [capabilities]: what the installer checks against the sandbox probe. */
    fun requiredExports(capabilities: Set<String>): Set<String> =
        capabilities.flatMap { c -> EXPORTS_FOR[c] ?: if (c in DECLARATIVE_CAPABILITIES) emptySet() else setOf(c) }.toSet()
    const val MAX_PATH_CHARS = 200

    /** `internal`, not public API of [PluginManifest]: exposed only so tests can pin `contract.json`'s `idPattern` to it. */
    internal val ID = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

    /** `internal`, not public API of [PluginManifest]: exposed only so tests can pin `contract.json`'s `colorPattern` to it. */
    internal val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

    /** `internal`, not public API of [PluginManifest]: exposed only so a test can pin `contract.json`'s `pathSegmentPattern` to it. */
    internal val PATH_SEGMENT = Regex("^[A-Za-z0-9._-]+$")

    fun parse(text: String, knownPermissions: Set<String> = PluginSettings.PERMISSIONS): ManifestResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return invalid("kino-plugin.json", "El manifiesto pesa más de 16 KB")
        }
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return invalid("kino-plugin.json", "El manifiesto no es un JSON válido")

        val id = o.optString("id")
        if (!ID.matches(id)) return invalid("id", "El campo \"id\" debe tener de 2 a 40 letras minúsculas, números o guiones")
        if (id in RESERVED_IDS) return invalid("id", "El id \"$id\" está reservado por Kino")

        val name = o.optString("name").trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return invalid("name", "El campo \"name\" debe tener de 1 a $MAX_NAME_CHARS caracteres")

        val version = o.optString("version")
        if (!SemVer.isValid(version)) return invalid("version", "El campo \"version\" debe ser del tipo 1.2.3")

        val api = o.opt("apiVersion")
        if (api !is Int) return invalid("apiVersion", "El campo \"apiVersion\" debe ser un número entero")
        if (api > SUPPORTED_API) return invalid("apiVersion", "Este plugin necesita una versión más nueva de Kino")
        if (api < 1) return invalid("apiVersion", "El campo \"apiVersion\" debe ser 1 o mayor")

        // Below apiVersion 5 `sealedEntry` is unknown and ignored like any other field: an older
        // manifest reaches the `entry` rule exactly as it always did.
        val entrySealed = api >= SEALED_CODE_API_VERSION && o.has("sealedEntry")
        val entry = if (entrySealed) {
            if (o.has("entry")) return invalid("entry", BOTH_ENTRIES)
            val sealed = o.opt("sealedEntry") as? String ?: ""
            if (!isSafeRelativePath(sealed) || !sealed.endsWith(SealedCode.EXTENSION)) return invalid("sealedEntry", SEALED_ENTRY_PATH)
            sealed
        } else {
            o.optString("entry").also { entry ->
                if (!isSafeRelativePath(entry) || !entry.endsWith(".js")) {
                    return invalid("entry", "El campo \"entry\" debe ser una ruta relativa a un archivo .js")
                }
            }
        }

        val hostsJson = o.optJSONArray("hosts") ?: return invalid("hosts", "Falta el campo \"hosts\"")
        // Empty is judged once the settings are read (below), and only from NO_HOSTS_API_VERSION:
        // an older manifest gets the refusal it always got, at the point it always got it.
        val emptyHostsAllowedLater = hostsJson.length() == 0 && api >= NO_HOSTS_API_VERSION
        // No upper bound (from 0.9.45): the 16 KB manifest cap above is the practical one.
        if (!emptyHostsAllowedLater && hostsJson.length() < MIN_HOSTS) {
            return invalid("hosts", "El campo \"hosts\" debe tener al menos $MIN_HOSTS dominio")
        }
        val hostEntries = ArrayList<HostEntry>(hostsJson.length())
        for (i in 0 until hostsJson.length()) {
            when (val raw = hostsJson.opt(i)) {
                is String -> hostEntries += HostEntry(raw, insecure = false)
                is JSONObject -> {
                    // The object shape itself -- {host, insecureHttp} -- is apiVersion 2, whatever
                    // insecureHttp's value: a v1 manifest gets the same clear refusal either way.
                    if (api < INSECURE_HOST_API_VERSION) return invalid("hosts", "Un host con \"insecureHttp\" necesita apiVersion $INSECURE_HOST_API_VERSION")
                    hostEntries += HostEntry(raw.optString("host"), insecure = raw.optBoolean("insecureHttp"))
                }
                else -> hostEntries += HostEntry("", insecure = false)
            }
        }
        hostEntries.firstOrNull { !HostRules.isValidPattern(it.host) }?.let {
            return invalid("hosts", "El dominio \"${it.host}\" no está permitido")
        }
        // A comodín widens which servers accept plain http far more than one named host: not allowed
        // on an insecureHttp entry even though it is fine on an https-only one.
        hostEntries.firstOrNull { it.insecure && it.host.startsWith("*.") }?.let {
            return invalid("hosts", "Un host con \"insecureHttp\" no puede tener comodín (\"*.\")")
        }
        val hosts = hostEntries.map { it.host }.distinct()
        val insecureHosts = hostEntries.filter { it.insecure }.map { it.host }.toSet()

        val capsJson = o.optJSONArray("capabilities") ?: return invalid("capabilities", "Falta el campo \"capabilities\"")
        val caps = (0 until capsJson.length()).map { capsJson.opt(it) as? String ?: "" }.toSet()
        caps.firstOrNull { it !in CAPABILITIES }?.let { return invalid("capabilities", "Capacidad desconocida: \"$it\"") }
        caps.firstOrNull { (CAPABILITY_API_VERSIONS[it] ?: 1) > api }?.let {
            return invalid("capabilities", "Esta capacidad necesita apiVersion ${CAPABILITY_API_VERSIONS.getValue(it)}")
        }
        REQUIRED_CAPABILITIES.firstOrNull { it !in caps }?.let { return invalid("capabilities", "El plugin debe declarar \"$it\"") }
        if (AT_LEAST_ONE_OF_CAPABILITIES.none { it in caps }) {
            return invalid("capabilities", "El plugin debe declarar \"" + AT_LEAST_ONE_OF_CAPABILITIES.joinToString("\" o \"") + "\"")
        }

        // Below apiVersion 3 the field is unknown and ignored like any other (v1/v2 stay byte-for-byte).
        val liveStreamHostsAny = if (!o.has("liveStreamHosts") || api < LIVE_STREAM_HOSTS_API_VERSION) false else {
            if (o.opt("liveStreamHosts") != LIVE_STREAM_HOSTS_ANY) return invalid("liveStreamHosts", "El campo \"liveStreamHosts\" solo admite \"$LIVE_STREAM_HOSTS_ANY\"")
            if (CHANNELS !in caps) return invalid("liveStreamHosts", "\"liveStreamHosts\" necesita la capacidad \"$CHANNELS\"")
            true
        }

        // Below apiVersion 4 the field is unknown and ignored like any other (v1/v2/v3 stay byte-for-byte).
        val secrets = if (!o.has("secrets") || api < SECRETS_API_VERSION) emptyMap() else {
            val secretsJson = o.opt("secrets") as? JSONObject ?: return invalid("secrets", "El campo \"secrets\" debe ser un objeto")
            val names = secretsJson.keys().asSequence().toList()
            if (names.size > SealedSecrets.MAX_SECRETS) {
                return invalid("secrets", "El campo \"secrets\" admite hasta ${SealedSecrets.MAX_SECRETS} secretos")
            }
            val parsed = LinkedHashMap<String, String>(names.size)
            for (n in names) {
                if (!SealedSecrets.NAME.matches(n)) return invalid("secrets", "El secreto \"${n.take(MAX_NAME_CHARS)}\" tiene un nombre inválido")
                val seal = secretsJson.opt(n) as? String ?: ""
                if (!SealedSecrets.isWellFormed(seal)) return invalid("secrets", "El secreto \"$n\" no es un sello de Kino válido")
                parsed[n] = seal
            }
            parsed
        }

        val streamHostsAny = if (!o.has("streamHosts") || api < STREAM_HOSTS_API_VERSION) false else {
            if (o.opt("streamHosts") != LIVE_STREAM_HOSTS_ANY) return invalid("streamHosts", "El campo \"streamHosts\" solo admite \"$LIVE_STREAM_HOSTS_ANY\"")
            true
        }

        val fetchHostsAny = if (!o.has("fetchHosts") || api < FETCH_HOSTS_API_VERSION) false else {
            if (o.opt("fetchHosts") != LIVE_STREAM_HOSTS_ANY) return invalid("fetchHosts", "El campo \"fetchHosts\" solo admite \"$LIVE_STREAM_HOSTS_ANY\"")
            true
        }

        // Only discovery reads it (never the runtime), so it is valid at every apiVersion (ruling R2).
        val discoverable = when (val d = o.opt("discoverable")) {
            null -> DISCOVERABLE_DEFAULT
            is Boolean -> d
            else -> return invalid("discoverable", DISCOVERABLE_NOT_BOOLEAN)
        }

        val color = o.optString("color").takeIf { it.isNotEmpty() }
        if (color != null && !COLOR.matches(color)) return invalid("color", "El campo \"color\" debe ser del tipo #RRGGBB")

        val icon = o.optString("icon").takeIf { it.isNotEmpty() }
        if (icon != null && (!isSafeRelativePath(icon) || !icon.endsWith(".png"))) {
            return invalid("icon", "El campo \"icon\" debe ser una ruta relativa a un .png")
        }

        if (o.has("permissions") && o.optJSONArray("permissions") == null) {
            return invalid("permissions", "El campo \"permissions\" debe ser una lista")
        }
        val permissions = when (val p = PluginSettings.parsePermissions(o.optJSONArray("permissions"), knownPermissions)) {
            is PluginSettings.Parsed.Error -> return invalid("permissions", p.message)
            is PluginSettings.Parsed.Ok -> p.value
        }
        if (o.has("settings") && o.optJSONArray("settings") == null) {
            return invalid("settings", "El campo \"settings\" debe ser una lista")
        }
        val settings = when (val p = PluginSettings.parseSettings(o.optJSONArray("settings"), api)) {
            is PluginSettings.Parsed.Error -> return invalid("settings", p.message)
            is PluginSettings.Parsed.Ok -> p.value
        }

        if (hosts.isEmpty() && settings.none { it.type == SettingType.URL }) return invalid("hosts", NO_HOSTS_NEEDS_URL_SETTING)

        return ManifestResult.Valid(
            PluginManifest(
                id = id, name = name, version = version, apiVersion = api, entry = entry,
                description = text(o, "description", MAX_DESCRIPTION_CHARS), author = text(o, "author", MAX_AUTHOR_CHARS),
                homepage = text(o, "homepage", MAX_HOMEPAGE_CHARS), hosts = hosts, capabilities = caps,
                color = color?.uppercase(), icon = icon, permissions = permissions, settings = settings,
                insecureHosts = insecureHosts, liveStreamHostsAny = liveStreamHostsAny, streamHostsAny = streamHostsAny, fetchHostsAny = fetchHostsAny, discoverable = discoverable,
                secrets = secrets, entrySealed = entrySealed,
            ),
        )
    }

    /** One parsed `hosts` entry: a plain string, or an apiVersion 2 `{host, insecureHttp}` object. */
    private data class HostEntry(val host: String, val insecure: Boolean)

    /** A path inside the plugin's repo folder: no absolute paths, no `..`, no backslashes. */
    fun isSafeRelativePath(p: String): Boolean =
        p.isNotEmpty() && p.length <= MAX_PATH_CHARS && !p.startsWith("/") && '\\' !in p &&
            p.split('/').all { it.isNotEmpty() && it != "." && it != ".." && PATH_SEGMENT.matches(it) }

    private fun text(o: JSONObject, key: String, max: Int): String =
        (o.opt(key) as? String).orEmpty().trim().take(max)

    private fun invalid(field: String, message: String) = ManifestResult.Invalid(field, message)
}
