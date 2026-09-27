package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.util.Base64

/**
 * A plugin's opaque `ref` wrapped so the app can route it: `plg1:<pluginId>:<base64url(json)>`.
 *
 * `CompositeSource` dispatches `resolve`/`episodes` by prefix (`PluginContentSource.recognizes`),
 * and the library stores this string in `torrentData`, like Caracol's `ditu1:` refs. [itemId] is
 * the plugin's STABLE id for the title (the library row hangs off it); [ref] is the plugin's own
 * value, which may change between calls. An [EPISODE] ref carries its series' [itemId] plus
 * [season]/[number].
 *
 * `java.util.Base64`, not `android.util.Base64`: the Android one is a stub in JVM tests.
 */
data class PluginRef(
    val pluginId: String,
    val itemId: String,
    val kind: String,
    val ref: String,
    val season: Int = 0,
    val number: Int = 0,
) {
    fun encode(): String {
        val json = JSONObject().put("id", itemId).put("k", kind).put("r", ref)
        if (kind == EPISODE) {
            json.put("s", season)
            json.put("e", number)
        }
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return prefixFor(pluginId) + payload
    }

    companion object {
        const val PREFIX = "plg1"
        const val MOVIE = "movie"
        const val SERIES = "series"
        const val EPISODE = "episode"
        /** A live channel (apiVersion 2): its ref goes to `resolve` like a movie's, but it is never saved to the library. */
        const val LIVE = "live"
        private val KINDS = setOf(MOVIE, SERIES, EPISODE, LIVE)

        fun prefixFor(pluginId: String): String = "$PREFIX:$pluginId:"

        fun decode(value: String): PluginRef? {
            if (!value.startsWith("$PREFIX:")) return null
            val rest = value.removePrefix("$PREFIX:")
            val pluginId = rest.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return null
            val payload = rest.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
            val json = runCatching {
                JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
            }.getOrNull() ?: return null
            val kind = json.optString("k").takeIf { it in KINDS } ?: return null
            val itemId = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return PluginRef(pluginId, itemId, kind, json.optString("r"), json.optInt("s", 0), json.optInt("e", 0))
        }
    }
}

/**
 * Plugin identity strings outside the ref: the search-event/`items.source` name and library ids.
 * [PREFIX] must match the `"plugin:"` branch of `PlayerSource.kindFor`: that is what sends a saved
 * title to `PlayerViewModel.loadPlugin`.
 */
object PluginIds {
    const val PREFIX = "plugin:"

    fun sourceFor(pluginId: String): String = PREFIX + pluginId

    fun pluginIdOfSource(source: String): String? =
        source.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() && ':' !in it }

    fun itemIdFor(pluginId: String, itemId: String): String = "$PREFIX$pluginId:$itemId"

    fun pluginIdOfEpisode(episodeId: String): String? =
        episodeId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore(':', "")?.takeIf { it.isNotEmpty() }

    /**
     * The `::` suffix of a live channel's player id, next to a movie's `::0` and a chapter's `::e3`
     * (`PluginEntities`). A channel has no library row: its id exists only to reach the player, where
     * `PlayerSource.isLiveChannel` reads it and `PluginLive` hands over the ref.
     */
    const val LIVE_SUFFIX = "::live"

    /** `plugin:<pluginId>:<itemId>::live`: still a plugin id for `PlayerSource.kindFor`, and a live one. */
    fun liveEpisodeId(pluginId: String, itemId: String): String = itemIdFor(pluginId, itemId) + LIVE_SUFFIX

    /**
     * Only an id built by [liveEpisodeId]: a plugin item whose own id happens to be `live` is a
     * movie (`plugin:p:live::0`) or a series (`plugin:p:live::e1`), never a channel.
     */
    fun isLiveEpisode(episodeId: String): Boolean =
        episodeId.startsWith(PREFIX) && episodeId.endsWith(LIVE_SUFFIX) &&
            episodeId.removeSuffix(LIVE_SUFFIX).removePrefix(PREFIX).substringAfter(':', "").let { it.isNotEmpty() && ':' !in it }
}

/** A manifest `#RRGGBB` as opaque ARGB, for `androidx.compose.ui.graphics.Color(Long)`. */
object PluginColors {
    /** Blue-grey 200: neutral next to Xuper's blue and Caracol's green. */
    const val DEFAULT: Long = 0xFFB0BEC5
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun parse(hex: String?): Long =
        hex?.takeIf { HEX.matches(it) }?.let { 0xFF000000 or it.substring(1).toLong(16) } ?: DEFAULT
}
