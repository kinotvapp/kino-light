package com.arkiv.player.companion

import org.json.JSONObject

const val TYPE_PLAY = "play"
const val TYPE_PLAY_ACK = "play_ack"

/**
 * What the phone tells the TV to play. Identity only -- the TV re-resolves the stream against its
 * OWN Magis/Caracol session, so no URL (which would be tokenized/expiring) crosses the wire.
 *
 * `kind` picks the source; the rest is what that source needs to re-mint a local episodeId on the
 * receiver ([com.arkiv.player.data.ArkivRepository.addMagisSource]/`addDituSource`), or, for
 * `live`, just the channel [liveCode].
 */
data class CompanionPlayItem(
    val kind: String,
    val ref: String = "",
    val contentId: String = "",
    val title: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val episodeTitle: String = "",
    val seriesRef: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val liveCode: String = "",
) {
    fun toPayload(): JSONObject = JSONObject()
        .put("kind", kind).put("ref", ref).put("contentId", contentId).put("title", title)
        .put("season", season).put("episode", episode).put("episodeTitle", episodeTitle)
        .put("seriesRef", seriesRef).put("poster", poster).put("backdrop", backdrop)
        .put("liveCode", liveCode)

    companion object {
        const val KIND_MAGIS = "magis"
        const val KIND_DITU = "ditu"
        const val KIND_LIVE = "live"

        /** Null on a payload with no `kind` (a malformed/foreign message). */
        fun fromPayload(p: JSONObject): CompanionPlayItem? {
            val kind = p.optString("kind").ifBlank { return null }
            return CompanionPlayItem(
                kind = kind,
                ref = p.optString("ref"),
                contentId = p.optString("contentId"),
                title = p.optString("title"),
                season = p.optInt("season"),
                episode = p.optInt("episode"),
                episodeTitle = p.optString("episodeTitle"),
                seriesRef = p.optString("seriesRef"),
                poster = p.optString("poster"),
                backdrop = p.optString("backdrop"),
                liveCode = p.optString("liveCode"),
            )
        }
    }
}

/** Host -> controller result of a [TYPE_PLAY]. [reason] is a short machine string when `!ok`
 *  (e.g. `"no_link"`, `"resolve_failed"`, `"unknown_kind"`); [title] echoes what started. */
data class CompanionPlayAck(val ok: Boolean, val reason: String = "", val title: String = "") {
    fun toPayload(): JSONObject = JSONObject().put("ok", ok).put("reason", reason).put("title", title)

    companion object {
        fun fromPayload(p: JSONObject): CompanionPlayAck =
            CompanionPlayAck(p.optBoolean("ok"), p.optString("reason"), p.optString("title"))
    }
}
