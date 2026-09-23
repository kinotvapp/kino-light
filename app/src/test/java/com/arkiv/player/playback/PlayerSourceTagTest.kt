package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSourceTagTest {

    private fun tag(
        referer: String? = null,
        userAgent: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = PlayerSourceTag(
        kind = SourceKind.UNKNOWN,
        openingStartMs = null, openingEndMs = null, endingStartMs = null, castUrl = null,
        referer = referer, userAgent = userAgent, extraHeaders = extra,
    )

    @Test
    fun `allHeaders merges referer user-agent and the extra ones`() {
        val t = tag(
            referer = "https://serieskao.top/",
            userAgent = "Ranger/4.9.4-17294ac0",
            extra = mapOf("Content-Auth" to "A", "Content-License" to "L"),
        )
        assertEquals(
            mapOf(
                "Referer" to "https://serieskao.top/",
                "User-Agent" to "Ranger/4.9.4-17294ac0",
                "Content-Auth" to "A",
                "Content-License" to "L",
            ),
            t.allHeaders,
        )
    }

    @Test
    fun `allHeaders leaves out the empty ones`() {
        assertEquals(emptyMap<String, String>(), tag(referer = "", userAgent = null).allHeaders)
    }

    @Test
    fun `with no extras the behaviour is the old one`() {
        assertEquals(mapOf("Referer" to "https://x/"), tag(referer = "https://x/").allHeaders)
    }

    /**
     * IPC GUARD. The tag does NOT cross from `MediaController` to `MediaSession`: `PlayerSourceTagIpc`
     * takes it apart into the extras `PlayerScreen.localMediaItems` builds, and puts it back together
     * in `PlaybackService.MediaItemResolverCallback`. A field added here and not there arrives on the
     * other side with its default value, SILENTLY -- no error, no log, nothing. It happened once
     * already: `preferSoftware` stayed false and magis HEVC kept opening on hardware, which is
     * exactly what that field exists to prevent.
     *
     * [TRAVELS] is what `PlayerSourceTagIpc.encode` really writes; [STAYS_BEHIND] is what it leaves
     * out on purpose, with the reason. If this test fails you added (or removed) a field. What to do
     * is NOT to update the list here and move on: wire it in BOTH places above, and only then add it
     * to [TRAVELS].
     */
    @Test
    fun `every tag field either travels through the IPC or is listed as left behind`() {
        val declared = PlayerSourceTag::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(
            "Tag field neither wired through the IPC nor listed in STAYS_BEHIND (see this test's KDoc)",
            TRAVELS + STAYS_BEHIND,
            declared,
        )
    }

    private companion object {
        /** The nine keys `PlayerSourceTagIpc.encode` writes, in its own order. */
        val TRAVELS = setOf(
            "kind", "openingStartMs", "openingEndMs", "endingStartMs", "castUrl",
            "referer", "userAgent", "proxyUrl", "preferSoftware",
        )

        /**
         * Deliberately out of the IPC: only `SourceKind.LOCAL` items cross this boundary and
         * `PlayerViewModel.loadLocal` never sets `extraHeaders` -- magis and Caracol carry their
         * headers on the in-screen players, which never reach the session. Wire it the day a local
         * source needs a header, and move it to [TRAVELS].
         */
        val STAYS_BEHIND = setOf("extraHeaders")
    }
}
