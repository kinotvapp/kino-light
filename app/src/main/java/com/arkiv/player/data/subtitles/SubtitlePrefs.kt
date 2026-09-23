package com.arkiv.player.data.subtitles

import android.content.Context
import com.arkiv.player.playback.TrackLang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What to do with subtitles on startup. See [PlaybackPrefs.subtitleLangs]. */
enum class SubtitleMode { AUTO, OFF }

/**
 * Playback preferences: audio language, subtitle language and style. Persisted locally (the sync
 * to the TV this used to have was removed along with the TV↔phone remote). The languages are
 * ORDERED LISTS: the player walks them and takes the first track that exists, instead of settling
 * for the file's first one.
 */
data class PlaybackPrefs(
    /** In what ORDER to pick the audio track. That's all: it says nothing about what languages you understand. */
    val audioLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL),
    /**
     * The languages you understand well enough to not need subtitles. It's a SET: order means
     * nothing here, it's kept as a list only to serialize it with the same helper as the other
     * two. Kept separate from [audioLangs] on purpose: hand-picking a title's Japanese audio moves
     * it to the top of [audioLangs] (see `LangPromotion`), and if that same list decided
     * subtitles, that choice would leave the anime in Japanese and with NO subtitles forever.
     */
    val understoodLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH, TrackLang.DUAL),
    val subtitleLangs: List<TrackLang> = listOf(TrackLang.LATINO, TrackLang.CASTELLANO, TrackLang.SPANISH),
    /** AUTO = turn them on only if the audio ended up OUTSIDE [understoodLangs]. OFF = never on their own. */
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val sizePercent: Int = 100,        // 60..200
    val textColor: Long = 0xFFFFFFFF,  // ARGB
    val backgroundColor: Long = 0x80000000, // ARGB (box background)
    val edge: Int = EDGE_OUTLINE,      // 0 none, 1 outline, 2 drop shadow
) {
    /**
     * Do these prefs pick the same languages as [other]? Ignores style (size, colors, edge).
     *
     * Used to avoid re-applying track selection when the only thing that changed is cosmetic: the
     * style lives in this same object and the size slider persists on EVERY step of the drag, so
     * without this filter moving the size would fire dozens of re-applications — and since the
     * audio pass doesn't short-circuit on a manual choice, it would revert the user's hand-picked
     * track.
     */
    fun sameLanguagesAs(other: PlaybackPrefs): Boolean =
        audioLangs == other.audioLangs &&
            understoodLangs == other.understoodLangs &&
            subtitleLangs == other.subtitleLangs &&
            subtitleMode == other.subtitleMode

    fun toJson(): String = JSONObject()
        // Legacy field: an old build reads ONLY this and it has to keep working.
        .put("language", if (subtitleMode == SubtitleMode.OFF) "off" else "es")
        .put("audioLangs", JSONArray(audioLangs.map { it.name }))
        .put("understoodLangs", JSONArray(understoodLangs.map { it.name }))
        .put("subtitleLangs", JSONArray(subtitleLangs.map { it.name }))
        .put("subtitleMode", subtitleMode.name)
        .put("sizePercent", sizePercent)
        .put("textColor", textColor).put("backgroundColor", backgroundColor)
        .put("edge", edge).toString()

    companion object {
        const val EDGE_NONE = 0
        const val EDGE_OUTLINE = 1
        const val EDGE_SHADOW = 2

        /** Any field the JSON doesn't carry falls back to a freshly-made [PlaybackPrefs]. */
        fun fromJson(s: String): PlaybackPrefs? = runCatching {
            val o = JSONObject(s)
            val base = PlaybackPrefs()
            PlaybackPrefs(
                audioLangs = langs(o, "audioLangs") ?: base.audioLangs,
                // Migration: with no new field it's seeded from audioLangs, which used to carry
                // both meanings. So a user who already had their list set up keeps seeing exactly
                // the same thing as before, instead of snapping back to the defaults.
                understoodLangs = langs(o, "understoodLangs") ?: langs(o, "audioLangs")
                    ?: base.understoodLangs,
                subtitleLangs = langs(o, "subtitleLangs") ?: base.subtitleLangs,
                // With no new field, the legacy one is migrated: "off" → OFF, anything else → AUTO.
                subtitleMode = o.optString("subtitleMode").takeIf { it.isNotBlank() }
                    ?.let { name -> runCatching { SubtitleMode.valueOf(name) }.getOrNull() }
                    ?: if (o.optString("language") == "off") SubtitleMode.OFF else SubtitleMode.AUTO,
                sizePercent = o.optInt("sizePercent", base.sizePercent),
                textColor = o.optLong("textColor", base.textColor),
                backgroundColor = o.optLong("backgroundColor", base.backgroundColor),
                edge = o.optInt("edge", base.edge),
            )
        }.getOrNull()

        /**
         * null when there's nothing usable in [key] → the caller falls back to its default. Also
         * returns null for an empty list or one made entirely of unknown names (a future build
         * adding a `TrackLang`): keeping the empty list would mean "never pick audio" and "always
         * turn subtitles on", which is worse than ignoring what isn't understood.
         */
        private fun langs(o: JSONObject, key: String): List<TrackLang>? {
            val arr = o.optJSONArray(key) ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { TrackLang.valueOf(arr.getString(i)) }.getOrNull()
            }.ifEmpty { null }
        }
    }
}

/** Preferences persisted locally (SharedPreferences), observable. */
class SubtitlePrefs(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("arkiv_subs", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<PlaybackPrefs> = _prefs.asStateFlow()

    fun update(p: PlaybackPrefs) {
        _prefs.value = p
        store.edit().putString(KEY, p.toJson()).apply()
    }

    private fun read(): PlaybackPrefs =
        store.getString(KEY, null)?.let { PlaybackPrefs.fromJson(it) } ?: PlaybackPrefs()

    private companion object {
        const val KEY = "subtitle_style"
    }
}
