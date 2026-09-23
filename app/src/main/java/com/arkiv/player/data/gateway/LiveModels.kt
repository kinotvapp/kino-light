package com.arkiv.player.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LiveCategory(val id: Int, val name: String)

data class LiveChannel(
    val code: String,
    val name: String,
    val number: Int,
    val logo: String?,
    /**
     * Whether the channel comes from an adults category.
     *
     * Lives on the CHANNEL and not only on the category because the channel travels on its own
     * all the way to the player -- zapping, deep link, the recents list itself -- and by then
     * there's no category on hand anymore. With the mark carried on it, the "this doesn't get
     * logged to history" rule applies at the write point and doesn't depend on how it got there.
     *
     * Defaults to `false`: whoever doesn't know, doesn't mark it. The paths that rebuild a
     * `LiveChannel` with no category on hand (favorites, `CountryChannels`' cache, recents,
     * `PlayerViewModel.loadLive`'s zapping fallback) don't pass it and are left with the default.
     */
    val adult: Boolean = false,
)

/** Times in epoch **seconds**, as the portal sends them. */
data class LiveProgram(val title: String, val start: Long, val end: Long, val synopsis: String)

/**
 * A CDN where the signal can be requested, with ITS OWN `authBase`.
 *
 * This is what the local proxy needs to talk to the CDN: the deliberate exception to the
 * `Gateway*` models' opaque-`ref` pattern -- here the app really does need the data in the clear
 * to build each segment's headers.
 *
 * The signing token travels inside that url, so they go together: signing with one CDN's token
 * against another one's host is exactly the pair a CDN rejects with a 401.
 */
data class ChannelCdn(val cflHost: String, val authBase: String) {
    /** The `token=<32 hex>` inside `authBase`; it's the only thing the signature needs. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

data class LiveSession(
    val cflHost: String,
    val authBase: String,
    val license: String,
    /** The code the channel was REQUESTED with: the key its session is cached and invalidated
     *  by, and the name it shows up as in the logs. NOT useful for talking to the CDN. */
    val channel: String,
    val expiresAt: Long,
    /**
     * What the signal is called ON THE CDN -- what goes in `/live/{...}.m3u8`.
     *
     * Doesn't always match [channel]. Measured on 2026-08-14: `cyx-RCNHD` is served as
     * `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`, while `cyx_9881490555304164628541864337` is the same in
     * both. Using [channel] here asked the CDN for a different signal than the one the license
     * being sent authorizes, and it answered 401 -- the channel was left loading forever. The
     * channels that worked were exactly the ones where the two match.
     *
     * Defaults to [channel], the usual behavior: correct for channels where they match, and for a
     * gateway that doesn't send the field yet.
     */
    val playCode: String = channel,
    /**
     * EVERY CDN where this signal can be requested, in the order the portal gave them.
     *
     * Measured on 2026-08-14: the portal returns three live entries and only the first was used.
     * That day the CDN answered 401 twice and the channel ended (`EndReached`) while another host
     * was available in that same response. With the list, a rejection becomes "try the next one"
     * instead of a dead channel.
     *
     * Defaults to the first one alone -- what there was before -- for a gateway that doesn't send
     * the list yet.
     */
    val cdns: List<ChannelCdn> = listOf(ChannelCdn(cflHost, authBase)),
) {
    /** The `token=<32 hex>` inside `authBase`; it's the only thing the signature needs. */
    val token: String get() = Regex("token=([0-9A-Fa-f]{32})").find(authBase)?.groupValues?.get(1).orEmpty()
}

/** An item from the Magis catalog (a section's movie/video). */
data class CatalogItem(
    val id: String,
    val title: String,
    val poster: String?,
    val durationS: Int,
    /**
     * Whether it came from an adults section. Lives on the ITEM and not only on the section
     * because the item travels on its own all the way to the player, and by then the "this
     * doesn't get logged to history" rule has to be applicable with no knowledge of where it came
     * from. Same thing done with [LiveChannel].
     */
    val adult: Boolean = false,
    /**
     * The ref the stream is requested from the portal with on playback (`MagisLive`/`MagisResolve`).
     * It's the ONLY playable thing the item carries: resolution does NOT take [id] (the portal's
     * contentId), it takes this string. It's a LOCAL descriptor -`MagisRef(id, type, 0).encode()`,
     * see `MagisLiveCatalog.kt`-: nobody signs or mints it, so it doesn't expire either (it used
     * to, after 24h, back when the gateway built it -see `MagisRef`'s KDoc-). The app still treats
     * it as opaque and never interprets it, but no longer for cryptographic reasons -- by contract.
     *
     * The `""` default is defensive, not something that happens today: the only place that builds
     * a [CatalogItem] (`MagisLiveCatalog`) already discards any blank `contentId`, so in
     * practice this field never comes out empty. If it ever were, the item is still listed -- it
     * can be seen -- but doesn't play; see [playable].
     */
    val ref: String = "",
    /** What the portal says it is: "movie", "teleplay"… See [isSeries]. */
    val type: String = "movie",
    /** The portal's `tags` as sent (IMDb-style genres: "Action", "Sci-Fi"…). The home's rows are
     *  built from these -- see `MagisHomeClassifier`. */
    val genres: List<String> = emptyList(),
    /** The portal's `score` (IMDb-like, 0–10). Null when it didn't send one. */
    val score: Double? = null,
    /** The landscape (1920×1080) image, the portal's `fileType "poster"`. [poster] is the portrait
     *  `icon`. */
    val backdrop: String? = null,
    val description: String = "",
    /** Chapters the season declares (`volumnCount`, else `updateCount`); 0 for movies/unknown. */
    val episodeCount: Int = 0,
) {
    /**
     * Whether its chapters have to be requested before playing, instead of playing it directly.
     *
     * Decided by [type] and not by opening [ref] on purpose: the ref is opaque to the app, and
     * keeping it that way is what lets the gateway change its shape without shipping a new APK.
     */
    val isSeries: Boolean get() = type == "teleplay"

    val playable: Boolean get() = ref.isNotBlank()
}

/** A catalog section, with its first items (the portal sends them in the same response). */
data class CatalogSection(
    val id: Int,
    val name: String,
    val adult: Boolean,
    val items: List<CatalogItem>,
)

data class LiveSignature(val moment: Long, val sign2: String)

/**
 * What [com.arkiv.player.ui.live.LiveViewModel] needs from the live catalog -- narrow on purpose:
 * `resolver`/`firmar` belong to [com.arkiv.player.ui.live.LiveController] (session resolution and
 * segment signing), a completely different consumer with its own lifecycle; putting them here
 * would only tie this interface to a consumer that doesn't use it.
 *
 * Implemented by [com.arkiv.player.data.magis.MagisLiveCatalog], which talks to the portal
 * directly (the gateway client used to implement it). In tests, a lightweight double implements
 * it directly (see `FakeLiveApi` in `LiveViewModelTest.kt`) touching no network.
 */
interface LiveCatalogGateway {
    /**
     * @param includeAdults also asks for the 18+ category. The gateway filters it out by
     *   DEFAULT, so without this it doesn't come -- see `AdultsLock`. It's a remote-control lock,
     *   not a security boundary: whoever builds the request by hand can set it either way.
     */
    suspend fun categories(includeAdults: Boolean = false): List<LiveCategory>
    suspend fun channels(category: Int): List<LiveChannel>
    suspend fun epg(codes: List<String>): Pair<Map<String, List<LiveProgram>>, List<String>>
}
