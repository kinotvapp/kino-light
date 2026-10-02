package com.arkiv.player.data.subtitles

import android.content.Context
import com.arkiv.player.data.plugin.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whose key a provider call uses. */
enum class KeySource { USER, SHARED }

/** The key a provider will use, and whose it is. */
data class EffectiveKey(val auth: ProviderAuth, val source: KeySource)

/** Pure: which key wins. */
object KeyPrecedence {
    /** The person's own key over Kino's shared one; null when neither is set. The account rides with the person's. */
    fun effective(userKey: String?, shared: String?, username: String = "", password: String = ""): EffectiveKey? {
        userKey?.trim()?.takeIf { it.isNotEmpty() }?.let { return EffectiveKey(ProviderAuth(it, username.trim(), password), KeySource.USER) }
        shared?.trim()?.takeIf { it.isNotEmpty() }?.let { return EffectiveKey(ProviderAuth(it, username.trim(), password), KeySource.SHARED) }
        return null
    }
}

/** Which providers are on, in which order. Persisted as plain preferences (nothing secret). */
data class ProviderSettings(
    val order: List<SubtitleProviderId> = SubtitleProviderId.entries.toList(),
    val disabled: Set<SubtitleProviderId> = emptySet(),
) {
    val enabledInOrder: List<SubtitleProviderId> get() = order.filter { it !in disabled }

    fun toggled(id: SubtitleProviderId): ProviderSettings =
        copy(disabled = if (id in disabled) disabled - id else disabled + id)

    /** [id] one place earlier in [order] (no-op at the top). */
    fun movedUp(id: SubtitleProviderId): ProviderSettings {
        val i = order.indexOf(id)
        if (i <= 0) return this
        return copy(order = order.toMutableList().apply { add(i - 1, removeAt(i)) })
    }

    fun encode(): String = order.joinToString(",") { it.name } + ";" + disabled.joinToString(",") { it.name }

    companion object {
        /** Unknown names are dropped and a missing provider is appended, so a new one shows up enabled. */
        fun decode(raw: String?): ProviderSettings {
            if (raw.isNullOrBlank()) return ProviderSettings()
            fun ids(s: String) = s.split(',').mapNotNull { n -> SubtitleProviderId.entries.firstOrNull { it.name == n.trim() } }
            val order = ids(raw.substringBefore(';')).distinct()
            val disabled = ids(raw.substringAfter(';', "")).toSet()
            return ProviderSettings(order + SubtitleProviderId.entries.filter { it !in order }, disabled)
        }
    }
}

/**
 * The online subtitle settings: each provider's own key the person typed (in [secrets], an
 * `EncryptedSharedPreferences` file of its own, `kino_subtitle_keys` -- the plugin passwords' store),
 * OpenSubtitles' optional account, and which providers are on. Kino's shared keys come from the
 * activation blob ([sharedKey]); the person's own win ([KeyPrecedence]).
 *
 * Keys are read off the main thread (the encrypted store touches the Keystore). Nothing here logs a
 * key: [OnlineSubtitleRules.mask] is what a log line gets.
 */
class SubtitleKeys(
    private val secrets: SecretStore,
    private val sharedKey: (SubtitleProviderId) -> String?,
    private val prefs: android.content.SharedPreferences?,
) {
    constructor(context: Context, secrets: SecretStore, sharedKey: (SubtitleProviderId) -> String?) :
        this(secrets, sharedKey, context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val _providers = MutableStateFlow(ProviderSettings.decode(prefs?.getString(K_PROVIDERS, null)))
    val providers: StateFlow<ProviderSettings> = _providers.asStateFlow()

    /** Bumped on every key change, so screens re-read what is in use. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun setProviders(settings: ProviderSettings) {
        _providers.value = settings
        prefs?.edit()?.putString(K_PROVIDERS, settings.encode())?.apply()
    }

    /** The person's own key for [id], or "". Off the main thread. */
    fun userKey(id: SubtitleProviderId): String = secrets.get(keyName(id)).orEmpty()

    fun setUserKey(id: SubtitleProviderId, key: String) {
        val v = key.trim()
        if (v.isEmpty()) secrets.remove(keyName(id)) else secrets.put(keyName(id), v)
        _version.value++
    }

    /** OpenSubtitles' account (username, password), "" when none. Off the main thread. */
    fun account(): Pair<String, String> = secrets.get(K_OS_USER).orEmpty() to secrets.get(K_OS_PASSWORD).orEmpty()

    fun setAccount(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            secrets.remove(K_OS_USER)
            secrets.remove(K_OS_PASSWORD)
        } else {
            secrets.put(K_OS_USER, username.trim())
            secrets.put(K_OS_PASSWORD, password)
        }
        _version.value++
    }

    fun hasShared(id: SubtitleProviderId): Boolean = !sharedKey(id).isNullOrBlank()

    /** The key [id] would use now, or null. Off the main thread. */
    fun effective(id: SubtitleProviderId): EffectiveKey? {
        val (user, password) = if (id == SubtitleProviderId.OPENSUBTITLES) account() else "" to ""
        return KeyPrecedence.effective(userKey(id), sharedKey(id), user, password)
    }

    /** The enabled providers that have a key, in the person's order. Off the main thread. */
    fun usable(): List<Pair<SubtitleProviderId, EffectiveKey>> =
        providers.value.enabledInOrder.mapNotNull { id -> effective(id)?.let { id to it } }

    private fun keyName(id: SubtitleProviderId) = "subtitles.${id.name.lowercase()}.apiKey"

    companion object {
        const val SECRETS_FILE = "kino_subtitle_keys"
        private const val PREFS = "kino_online_subtitles"
        private const val K_PROVIDERS = "providers"
        private const val K_OS_USER = "subtitles.opensubtitles.username"
        private const val K_OS_PASSWORD = "subtitles.opensubtitles.password"
    }
}
