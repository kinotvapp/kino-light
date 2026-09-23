package com.arkiv.player.ui.player

import android.graphics.Color
import android.util.TypedValue
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.arkiv.player.data.subtitles.PlaybackPrefs

/**
 * SRT subtitles carry no on-screen position, so ExoPlayer anchors every one of their cues to the
 * bottom of the frame. When a subtitle file has two entries overlapping in time -- e.g. an
 * episode-title line still showing as the first dialogue line begins -- [androidx.media3.ui.SubtitleView]
 * paints both at that same bottom line and they smear on top of each other (reported on Magis anime,
 * 2026-09-22). Media3 does not stack position-less cues on its own.
 *
 * [stackOverlappingCues] folds the bottom-anchored, position-less cues that are visible at the same
 * instant into a single multi-line cue, so they render as stacked lines in one box instead of
 * overlapping. Cues that carry their own position (WebVTT / SSA signs) keep it and are returned
 * untouched, and a single cue is returned unchanged -- so nothing else about subtitle rendering
 * changes, and content whose overlap is burnt into the video (hardsub) is unaffected.
 */
internal fun stackOverlappingCues(cues: List<Cue>): List<Cue> {
    if (cues.size < 2) return cues
    val bottomAnchored = cues.filter { it.line == Cue.DIMEN_UNSET && it.position == Cue.DIMEN_UNSET }
    if (bottomAnchored.size < 2) return cues

    val stacked = mergeBottomCueTexts(bottomAnchored.map { it.text?.toString().orEmpty() })
    if (stacked.isEmpty()) return cues

    val positioned = cues.filter { it.line != Cue.DIMEN_UNSET || it.position != Cue.DIMEN_UNSET }
    // No line/position on the merged cue: it stays bottom-centered, exactly where the originals were,
    // now as one box whose lines SubtitleView lays out without overlap.
    val merged = Cue.Builder().setText(stacked).build()
    return positioned + merged
}

/**
 * Joins the text of the simultaneous bottom cues into one string, one cue per line, top-to-bottom in
 * the order the player delivered them. Blank cues are dropped so an empty overlapping cue does not
 * add a stray blank line. Pure (no Android types) so the stacking rule is unit-tested directly.
 */
internal fun mergeBottomCueTexts(texts: List<String>): String =
    texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/** Comfortable base subtitle size, in sp, on a phone at [PlaybackPrefs.sizePercent] = 100. */
internal const val BASE_SUBTITLE_SP = 20f

/**
 * Applies the app's own subtitle look (Ajustes → Subtítulos: size / colours / edge) to [this] view.
 *
 * On a phone the player used to call [SubtitleView.setUserDefaultTextSize], which sizes the text as
 * a fraction of the view's height. The view is the whole screen, so in portrait -- where the video
 * is a small letterboxed strip -- the subtitles came out huge, and the app's own size slider did
 * nothing. Here the phone gets a fixed sp base scaled by [PlaybackPrefs.sizePercent] (so the slider
 * works and the default is sane) plus the app's colours/edge; embedded font sizes are ignored so the
 * app size always wins.
 *
 * TV is left exactly as it was: viewed from across the room with the video filling the screen, the
 * platform's fraction-of-height sizing is right, and the TV settings do not expose these controls
 * (see TvSettingsSubtitles).
 */
internal fun SubtitleView.applyArkivSubtitleStyle(prefs: PlaybackPrefs, isTv: Boolean) {
    if (isTv) {
        setUserDefaultStyle()
        setUserDefaultTextSize()
        return
    }
    setApplyEmbeddedFontSizes(false)
    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_SUBTITLE_SP * prefs.sizePercent / 100f)
    setStyle(
        CaptionStyleCompat(
            prefs.textColor.toInt(),
            prefs.backgroundColor.toInt(),
            Color.TRANSPARENT,
            when (prefs.edge) {
                PlaybackPrefs.EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                PlaybackPrefs.EDGE_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                else -> CaptionStyleCompat.EDGE_TYPE_NONE
            },
            Color.BLACK,
            null,
        ),
    )
}
