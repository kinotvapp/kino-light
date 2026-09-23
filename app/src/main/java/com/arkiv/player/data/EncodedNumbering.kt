package com.arkiv.player.data

/**
 * How to read the numbering that some sources used to pack INSIDE `orderIndex`.
 *
 * The torrent and web sources (removed in this branch's pruning, along with the functions that
 * used to save chapters this way) saved their chapters with `orderIndex = season*1000 + episode`,
 * while archive.org (also removed) used it as a plain 0..N-1 correlative. Both numbers share the
 * same column, and something has to tell them apart for the rows still saved from before that.
 *
 * **Why just looking at the number isn't enough.** The old rule was "if `orderIndex >= 1000` it's
 * encoded." It breaks on both sides:
 *  - A **season 0** (specials) gives `0*1000 + 3 = 3`, indistinguishable from an archive
 *    correlative: special 3 showed up as "E4".
 *  - An anime pack with **absolute** numbering (One Piece, chapter 1085) does go past 1000
 *    without being encoded, and was read as "S1 · E85".
 *
 * What actually tells them apart is **which source the row came from**, and that's two pieces of
 * data already saved on the row itself: the `itemId` prefix (only torrent and web ever encoded;
 * archive.org used its bare identifier) and the `section`, which those same sources wrote as
 * "Temporada N" exactly when they encoded. Asking for both is what rules out an archive.org
 * upload that saved its files in a folder called "Temporada 1", as well as the absolute-numbering
 * packs, which leave the section empty.
 *
 * The season comes from the section's text and not from `orderIndex / 1000` because it's the
 * direct value: for season 0 the two forms agree, but one doesn't depend on the arithmetic.
 *
 * This is a **reader for old data**. Sources save `season`/`episode` in their own column now, so
 * new rows never go through here; it still exists for whatever is already saved from before this
 * branch's torrent/web/archive.org pruning.
 */
object EncodedNumbering {

    private val SEASON_SECTION = Regex("""^Temporada (\d+)$""")

    /** Only torrent and web encode. archive.org (bare identifier) and Magis don't. */
    private fun encodes(itemId: String) =
        itemId.startsWith("torrent:") || itemId.startsWith("web:")

    /**
     * (season, chapter) if this row carries the numbering encoded in [orderIndex], or null if
     * [orderIndex] doesn't mean that and has to be treated as whatever it is for its source.
     */
    fun coordinates(itemId: String, section: String, orderIndex: Int): Pair<Int, Int>? {
        if (!encodes(itemId)) return null
        val season = SEASON_SECTION.find(section)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return season to (orderIndex % 1000)
    }
}
