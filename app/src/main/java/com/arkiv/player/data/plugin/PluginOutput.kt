package com.arkiv.player.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class PluginItem(
    val id: String, val ref: String, val title: String, val kind: String,
    val year: String = "", val poster: String = "", val backdrop: String = "",
    val overview: String = "", val lang: String = "", val quality: String = "",
    val originalTitle: String = "",
    val genres: List<String> = emptyList(),
    /** 0..10, or null when the plugin gave none. */
    val rating: Double? = null,
    /** 1..1000, or 0 when unknown. */
    val runtimeMinutes: Int = 0,
    val tmdbId: Int = 0,
    /** `tt` + 5..10 digits, or "". */
    val imdbId: String = "",
    val badges: List<String> = emptyList(),
)

/** [ref] non-null only when the plugin declares `browse`: the row gets "Ver más". */
data class PluginRow(val id: String, val title: String, val items: List<PluginItem>, val ref: String? = null)

/** A page of items; [next] is the opaque cursor the app passes back, or null at the end. */
data class PluginPage(val items: List<PluginItem>, val next: String?)

data class PluginSeriesInfo(
    val title: String = "", val poster: String = "", val backdrop: String = "",
    val overview: String = "", val tmdbId: Int = 0, val imdbId: String = "",
    val genres: List<String> = emptyList(), val year: String = "",
)

data class PluginEpisode(
    val season: Int, val number: Int, val ref: String,
    val title: String = "", val still: String = "", val overview: String = "",
    /** `YYYY-MM-DD`, or "". */
    val airDate: String = "",
    val runtimeMinutes: Int = 0,
)

/**
 * One season of a series, for a plugin that keeps each season as its own `series` item: [id] and
 * [ref] are that item's, exactly as a search or Home would list it. [title] is what the season
 * selector shows; [number] is 0 when the plugin gave none (never "the 1st"); [current] when the
 * plugin flagged it as the season whose episodes came in the same answer.
 */
data class PluginSeason(val id: String, val ref: String, val title: String, val number: Int = 0, val current: Boolean = false)

/**
 * [seasons] is empty for a plugin that puts every season in one [episodes] list (the seasons are then
 * read from the episodes) and lists the whole show's seasons, this one included, for a plugin that
 * keeps each as its own title. Optional in the answer: an apiVersion 1 plugin that never heard of it
 * keeps working unchanged.
 */
data class PluginEpisodes(val series: PluginSeriesInfo?, val episodes: List<PluginEpisode>, val seasons: List<PluginSeason> = emptyList())

data class PluginSubtitle(val lang: String, val url: String, val format: String = "")

/**
 * One separately-hosted audio track (apiVersion 1, optional): a dub or an alternate mix the plugin
 * serves as its own file, next to (not inside) the video. [lang] is a short code like `subtitles`'
 * ("es-419"; blank becomes `"und"`), [label] an optional name the player shows verbatim when given.
 */
data class PluginAudioTrack(val lang: String, val url: String, val label: String = "")

/**
 * A Widevine-protected stream's license (apiVersion 2, only for a plugin whose manifest declares
 * the `drm` capability): [licenseUrl] passed the same check as the stream's own URL (a declared
 * host, https), [licenseHeaders] the same filter as the stream's `headers`. The player sends them
 * with the license request and nowhere else.
 */
data class PluginDrm(val licenseUrl: String, val licenseHeaders: Map<String, String> = emptyMap())

data class PluginStream(
    val url: String, val mime: String = "", val headers: Map<String, String> = emptyMap(),
    val subtitles: List<PluginSubtitle> = emptyList(), val durationMs: Long = 0,
    /** 30..86 400, or 0: after that long a failed playback resolves again once. */
    val expiresInSeconds: Int = 0,
    /** At most [MAX_AUDIO_TRACKS]; empty plays exactly as before this field existed. */
    val audioTracks: List<PluginAudioTrack> = emptyList(),
    /** Non-null only for a plugin that declares `drm` (see [PluginDrm]); null is a clear stream, as every stream was before. */
    val drm: PluginDrm? = null,
)

/** A section of the En vivo tab (apiVersion 3, `channels`). [country] is ISO 3166 alpha-2 uppercase, or "". */
data class PluginLiveCategory(val id: String, val title: String, val country: String = "")

/**
 * One channel of a `liveChannels` page (apiVersion 3). It plays either through `resolve([ref])`
 * or, when [stream] is non-null, straight from that inline stream (checked like `resolve()`'s);
 * with both, the stream plays and [ref] is only the fallback. [ref] is "" when the plugin gave
 * none. [logo] follows the poster rules; [number] is 1..[PluginLiveContract.MAX_CHANNEL_NUMBER],
 * or 0 when unknown; [categoryId] is "" when missing or invalid.
 */
data class PluginLiveChannelItem(
    val id: String, val title: String, val ref: String,
    val logo: String = "", val number: Int = 0, val categoryId: String = "",
    val stream: PluginStream? = null,
)

/** A page of channels; [next] is the opaque cursor the app passes back, or null at the end. */
data class PluginLiveChannelPage(val items: List<PluginLiveChannelItem>, val next: String?)

/** One programme of a plugin's `guide` (apiVersion 3), times in epoch milliseconds, `end > start`. */
data class PluginGuideEntry(val channelId: String, val title: String, val startMs: Long, val endMs: Long, val description: String = "")

/**
 * An M3U playlist a plugin declares in its `liveCategories()` answer (apiVersion 3): the APP
 * downloads it (and its XMLTV guide at [epgUrl], "" for none) from a declared host only, every
 * [refreshHours]. [hideGroups] is lowercased and trimmed. With [resolve], an entry plays through the
 * plugin's `resolve(<entry url>)` (tokens) instead of directly.
 */
data class PluginPlaylist(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val epgUrl: String = "",
    val refreshHours: Int = PluginLiveContract.DEFAULT_REFRESH_HOURS,
    val hideGroups: Set<String> = emptySet(),
    val resolve: Boolean = false,
)

/** What a `liveCategories()` answer holds: the plugin's own sections and the playlists it declares. */
data class PluginLiveCatalog(val categories: List<PluginLiveCategory>, val playlists: List<PluginPlaylist>)

/** A plugin answered something the contract doesn't allow; [message] is Spanish, shown to the person. */
class PluginContractException(message: String) : Exception(message)

/**
 * Strict reader of what a plugin returns (apiVersion 1). Lists are forgiving — a bad entry is
 * dropped with a [log] line, the rest survive — while a stream is all-or-nothing: an `http` URL, a
 * host the plugin may not reach, or any DRM field (save the `drm` block of a plugin that declares
 * the capability, see [PluginDrm]) refuses the whole thing. Unknown fields are
 * ignored (forward compatibility). Images must be https and are the one thing NOT host-gated
 * (display-only, loaded by Coil without plugin headers), but an image on an IP literal or a local
 * name is dropped, so a poster can't probe the home network — unless it is exactly one of the
 * servers the person typed in the plugin's settings (see [EffectiveHosts]). The one other exception
 * is [stream]'s `xuper`, for the single plugin [XuperPrivilege.grants].
 */
object PluginOutput {
    const val MAX_SEARCH_ITEMS = 100
    const val MAX_BROWSE_ITEMS = 100
    const val MAX_ROWS = 20
    const val MAX_ROW_ITEMS = 60
    const val MAX_EPISODES = 5000
    /** Sibling seasons an episodes answer may list (`seasons`); the selector is a row of chips. */
    const val MAX_SEASONS = 50
    const val MAX_REF_CHARS = 4096
    const val MAX_CURSOR_CHARS = 2048
    const val MAX_IMAGE_URL_CHARS = 2048
    const val MAX_GENRES = 5
    const val MAX_GENRE_CHARS = 30
    const val MAX_BADGES = 3
    const val MAX_BADGE_CHARS = 20
    const val MIN_EXPIRES_IN_SECONDS = 30
    const val MAX_EXPIRES_IN_SECONDS = 86_400
    const val MAX_RUNTIME_MINUTES = 1000
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxTitleChars` to it. */
    internal const val MAX_TITLE_CHARS = 200
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxTextChars` to it. */
    internal const val MAX_TEXT_CHARS = 2000
    /** Request headers a stream (and a DRM license request) may carry. `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxHeaders` to it. */
    internal const val MAX_HEADERS = 20
    private const val MAX_HEADER_VALUE_CHARS = 4096
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxSubtitles` to it. */
    internal const val MAX_SUBTITLES = 30
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxAudioTracks` to it. */
    internal const val MAX_AUDIO_TRACKS = 8
    private const val MAX_AUDIO_LANG_CHARS = 16
    private const val MAX_AUDIO_LABEL_CHARS = 40
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxSeasonNumber` to it. */
    internal const val MAX_SEASON = 999
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.maxEpisodeNumber` to it. */
    internal const val MAX_EPISODE_NUMBER = 99_999

    /**
     * The item kind of a live channel (apiVersion 2): it has no duration and no episodes, its `ref`
     * goes to `resolve` and plays as live, straight from its card. See [allowsLive].
     */
    const val KIND_LIVE = "live"
    /**
     * The apiVersion that brought [KIND_LIVE]. Its own constant, not `ManifestParser.SUPPORTED_API`:
     * that one is the app's ceiling and moves with every round; this one names when live arrived
     * and never moves. `internal`, not public API: exposed so a test can pin `contract.json`'s
     * `output.liveKindApiVersion` to it.
     */
    internal const val LIVE_API_VERSION = 2
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.itemKinds` to it. */
    internal val ITEM_KINDS = listOf("movie", "series", KIND_LIVE)

    /** Whether a plugin declaring [apiVersion] may answer [KIND_LIVE] items; below it they are dropped like any invalid kind. */
    fun allowsLive(apiVersion: Int): Boolean = apiVersion >= LIVE_API_VERSION

    val ID = Regex("^[A-Za-z0-9._~-]{1,128}$")
    val IMDB = Regex("^tt\\d{5,10}$")
    val AIR_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val MIME = Regex("^[a-z]+/[A-Za-z0-9.+-]{1,100}$")
    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")
    /**
     * The Stream keys that spell DRM. Every one of them refuses the stream, always has, with one
     * exception: [DRM_FIELD] on its own, from a plugin that declares the `drm` capability, is the
     * [PluginDrm] block. `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.drmKeys` to it.
     */
    internal val DRM_KEYS = setOf("drm", "license", "licenseUrl", "drmLicenseUrl", "keySystem", "widevine")
    /** `internal`, not public API: exposed only so a test can pin `contract.json`'s `output.drm.field` to it. */
    internal const val DRM_FIELD = "drm"
    /** The `type` values a [DRM_FIELD] block may carry; Widevine is the one Kino's player negotiates. `internal`, not public API: pinned to `contract.json`'s `output.drm.types`. */
    internal val DRM_TYPES = listOf("widevine")

    /**
     * `search` (≤ [MAX_SEARCH_ITEMS]) or `browse` (≤ [MAX_BROWSE_ITEMS]): an `Item[]` or a
     * `{ items, next }` page. [allowNext] is whether the plugin declares `browse`: without it a
     * `next` is dropped (logged), since nothing could ask for it. [allowLive] is [allowsLive] of
     * the plugin's apiVersion; off by default, so a caller that never heard of live channels keeps
     * dropping them exactly as before.
     */
    fun page(
        json: String,
        max: Int,
        allowSeries: Boolean,
        allowNext: Boolean,
        hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        allowLive: Boolean = false,
        log: (String) -> Unit = {},
    ): PluginPage {
        val value = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull()
        val array: JSONArray
        var next: String? = null
        when (value) {
            is JSONArray -> array = value
            is JSONObject -> {
                array = value.optJSONArray("items") ?: return PluginPage(emptyList(), null).also { log("page: no items") }
                next = cursor(value.opt("next"), allowNext, log)
            }
            else -> return PluginPage(emptyList(), null).also { log("the answer is not a list or a page") }
        }
        return PluginPage(itemsOf(array, max, allowSeries, allowLive, hosts, log), next)
    }

    fun rows(
        json: String,
        allowSeries: Boolean,
        allowBrowse: Boolean,
        hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        allowLive: Boolean = false,
        log: (String) -> Unit = {},
    ): List<PluginRow> {
        val array = runCatching { JSONArray(json) }.getOrNull()
            ?: return emptyList<PluginRow>().also { log("home: the answer is not a JSON array") }
        val seen = HashSet<String>()
        val out = ArrayList<PluginRow>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_ROWS) { log("home: rows beyond $MAX_ROWS dropped"); break }
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id)) { log("home: row $i has an invalid id"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("home: row $id has no title"); continue }
            val items = itemsOf(o.optJSONArray("items") ?: JSONArray(), MAX_ROW_ITEMS, allowSeries, allowLive, hosts, log)
            if (items.isEmpty()) continue
            // Home keys its Lazy rows by this id: a repeat would crash the whole screen.
            if (!seen.add(id)) { log("home: duplicate row $id dropped"); continue }
            val ref = (o.opt("ref") as? String)?.takeIf { it.isNotEmpty() }
            val kept = when {
                ref == null -> null
                !allowBrowse -> null.also { log("home: row $id has a ref but the plugin doesn't declare browse") }
                ref.length > MAX_REF_CHARS -> null.also { log("home: row $id ref too long") }
                else -> ref
            }
            out += PluginRow(id, title, items, kept)
        }
        return out
    }

    fun episodes(json: String, log: (String) -> Unit = {}, hosts: EffectiveHosts = EffectiveHosts(emptyList())): PluginEpisodes {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: throw PluginContractException("La lista de capítulos no es válida")
        val list = o.optJSONArray("episodes") ?: throw PluginContractException("El plugin no devolvió capítulos")
        val seen = HashSet<Pair<Int, Int>>()
        val eps = ArrayList<PluginEpisode>()
        for (i in 0 until list.length()) {
            if (eps.size >= MAX_EPISODES) { log("episodes: beyond $MAX_EPISODES dropped"); break }
            val e = list.optJSONObject(i) ?: continue
            val season = e.optInt("season", 1).takeIf { it in 1..MAX_SEASON } ?: 1
            val number = e.optInt("number", -1)
            if (number !in 1..MAX_EPISODE_NUMBER) { log("episodes: #$i has no valid number"); continue }
            val ref = e.optString("ref")
            if (ref.isEmpty() || ref.length > MAX_REF_CHARS) { log("episodes: #$i has no valid ref"); continue }
            if (!seen.add(season to number)) { log("episodes: duplicate S${season}E$number dropped"); continue }
            eps += PluginEpisode(
                season, number, ref, text(e, "title", MAX_TITLE_CHARS), image(e, "still", hosts), text(e, "overview", MAX_TEXT_CHARS),
                airDate = text(e, "airDate", 10).takeIf { AIR_DATE.matches(it) }.orEmpty(),
                runtimeMinutes = runtime(e),
            )
        }
        val series = o.optJSONObject("series")?.let {
            val ids = it.optJSONObject("ids")
            PluginSeriesInfo(
                title = text(it, "title", MAX_TITLE_CHARS), poster = image(it, "poster", hosts),
                backdrop = image(it, "backdrop", hosts), overview = text(it, "overview", MAX_TEXT_CHARS),
                // `ids` is the SDK v1 shape; the flat tmdbId/imdbId of the first contract still work.
                tmdbId = (ids?.let(::tmdb) ?: 0).takeIf { t -> t > 0 } ?: it.optInt("tmdbId", 0).coerceAtLeast(0),
                imdbId = ids?.let(::imdb)?.takeIf { s -> s.isNotEmpty() } ?: text(it, "imdbId", 20),
                genres = strings(it, "genres", MAX_GENRES, MAX_GENRE_CHARS),
                year = text(it, "year", 10),
            )
        }
        return PluginEpisodes(series, eps, seasonsOf(o, log))
    }

    /**
     * The optional `seasons` of an episodes answer (see [PluginSeason]): forgiving like every list,
     * a bad entry is dropped with a [log] line. A wrong `number` or `current` drops the field, not
     * the season, so a plugin's typo costs it a chip's number, never the whole selector.
     */
    private fun seasonsOf(o: JSONObject, log: (String) -> Unit): List<PluginSeason> {
        if (!o.has("seasons")) return emptyList()
        val array = o.optJSONArray("seasons") ?: return emptyList<PluginSeason>().also { log("seasons: not a list, ignored") }
        val seen = HashSet<String>()
        val out = ArrayList<PluginSeason>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_SEASONS) { log("seasons: beyond $MAX_SEASONS dropped"); break }
            val s = array.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (!ID.matches(id)) { log("seasons: #$i has an invalid id"); continue }
            val ref = s.optString("ref")
            if (ref.isEmpty() || ref.length > MAX_REF_CHARS) { log("seasons: $id has no valid ref"); continue }
            val title = text(s, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("seasons: $id has no title"); continue }
            if (!seen.add(id)) { log("seasons: duplicate $id dropped"); continue }
            out += PluginSeason(
                id, ref, title,
                number = (s.opt("number") as? Number)?.toInt()?.takeIf { it in 1..MAX_SEASON } ?: 0,
                current = s.opt("current") == true,
            )
        }
        return out
    }

    private val COUNTRY = Regex("^[A-Z]{2}$")

    /**
     * `liveCategories()` (apiVersion 3): `[{ id, title, country? }]`, ≤ [PluginLiveContract.MAX_CATEGORIES],
     * mixed with `{ playlist: {...} }` declarations (≤ [PluginLiveContract.MAX_PLAYLISTS], not counted
     * as categories; see [playlistOf]), or a single `{ playlist: {...} }` object. Forgiving like every
     * list: a bad entry is dropped with a [log] line.
     */
    fun liveCategories(json: String, hosts: EffectiveHosts = EffectiveHosts(emptyList()), log: (String) -> Unit = {}): PluginLiveCatalog {
        val value = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull()
        val array = when {
            value is JSONArray -> value
            value is JSONObject && value.has("playlist") -> JSONArray().put(value)
            else -> return PluginLiveCatalog(emptyList(), emptyList()).also { log("liveCategories: the answer is not a JSON array") }
        }
        val seen = HashSet<String>()
        val categories = ArrayList<PluginLiveCategory>()
        val playlists = ArrayList<PluginPlaylist>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.has("playlist")) {
                val p = o.optJSONObject("playlist") ?: run { log("liveCategories: #$i playlist is not an object"); null } ?: continue
                if (playlists.size >= PluginLiveContract.MAX_PLAYLISTS) { log("liveCategories: playlists beyond ${PluginLiveContract.MAX_PLAYLISTS} dropped"); continue }
                playlistOf(p, hosts, log)?.let { playlists += it }
                continue
            }
            if (categories.size >= PluginLiveContract.MAX_CATEGORIES) { log("liveCategories: beyond ${PluginLiveContract.MAX_CATEGORIES} dropped"); continue }
            val id = o.optString("id")
            if (!ID.matches(id)) { log("liveCategories: #$i has an invalid id"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("liveCategories: $id has no title"); continue }
            // No plugin section exists behind the 18+ lock, same as items.
            if (o.opt("adult") == true) { log("liveCategories: $id adult, dropped"); continue }
            if (!seen.add(id)) { log("liveCategories: duplicate $id dropped"); continue }
            val country = (o.opt("country") as? String)?.trim()?.uppercase()?.takeIf { COUNTRY.matches(it) }.orEmpty()
            categories += PluginLiveCategory(id, title, country)
        }
        return PluginLiveCatalog(categories, playlists)
    }

    /** A `{ playlist: {...} }` declaration (see [PluginPlaylist]), or null (logged) when it can't be used. */
    private fun playlistOf(p: JSONObject, hosts: EffectiveHosts, log: (String) -> Unit): PluginPlaylist? {
        val url = p.optString("url")
        if (p.optString("format") !in PluginLiveContract.PLAYLIST_FORMATS) { log("playlist: unknown format"); return null }
        // Strict on purpose: the list and its guide are downloaded by the app, never under liveStreamHosts "any".
        if (!allowsUrl(url, hosts)) { log("playlist: ${url.take(100)} is not a declared host"); return null }
        val epg = p.optJSONObject("epg")?.takeIf { it.optString("format") in PluginLiveContract.EPG_FORMATS }?.optString("url")
            ?.takeIf { allowsUrl(it, hosts).also { ok -> if (!ok) log("playlist: epg host not declared, guide dropped") } }.orEmpty()
        val hours = (p.opt("refreshHours") as? Number)?.toInt()
            ?.takeIf { it in PluginLiveContract.MIN_REFRESH_HOURS..PluginLiveContract.MAX_REFRESH_HOURS } ?: PluginLiveContract.DEFAULT_REFRESH_HOURS
        val hide = p.optJSONArray("hideGroups")?.let { a ->
            (0 until minOf(a.length(), PluginLiveContract.MAX_HIDE_GROUPS))
                .mapNotNull { (a.opt(it) as? String)?.trim()?.lowercase()?.take(100)?.takeIf(String::isNotEmpty) }.toSet()
        } ?: emptySet()
        return PluginPlaylist(url, headersOf(p.optJSONObject("headers")), epg, hours, hide, p.opt("resolve") == true)
    }

    /** Whether [url] passes the stream URL check ([checkUrl]): a declared host or a typed server, with the scheme rule. */
    internal fun allowsUrl(url: String, hosts: EffectiveHosts): Boolean = runCatching { checkUrl(url, hosts.strict, "") }.isSuccess

    /**
     * `liveChannels({ categoryId, cursor })` (apiVersion 3): a `{ items, next? }` page or a plain
     * list, ≤ [PluginLiveContract.MAX_CHANNELS_PER_PAGE] channels. Each needs a valid `ref` or an
     * inline `stream` (read by [streamOf], [allowDrm] as for `resolve`); a refused stream drops the
     * channel. Ids starting with [PluginLiveContract.RESERVED_ID_PREFIX] are the app's own and are
     * dropped. `logo` follows the poster rules ([image]); `number` outside
     * 1..[PluginLiveContract.MAX_CHANNEL_NUMBER] becomes 0 (unknown).
     */
    fun liveChannels(
        json: String,
        hosts: EffectiveHosts = EffectiveHosts(emptyList()),
        allowDrm: Boolean = false,
        log: (String) -> Unit = {},
    ): PluginLiveChannelPage {
        val value = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull()
        val array: JSONArray
        var next: String? = null
        when (value) {
            is JSONArray -> array = value
            is JSONObject -> {
                array = value.optJSONArray("items") ?: return PluginLiveChannelPage(emptyList(), null).also { log("liveChannels: no items") }
                next = cursor(value.opt("next"), allowNext = true, log)
            }
            else -> return PluginLiveChannelPage(emptyList(), null).also { log("liveChannels: the answer is not a list or a page") }
        }
        val seen = HashSet<String>()
        val out = ArrayList<PluginLiveChannelItem>()
        for (i in 0 until array.length()) {
            if (out.size >= PluginLiveContract.MAX_CHANNELS_PER_PAGE) { log("liveChannels: beyond ${PluginLiveContract.MAX_CHANNELS_PER_PAGE} dropped"); break }
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id)) { log("liveChannels: #$i has an invalid id"); continue }
            if (id.startsWith(PluginLiveContract.RESERVED_ID_PREFIX)) { log("liveChannels: $id uses a reserved id"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("liveChannels: $id has no title"); continue }
            if (o.opt("adult") == true) { log("liveChannels: $id adult, dropped"); continue }
            val ref = o.optString("ref")
            if (ref.length > MAX_REF_CHARS) { log("liveChannels: $id has an invalid ref"); continue }
            val streamJson = o.optJSONObject("stream")
            val stream = streamJson?.let {
                runCatching { streamOf(it, hosts, null, allowDrm) }
                    .onFailure { e -> log("liveChannels: $id stream refused: ${e.message}") }.getOrNull()
            }
            if (streamJson != null && stream == null) continue
            if (ref.isEmpty() && stream == null) { log("liveChannels: $id has neither a ref nor a stream"); continue }
            if (!seen.add(id)) { log("liveChannels: duplicate $id dropped"); continue }
            val number = (o.opt("number") as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= 1.0 && it <= PluginLiveContract.MAX_CHANNEL_NUMBER }?.toInt() ?: 0
            val categoryId = o.optString("categoryId").takeIf { ID.matches(it) }.orEmpty()
            out += PluginLiveChannelItem(id, title, ref, image(o, "logo", hosts), number, categoryId, stream)
        }
        return PluginLiveChannelPage(out, next)
    }

    /**
     * `guide({ channelIds, from, to })` (apiVersion 3, optional): `[{ channelId, title, start, end,
     * description? }]` with times in epoch MILLISECONDS. Only entries for [channelIds], with
     * `end > start`, that overlap [fromMs]..[toMs]; ≤ [PluginLiveContract.MAX_GUIDE_ENTRIES_PER_CHANNEL]
     * per channel, one per start time, each channel's sorted by start.
     */
    fun guide(json: String, channelIds: Set<String>, fromMs: Long, toMs: Long, log: (String) -> Unit = {}): List<PluginGuideEntry> {
        val array = runCatching { JSONArray(json) }.getOrNull()
            ?: return emptyList<PluginGuideEntry>().also { log("guide: the answer is not a JSON array") }
        val perChannel = LinkedHashMap<String, MutableList<PluginGuideEntry>>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val channelId = o.optString("channelId")
            if (channelId !in channelIds) { log("guide: #$i is for a channel that was not asked for"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("guide: #$i has no title"); continue }
            val start = (o.opt("start") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong()
            val end = (o.opt("end") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong()
            if (start == null || end == null || end <= start) { log("guide: #$i has an invalid start/end"); continue }
            if (end <= fromMs || start >= toMs) continue
            val list = perChannel.getOrPut(channelId) { ArrayList() }
            if (list.size >= PluginLiveContract.MAX_GUIDE_ENTRIES_PER_CHANNEL) { log("guide: $channelId beyond ${PluginLiveContract.MAX_GUIDE_ENTRIES_PER_CHANNEL} dropped"); continue }
            if (list.any { it.startMs == start }) continue
            list += PluginGuideEntry(channelId, title, start, end, text(o, "description", MAX_TEXT_CHARS))
        }
        return perChannel.values.flatMap { l -> l.sortedBy { it.startMs } }
    }

    /**
     * [xuper] is non-null ONLY for the one plugin [XuperPrivilege.grants] (`PluginContentSource`
     * enforces it). For that plugin, a URL the native bridge itself resolved, byte for byte, skips
     * the https and declared-host checks (Magis's CDN is plain http on per-session hosts), and the
     * stream plays with the bridge's headers instead of any the script returned: the script never
     * holds them. Every other URL, of that plugin or any other, is checked exactly as always.
     *
     * [allowDrm] is whether the plugin's manifest declares the `drm` capability (apiVersion 2): only
     * then is a `drm` block read (see [drmOf]); off, the default, every DRM-shaped key refuses the
     * stream exactly as it always did.
     */
    fun stream(json: String, hosts: EffectiveHosts, xuper: XuperStreams? = null, allowDrm: Boolean = false): PluginStream {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: throw PluginContractException("El plugin no devolvió un video")
        return streamOf(o, hosts, xuper, allowDrm)
    }

    /** [stream]'s rules on an already-parsed object: also a `liveChannels` item's inline `stream`. */
    internal fun streamOf(o: JSONObject, hosts: EffectiveHosts, xuper: XuperStreams? = null, allowDrm: Boolean = false): PluginStream {
        // liveStreamHosts "any" relaxes the stream's own URL only: its subtitles, audio tracks and
        // DRM license stay on the declared hosts and typed servers.
        val strict = hosts.strict
        val drm = drmOf(o, strict, allowDrm)
        val url = o.optString("url")
        val native = xuper?.headersFor(url)
        if (native == null) checkUrl(url, hosts, "El video")
        val mime = o.optString("mime").trim()
        if (mime.isNotEmpty() && !MIME.matches(mime)) throw PluginContractException("El tipo de video \"${mime.take(100)}\" no es válido")
        val headers = if (native != null) LinkedHashMap(native) else headersOf(o.optJSONObject("headers"))
        val subtitles = ArrayList<PluginSubtitle>()
        o.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_SUBTITLES)) {
                val s = arr.optJSONObject(i) ?: continue
                val su = s.optString("url")
                if (xuper?.headersFor(su) == null && runCatching { checkUrl(su, strict, "El subtítulo") }.isFailure) continue
                val format = s.optString("format").takeIf { it == "vtt" || it == "srt" }.orEmpty()
                subtitles += PluginSubtitle(text(s, "lang", 20).ifBlank { "und" }, su, format)
            }
        }
        val audioTracks = ArrayList<PluginAudioTrack>()
        o.optJSONArray("audioTracks")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_AUDIO_TRACKS)) {
                val a = arr.optJSONObject(i) ?: continue
                val au = a.optString("url")
                if (xuper?.headersFor(au) == null && runCatching { checkUrl(au, strict, "El audio") }.isFailure) continue
                // One URL is one merged child: twice would be two menu rows and, on a failure,
                // `fallbackAudioTracks` would blame both. The first entry wins, as for item ids.
                if (audioTracks.any { it.url == au }) continue
                audioTracks += PluginAudioTrack(
                    text(a, "lang", MAX_AUDIO_LANG_CHARS).ifBlank { "und" }, au,
                    text(a, "label", MAX_AUDIO_LABEL_CHARS),
                )
            }
        }
        val expires = o.optInt("expiresInSeconds", 0).takeIf { it in MIN_EXPIRES_IN_SECONDS..MAX_EXPIRES_IN_SECONDS } ?: 0
        return PluginStream(url, mime, headers, subtitles, o.optLong("durationMs", 0L).coerceAtLeast(0L), expires, audioTracks, drm)
    }

    /**
     * The optional `drm` block of a Stream, `{ type: "widevine", licenseUrl, licenseHeaders? }`,
     * read ONLY when [allowDrm] (the plugin declares the capability) and it is the only DRM-shaped
     * key present. Any other of [DRM_KEYS], with or without the capability and even next to a valid
     * block, refuses the stream with the sentence it always had: those keys never meant anything
     * to Kino and still don't. The license URL passes [checkUrl] like the stream's own (no Xuper
     * carve-out: the bridge never resolves a license), and its headers the same filter as `headers`.
     */
    private fun drmOf(o: JSONObject, hosts: EffectiveHosts, allowDrm: Boolean): PluginDrm? {
        val present = DRM_KEYS.filter { o.has(it) }
        if (present.isEmpty()) return null
        if (!allowDrm || present != listOf(DRM_FIELD)) throw PluginContractException("El video tiene DRM y los plugins no lo soportan")
        val d = o.optJSONObject(DRM_FIELD) ?: throw PluginContractException("El DRM del video no es válido")
        if (d.optString("type") !in DRM_TYPES) throw PluginContractException("El video usa un DRM que Kino no soporta")
        val licenseUrl = d.optString("licenseUrl")
        checkUrl(licenseUrl, hosts, "La licencia del video")
        return PluginDrm(licenseUrl, headersOf(d.optJSONObject("licenseHeaders")))
    }

    /**
     * Up to [MAX_HEADERS] request headers of [h], in order: a name that isn't a token, one of
     * [FORBIDDEN_HEADERS], a non-string value, or one over [MAX_HEADER_VALUE_CHARS] or with a line
     * break is skipped, never fatal. Shared by a Stream's `headers` and a DRM block's `licenseHeaders`.
     */
    internal fun headersOf(h: JSONObject?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (h == null) return out
        for (k in h.keys()) {
            if (out.size >= MAX_HEADERS) break
            val v = h.opt(k) as? String ?: continue
            if (!HEADER_NAME.matches(k) || k.lowercase() in FORBIDDEN_HEADERS) continue
            if (v.length > MAX_HEADER_VALUE_CHARS || '\n' in v || '\r' in v) continue
            out[k] = v
        }
        return out
    }

    /**
     * A stream, subtitle, audio or license URL, checked in this order: exactly a server the person
     * typed passes at once (its own scheme, http included); otherwise the scheme must pass
     * [EffectiveHosts.allowsScheme] (https, or plain http only on a host the person approved as
     * insecure -- the same rule the host gate applies when the player then requests it), and only
     * then must the host be a declared one. With [EffectiveHosts.anyPublicLiveHost] (only ever the
     * stream's own URL, see [streamOf]) any public host passes, over http or https, never a local one.
     */
    private fun checkUrl(url: String, hosts: EffectiveHosts, what: String) {
        val u = url.toHttpUrlOrNull() ?: throw PluginContractException("$what tiene una dirección inválida")
        if (hosts.userHostFor(u) != null) return
        if (hosts.anyPublicLiveHost) {
            if (HostRules.isPublicIpv4Literal(u.host) || !HostRules.isLocalAddress(u.host)) return
            throw PluginContractException("$what apunta a ${u.host.take(100)}, una dirección local")
        }
        if (!hosts.allowsScheme(u)) throw PluginContractException("$what debe usar https")
        if (!HostRules.matches(u.host, hosts.declared)) throw PluginContractException("$what apunta a ${u.host.take(100)}, que el plugin no declaró")
    }

    private fun cursor(raw: Any?, allowNext: Boolean, log: (String) -> Unit): String? {
        val next = (raw as? String)?.takeIf { it.isNotEmpty() } ?: return null
        if (!allowNext) { log("page: next dropped, the plugin doesn't declare browse"); return null }
        if (next.length > MAX_CURSOR_CHARS) { log("page: next longer than $MAX_CURSOR_CHARS dropped"); return null }
        return next
    }

    private fun itemsOf(array: JSONArray, max: Int, allowSeries: Boolean, allowLive: Boolean, hosts: EffectiveHosts, log: (String) -> Unit): List<PluginItem> {
        val seen = HashSet<String>()
        val out = ArrayList<PluginItem>()
        for (i in 0 until array.length()) {
            if (out.size >= max) { log("items beyond $max dropped"); break }
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (!ID.matches(id)) { log("item #$i: invalid id"); continue }
            val ref = o.optString("ref")
            if (ref.isEmpty() || ref.length > MAX_REF_CHARS) { log("item $id: invalid ref"); continue }
            val title = text(o, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) { log("item $id: no title"); continue }
            val kind = o.optString("kind")
            if (kind !in ITEM_KINDS) { log("item $id: invalid kind '${kind.take(20)}'"); continue }
            if (kind == "series" && !allowSeries) { log("item $id: series without the episodes capability"); continue }
            // Silently, like any invalid item: an apiVersion 1 plugin never declared it could go live.
            if (kind == KIND_LIVE && !allowLive) { log("item $id: live needs apiVersion $LIVE_API_VERSION"); continue }
            // Adult titles never reach a screen: no plugin section exists behind the 18+ lock yet.
            if (o.opt("adult") == true) { log("item $id: adult, dropped"); continue }
            if (!seen.add(id)) continue
            val ids = o.optJSONObject("ids")
            out += PluginItem(
                id = id, ref = ref, title = title, kind = kind, year = text(o, "year", 10),
                poster = image(o, "poster", hosts), backdrop = image(o, "backdrop", hosts),
                overview = text(o, "overview", MAX_TEXT_CHARS), lang = text(o, "lang", 20),
                quality = text(o, "quality", 20),
                originalTitle = text(o, "originalTitle", MAX_TITLE_CHARS),
                genres = strings(o, "genres", MAX_GENRES, MAX_GENRE_CHARS),
                rating = (o.opt("rating") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..10.0 },
                // A channel has no length: whatever the plugin put there is ignored, never shown.
                runtimeMinutes = if (kind == KIND_LIVE) 0 else runtime(o),
                tmdbId = ids?.let(::tmdb) ?: 0,
                imdbId = ids?.let(::imdb).orEmpty(),
                badges = strings(o, "badges", MAX_BADGES, MAX_BADGE_CHARS),
            )
        }
        return out
    }

    private fun tmdb(ids: JSONObject): Int = (ids.opt("tmdb") as? Number)?.toLong()?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: 0

    private fun imdb(ids: JSONObject): String = (ids.opt("imdb") as? String)?.takeIf { IMDB.matches(it) }.orEmpty()

    private fun runtime(o: JSONObject): Int = (o.opt("runtimeMinutes") as? Number)?.toInt()?.takeIf { it in 1..MAX_RUNTIME_MINUTES } ?: 0

    /** Up to [max] non-blank strings, each cut to [maxChars]; anything else in the list is skipped. */
    private fun strings(o: JSONObject, key: String, max: Int, maxChars: Int): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            if (out.size >= max) break
            val s = (a.opt(i) as? String)?.trim()?.take(maxChars)?.takeIf { it.isNotEmpty() } ?: continue
            if (s !in out) out += s
        }
        return out
    }

    private fun text(o: JSONObject, key: String, max: Int): String =
        when (val v = o.opt(key)) {
            is String -> v
            is Number -> v.toString()
            else -> ""
        }.trim().take(max)

    /**
     * https, ≤ [MAX_IMAGE_URL_CHARS] chars, and never an IP literal or a local name: a poster must
     * not be a LAN probe. The exception is a URL on a server the person typed (scheme, host and
     * port exactly): their own Jellyfin's posters are that server's.
     */
    private fun image(o: JSONObject, key: String, hosts: EffectiveHosts): String = imageUrl((o.opt(key) as? String).orEmpty(), hosts)

    /** [image]'s rule for a bare string: the URL, trimmed, or "" when it may not be shown. */
    internal fun imageUrl(raw: String, hosts: EffectiveHosts): String {
        val v = raw.trim()
        if (v.length > MAX_IMAGE_URL_CHARS) return ""
        val url = v.toHttpUrlOrNull() ?: return ""
        if (hosts.userHostFor(url) != null) return v
        if (url.scheme != "https" || !v.startsWith("https://")) return ""
        return if (HostRules.isLocalAddress(url.host)) "" else v
    }
}
