package com.arkiv.player.data.plugin

/**
 * The numbers of the apiVersion 3 `channels` capability (the En vivo module), pinned to
 * `docs/plugins/contract.json`'s `live` section and `timeoutsMs` by `PluginContractParityTest`.
 * The parsers live in [PluginOutput]; the paging/caching that uses these lives in
 * `data/live/PluginLiveProvider`.
 */
object PluginLiveContract {
    const val API_VERSION = 3
    const val MAX_CATEGORIES = 200
    const val MAX_CHANNELS_PER_PAGE = 500
    /** 500 × 10 = 5000 channels per category at most: well above the ~1000 the spec plans for. */
    const val MAX_PAGES_PER_CATEGORY = 10
    const val MAX_GUIDE_CHANNELS = 50
    const val MAX_GUIDE_WINDOW_MS = 24 * 60 * 60 * 1000L
    const val MAX_GUIDE_ENTRIES_PER_CHANNEL = 100
    const val MAX_CHANNEL_NUMBER = 9999
    const val CATEGORIES_TIMEOUT_MS = 20_000L
    const val CHANNELS_TIMEOUT_MS = 20_000L
    const val GUIDE_TIMEOUT_MS = 20_000L

    /** Playlists one `liveCategories()` answer may declare; beyond it they are dropped (logged). */
    const val MAX_PLAYLISTS = 10
    const val DEFAULT_REFRESH_HOURS = 12
    const val MIN_REFRESH_HOURS = 1
    const val MAX_REFRESH_HOURS = 168
    const val MAX_HIDE_GROUPS = 50
    const val MAX_PLAYLIST_BYTES = 20L * 1024 * 1024
    /** Uncompressed: a gzip EPG is cut at this many bytes after inflating. */
    const val MAX_EPG_BYTES = 50L * 1024 * 1024
    const val MAX_CHANNELS_PER_PROVIDER = 5000
    const val MAX_CATEGORIES_PER_PROVIDER = 500
    const val PLAYLIST_PARSE_BUDGET_MS = 20_000L
    const val EPG_PARSE_BUDGET_MS = 30_000L
    /** Channel ids starting with this are the app's own (playlist entries): a plugin's are dropped. */
    const val RESERVED_ID_PREFIX = "~"
    val PLAYLIST_FORMATS = listOf("m3u")
    val EPG_FORMATS = listOf("xmltv")
}
