package com.arkiv.player.data.plugin

import com.arkiv.player.data.gateway.GatewaySearchQuery
import com.arkiv.player.data.magis.MagisPluginBridge
import com.arkiv.player.data.magis.MagisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The production [PrivilegedXuperHost]. [DefaultPluginHost] is `final` (this codebase has no other
 * open production class), so every ordinary `kino.*` member -- fetch/storage/cookies/config/crypto,
 * all unchanged from any other installed plugin -- is delegated to one, by interface delegation
 * rather than inheritance. Only the 5 `xuper*` members are this class's own.
 */
class DefaultPrivilegedXuperHost internal constructor(
    id: String,
    http: PluginHttp,
    storage: PluginStorage,
    config: PluginConfig,
    cookies: PluginCookies?,
    /** `AppGraph`'s one [MagisPluginBridge]: every Magis call goes through it. Lazy, so opening a
     *  runtime never forces the credential-gated Magis objects; only a `kino.xuper.*` call does. */
    private val magis: Lazy<MagisPluginBridge>,
) : PluginHost by DefaultPluginHost(id, http, storage, config, cookies), PrivilegedXuperHost {

    /**
     * Argument: a [GatewaySearchQuery]'s fields, `{"q", "type", "season", "episode", "tmdbId"}`, with
     * `type` in its vocabulary ("movie"/"tv"/"anime") and the plugin contract's "series" read as
     * "tv". Answer: the envelope around [MagisPluginBridge.search]'s items.
     */
    override suspend fun xuperSearch(argsJson: String): String = envelope(accountLinked()) {
        magis.value.search(searchQueryOf(JSONObject(argsJson)))
    }

    /**
     * Answer: the envelope around [MagisPluginBridge.home]'s rows. Unlike [envelope]
     * (search/episodes/resolve), [MagisPluginBridge.home] never answers a portal error of its own
     * -- `MagisHomeCatalog`'s rows are cache-first and a failing root just contributes nothing, see
     * its own KDoc -- so there is no `MagisResult` here to pattern-match, and this gets its own
     * minimal wrapping instead of reusing [envelope]. Only a genuinely unexpected throw (nothing
     * `MagisHomeCatalog`/`HomeCatalogStore` cause today, both total and defensive by design; see
     * the Task 9 report) collapses to the same `unavailable` shape the other `xuper*` members use.
     */
    override suspend fun xuperHome(): String = withContext(Dispatchers.IO) {
        try {
            JSONObject().put("ok", true).put("data", magis.value.home()).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(PluginErrors.UNAVAILABLE, e.message ?: "error de Xuper")
        }
    }

    /**
     * Argument: a Home row's own `ref` (`kino.xuper.home`'s row `ref`, equal to its `id`), and a
     * numeric offset `cursor` ("Ver más" page). Answer: the envelope around
     * [MagisPluginBridge.browse]'s `{items, next}`, paging over the row's already-fetched
     * `MagisHomeRow.all` -- no new portal call. Like [xuperHome] (and for the same reason: no
     * `MagisResult` here to pattern-match), this doesn't reuse [envelope]. A `ref` that matches no
     * current row answers plain `not_found`, the same shape every other installed plugin's browse
     * of an unknown ref already answers with -- not a portal error, so it never goes through
     * [MagisResult.toPluginError].
     */
    override suspend fun xuperBrowse(ref: String, cursor: String?): String = withContext(Dispatchers.IO) {
        try {
            val page = magis.value.browse(ref, cursor)
                ?: return@withContext failure(PluginErrors.NOT_FOUND, "")
            JSONObject().put("ok", true).put("data", page).toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure(PluginErrors.UNAVAILABLE, e.message ?: "error de Xuper")
        }
    }

    /**
     * Argument: a series item's own `ref`, as `kino.xuper.search` returned it. Answer: the envelope
     * around [MagisPluginBridge.episodes]'s `{episodes, series}`.
     */
    override suspend fun xuperEpisodes(ref: String): String =
        envelope(accountLinked(), goneMessage = XUPER_SERIES_GONE) { magis.value.episodes(ref) }

    /**
     * Argument: the item's own `ref`, as `kino.xuper.search` returned it. Answer: the envelope
     * around [MagisPluginBridge.resolve]'s stream.
     */
    override suspend fun xuperResolve(ref: String): String = envelope(accountLinked()) { magis.value.resolve(ref) }

    /** Read lazily (a thunk) so a call never forces the Magis objects before it runs; see [toPluginError]. */
    private fun accountLinked(): () -> Boolean = { runCatching { magis.value.accountLinked() }.getOrDefault(false) }
}

/**
 * The one production decision of which [PluginHost] a plugin's runtime gets: [DefaultPrivilegedXuperHost]
 * only for the plugin [XuperPrivilege.grants], [DefaultPluginHost] for every other one.
 * `AppGraph.openPluginRuntime` calls this rather than inlining the branch, and so does
 * `XuperPrivilegeGateTest` -- the SAME function, not a reproduction of its condition, so a later
 * change to the gate (or its removal) can't leave a test green while the real gate silently breaks.
 * [magis] is only ever read by the privileged host, and only when a `kino.xuper.*` call runs.
 * [secrets] ([pluginSecretsFor]) reach only the ordinary [DefaultPluginHost]; the privileged host
 * never gets any.
 */
internal fun pluginHostFor(
    plugin: InstalledPlugin,
    http: PluginHttp,
    storage: PluginStorage,
    config: PluginConfig,
    cookies: PluginCookies?,
    magis: Lazy<MagisPluginBridge>,
    secrets: PluginSecrets? = null,
): PluginHost = if (XuperPrivilege.grants(plugin.record)) {
    DefaultPrivilegedXuperHost(plugin.id, http, storage, config, cookies, magis)
} else {
    DefaultPluginHost(plugin.id, http, storage, config, cookies, secrets = secrets)
}

/** `as? String`, not `optString`: Android's org.json turns a JSON `null` into the text "null". */
private fun searchQueryOf(args: JSONObject) = GatewaySearchQuery(
    q = args.opt("q") as? String ?: "",
    type = when (val type = (args.opt("type") as? String).orEmpty().ifBlank { "movie" }) {
        "series" -> "tv"
        else -> type
    },
    season = args.optInt("season", 0),
    episode = args.optInt("episode", 0),
    tmdbId = args.optInt("tmdbId", 0),
)

/**
 * The `kino.xuper.*` envelope: `{"ok":true,"data":…}`, or `{"ok":false,"code","message"}` with a
 * portal failure's code collapsed by Task 4's `toPluginError()`. Anything else thrown is
 * `unavailable`, with MagisSource's own fallback text. Never throws, except cancellation.
 */
private suspend fun envelope(
    accountLinked: () -> Boolean = { false },
    goneMessage: String = XUPER_EPISODE_GONE,
    call: suspend () -> MagisResult<Any>,
): String = withContext(Dispatchers.IO) {
    val result = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return@withContext failure(PluginErrors.UNAVAILABLE, e.message ?: "error de Xuper")
    }
    when (result) {
        is MagisResult.Ok -> JSONObject().put("ok", true).put("data", result.data).toString()
        is MagisResult.PortalError -> result.toPluginError(accountLinked(), goneMessage).let { (code, message) -> failure(code, message) }
        is MagisResult.RedError -> result.toPluginError().let { (code, message) -> failure(code, message) }
    }
}

private fun failure(code: String, message: String): String =
    JSONObject().put("ok", false).put("code", code).put("message", message).toString()
