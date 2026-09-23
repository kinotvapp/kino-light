package com.arkiv.player.data.ditu

import org.json.JSONObject
import java.util.Base64

/**
 * What to play from Caracol, in a string the app saves in its database.
 *
 * Same criterion as [com.arkiv.player.data.magis.MagisRef]: the gateway used to mint a signed ref
 * that expired, and with no server there's nobody to ask for a new one. It isn't needed either —
 * to resolve, Caracol only needs the `contentId` and the `contentType`, which don't expire. And
 * unlike Magis, here that really matters: Caracol's ids are stable, so a chapter saved in the
 * library keeps playing next month.
 *
 * [contentType] is what the API calls the content: `VOD` (a movie or standalone chapter), `BUNDLE`
 * (a season) or `GROUP_OF_BUNDLES` (a series with several seasons).
 */
internal data class DituRef(
    val contentId: String,
    val contentType: String = "VOD",
) {
    val isSeries: Boolean get() = contentType in SERIES

    /** `ditu1:<contentType>:<contentId>` — the contentId goes last so it doesn't matter if it
     *  ever carries a `:` inside. */
    fun encode(): String = "$PREFIX:$contentType:$contentId"

    internal companion object {
        const val PREFIX = "ditu1"

        /** The types that have to be listed before you can play. */
        val SERIES = setOf("BUNDLE", "GROUP_OF_BUNDLES")

        /** Reads an own ref or an old gateway one. `null` if it isn't Ditu's or isn't understood. */
        fun decode(ref: String): DituRef? {
            if (ref.isBlank()) return null
            if (ref.startsWith("$PREFIX:")) {
                val parts = ref.split(":", limit = 3)
                if (parts.size < 3) return null
                val contentId = parts[2].takeIf { it.isNotBlank() } ?: return null
                return DituRef(contentId = contentId, contentType = parts[1].ifBlank { "VOD" })
            }
            return fromGatewayRef(ref)
        }

        private fun fromGatewayRef(ref: String): DituRef? {
            val data = ref.substringBefore('.').takeIf { it.isNotBlank() && it != ref } ?: return null
            val json = runCatching {
                // `java.util.Base64` and not `android.util.Base64`: Android's is a stub that
                // returns null in JVM tests, and this is exactly the path that only runs once, in
                // silence, so as not to lose what's already saved.
                JSONObject(String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8))
            }.getOrNull() ?: return null
            if (json.optString("s") != "ditu") return null
            val p = json.optJSONObject("p") ?: return null
            val contentId = p.optString("content_id").takeIf { it.isNotBlank() } ?: return null
            return DituRef(
                contentId = contentId,
                contentType = p.optString("content_type").ifBlank { "VOD" },
            )
        }
    }
}
