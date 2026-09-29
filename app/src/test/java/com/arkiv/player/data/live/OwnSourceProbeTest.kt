package com.arkiv.player.data.live

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnSourceProbeTest {
    private fun ok(kind: OwnKind, text: String) = OwnSourceProbe.classify(kind, text.toByteArray()) as OwnProbe.Ok
    private fun failed(kind: OwnKind, text: String) = (OwnSourceProbe.classify(kind, text.toByteArray()) as OwnProbe.Failed).message

    @Test fun `an HLS manifest is a good channel`() {
        assertTrue(ok(OwnKind.CHANNEL, "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg1.ts").message.contains("HLS"))
    }

    @Test fun `a web page or an empty answer is not a channel`() {
        assertTrue(failed(OwnKind.CHANNEL, "<html><body>403</body></html>").contains("página web"))
        assertTrue(failed(OwnKind.CHANNEL, "").contains("nada"))
    }

    @Test fun `a playlist reports how many channels it holds`() {
        val text = "#EXTM3U\n#EXTINF:-1,Uno\nhttp://a.example.com/1.m3u8\n#EXTINF:-1,Dos\nhttps://a.example.com/2.m3u8\n"
        assertEquals("Encontré 2 canales", ok(OwnKind.PLAYLIST, text).message)
    }

    @Test fun `a playlist with no channels is refused with a hint`() {
        assertTrue(failed(OwnKind.PLAYLIST, "hola mundo").contains("lista M3U"))
        assertTrue(failed(OwnKind.PLAYLIST, "<html></html>").contains("lista M3U"))
    }

    @Test fun `run maps a download failure to a Spanish message`() = runTest {
        val r = OwnSourceProbe.run(OwnKind.CHANNEL, "http://a.example.com/1.m3u8", emptyMap()) { _, _, _ -> throw IOException("timeout") }
        assertTrue((r as OwnProbe.Failed).message.startsWith("No se pudo leer la dirección"))
    }

    @Test fun `a name that resolves into the home network says so`() = runTest {
        val r = OwnSourceProbe.run(OwnKind.CHANNEL, "http://a.example.com/1.m3u8", emptyMap()) { _, _, _ ->
            throw com.arkiv.player.data.plugin.PrivateAddressException("a.example.com")
        }
        assertTrue((r as OwnProbe.Failed).message.contains("red local"))
    }

    @Test fun `run classifies what the fetcher returns`() = runTest {
        val r = OwnSourceProbe.run(OwnKind.CHANNEL, "http://a.example.com/1.m3u8", emptyMap()) { _, _, _ -> "#EXTM3U\n".toByteArray() }
        assertTrue(r is OwnProbe.Ok)
    }
}
