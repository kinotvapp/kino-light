package com.arkiv.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The IPC round trip's VALUES, not just its field LIST (see the "every tag field either travels
 * through the IPC or is listed as left behind" guard in [PlayerSourceTagTest]): a tag with every
 * field populated has to survive encode→decode exactly. A
 * `preferSoftware` bug already slipped through exactly here once -- it stayed false and magis's
 * HEVC kept opening in hardware -- and nothing short of round-tripping the real values would have
 * caught it.
 *
 * This exercises [PlayerSourceTagIpc], the single codec `PlayerScreen.localMediaItems` and
 * `PlaybackService.MediaItemResolverCallback` both delegate to now -- not `android.os.Bundle`
 * itself, which is a no-op stub under this project's JVM unit tests (`isReturnDefaultValues = true`
 * in `app/build.gradle.kts`): `putString` is a no-op and `getString`/`containsKey` always answer
 * null/false, so a real Bundle round trip can't be observed here at all (verified by running a probe
 * against it). [PlayerSourceTagIpc] exists precisely so the real logic is testable regardless of
 * that -- same convention this project already uses for `LocalFilePaths`/`FreeSpacePolicy` and
 * friends, none of which touch Android framework types either, because there is no Robolectric here.
 */
class PlayerSourceTagIpcTest {

    /** Every field populated, the way a magis item with a resume position and a proxy fallback looks. */
    private val fullTag = PlayerSourceTag(
        kind = SourceKind.LOCAL,
        openingStartMs = 60_000L,
        openingEndMs = 90_000L,
        endingStartMs = 1_200_000L,
        castUrl = "https://cast.example/movie.mp4",
        referer = "https://serieskao.top/",
        userAgent = "Ranger/4.9.4-17294ac0",
        proxyUrl = "http://127.0.0.1:8080/proxy",
        preferSoftware = true,
    )

    @Test
    fun `every field survives encode then decode`() {
        assertEquals(fullTag, PlayerSourceTagIpc.decode(PlayerSourceTagIpc.encode(fullTag)))
    }

    @Test
    fun `preferSoftware false round-trips as false, not as a stray true`() {
        val tag = fullTag.copy(preferSoftware = false)
        val decoded = PlayerSourceTagIpc.decode(PlayerSourceTagIpc.encode(tag))
        assertEquals(tag, decoded)
        assertEquals(false, decoded?.preferSoftware)
    }

    @Test
    fun `absent optional fields decode back to null, not to a wrong default`() {
        val bare = PlayerSourceTag(
            kind = SourceKind.LOCAL,
            openingStartMs = null, openingEndMs = null, endingStartMs = null, castUrl = null,
        )
        assertEquals(bare, PlayerSourceTagIpc.decode(PlayerSourceTagIpc.encode(bare)))
    }

    @Test
    fun `extras that never went through encode decode to null, same guard the callback relied on`() {
        assertNull(PlayerSourceTagIpc.decode(emptyMap()))
    }
}
