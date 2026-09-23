package com.arkiv.player.data.ditu

import com.arkiv.player.data.gateway.GatewayPlayable
import org.json.JSONObject

/**
 * From a `ref` to something the player can open.
 *
 * There are three steps and the order isn't negotiable: `DETAIL` gives the `assetId`, `USERDATA`
 * says whether you're allowed to watch, and only then does `VIDEOURL` hand over the `.mpd`'s URL
 * **and the `playback_token` cookie**, which is what later authorizes the Widevine license.
 * Checking entitlement before asking for the URL is what turns a geoblock into a clear message
 * instead of a player failure ten seconds later.
 *
 * Live is only two steps: the `assetId` already comes with the channel (see `DituCatalog.channels`).
 */
internal class DituResolve(private val client: DituClientLike) {

    suspend fun vod(ref: DituRef): GatewayPlayable {
        val (contentId, assetId) = when {
            ref.contentType == "GROUP_OF_BUNDLES" -> firstChapterOfGroup(ref.contentId)
            ref.isSeries -> firstChapter(ref.contentId)
            else -> {
                val detail = client.get("CONTENT/DETAIL/${ref.contentType}/${ref.contentId}")
                val container = DituCatalog.containersFrom(detail).firstOrNull()
                    ?: throw DituException("Caracol no devolvió el detalle de ${ref.contentId}")
                val asset = DituCatalog.assetMaster(container)
                    ?: throw DituException("Caracol no tiene un asset reproducible para ${ref.contentId}")
                ref.contentId to asset
            }
        }

        checkEntitlement("CONTENT/USERDATA/VOD/$contentId")
        return playableFrom("CONTENT/VIDEOURL/VOD/$contentId/$assetId", contentId)
    }

    suspend fun live(channel: DituChannel): GatewayPlayable {
        checkEntitlement("CONTENT/USERDATA/LIVE/${channel.channelId}")
        return playableFrom("CONTENT/VIDEOURL/LIVE/${channel.channelId}/${channel.assetId}", channel.name)
    }

    /**
     * A GROUP_OF_BUNDLES isn't playable on its own: what opens is the first playable chapter of
     * its first child bundle that has chapters.
     */
    private suspend fun firstChapterOfGroup(groupId: String): Pair<String, Int> {
        val children = client.get(
            DituCatalog.TRAY,
            mapOf("filter_parentId" to groupId, "filter_contentType" to "BUNDLE"),
        )
        val ids = DituCatalog.containersFrom(children).mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }

        for (bundleId in ids) {
            val result = runCatching { firstChapter(bundleId) }.getOrNull()
            if (result != null) return result
        }

        throw DituException("Ningún capítulo de la serie está disponible para reproducir")
    }

    /** A BUNDLE isn't playable on its own: what opens is its first chapter that has an asset. */
    private suspend fun firstChapter(bundleId: String): Pair<String, Int> {
        val detail = client.get("CONTENT/DETAIL/BUNDLE/$bundleId")
        val outer = DituCatalog.containersFrom(detail).firstOrNull()
            ?: throw DituException("Caracol no devolvió el detalle de $bundleId")
        val raw = outer.optJSONArray("containers")
        for (i in 0 until (raw?.length() ?: 0)) {
            val ep = raw!!.optJSONObject(i) ?: continue
            val id = ep.optString("id").takeIf { it.isNotBlank() } ?: continue
            val asset = DituCatalog.assetMaster(ep) ?: continue
            return id to asset
        }
        throw DituException("Ningún capítulo de $bundleId se puede reproducir")
    }

    private suspend fun checkEntitlement(path: String) {
        val data: JSONObject = client.get(path)
        DituEntitlement.block(data)?.let { throw DituException("Caracol: $it", blockReason = it) }
    }

    private suspend fun playableFrom(path: String, what: String): GatewayPlayable {
        val r = client.getWithToken(path)
        val src = r.json.optJSONObject("resultObj")?.optString("src").orEmpty()
        if (src.isBlank()) throw DituException("Caracol no devolvió una URL de video para $what")
        return GatewayPlayable(
            kind = SOURCE,
            url = src,
            mime = "application/dash+xml",
            drmLicenseUrl = DituClient.LICENSE,
            // With no token it does NOT fail here: if the license later answers 500, that error
            // says more than one made up before even trying.
            drmLicenseHeaders = if (r.playbackToken.isBlank()) {
                emptyMap()
            } else {
                mapOf("Cookie" to "${DituClient.COOKIE_TOKEN}=${r.playbackToken}")
            },
        )
    }

    internal companion object {
        const val SOURCE = "ditu"
    }
}
