package com.arkiv.player.playback

import com.arkiv.player.playback.HlsPlaylistRewriter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every URI an HLS playlist names comes back through the proxy, resolved and classified right. */
class HlsPlaylistRewriterTest {
    private val seen = mutableListOf<Pair<String, Kind>>()
    private fun route(url: String, kind: Kind): String {
        seen += url to kind
        return "/t/TOK/r/${seen.size - 1}"
    }

    @Test fun `a master playlist's variants, renditions and i-frame playlists are playlists`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="es",URI="audio/es.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO="aud"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,AUDIO="aud"
            https://other.example/high/index.m3u8?tok=1
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,URI="/iframes.m3u8"
        """.trimIndent()
        val out = HlsPlaylistRewriter.rewrite(master, "https://cdn.example/v/master.m3u8?sig=x", ::route)!!
        assertEquals(
            listOf(
                "https://cdn.example/v/audio/es.m3u8" to Kind.PLAYLIST,
                "https://cdn.example/v/low/index.m3u8" to Kind.PLAYLIST,
                "https://other.example/high/index.m3u8?tok=1" to Kind.PLAYLIST,
                "https://cdn.example/iframes.m3u8" to Kind.PLAYLIST,
            ),
            seen,
        )
        assertTrue(out.contains("URI=\"/t/TOK/r/0\""))
        assertTrue(out.lines().contains("/t/TOK/r/1"))
        assertTrue(out.lines().contains("/t/TOK/r/2"))
        assertTrue(out.contains("URI=\"/t/TOK/r/3\""))
        assertNoUpstreamLeft(out)
        // Tags and attributes otherwise untouched.
        assertTrue(out.contains("#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO=\"aud\""))
    }

    @Test fun `a media playlist's segments, keys and maps are media, relative and absolute alike`() {
        val media = "#EXTM3U\r\n" +
            "#EXT-X-TARGETDURATION:6\r\n" +
            "#EXT-X-MAP:URI=\"init.mp4\"\r\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"../keys/k1.key\",IV=0x1234\r\n" +
            "#EXTINF:6.0,\r\n" +
            "seg-1.ts\r\n" +
            "#EXTINF:6.0,\r\n" +
            "//cdn2.example/seg-2.ts?e=9\r\n" +
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://fairplay-key\"\r\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\r\n" +
            "#EXTINF:6.0,\r\n" +
            "https://cdn3.example/abs/seg-3.ts\r\n" +
            "#EXT-X-ENDLIST\r\n"
        val out = HlsPlaylistRewriter.rewrite(media, "https://cdn.example/v/low/index.m3u8", ::route)!!
        assertEquals(
            listOf(
                "https://cdn.example/v/low/init.mp4" to Kind.MEDIA,
                "https://cdn.example/v/keys/k1.key" to Kind.MEDIA,
                "https://cdn.example/v/low/seg-1.ts" to Kind.MEDIA,
                "https://cdn2.example/seg-2.ts?e=9" to Kind.MEDIA,
                "https://cdn3.example/abs/seg-3.ts" to Kind.MEDIA,
            ),
            seen,
        )
        assertTrue(out.contains("#EXT-X-MAP:URI=\"/t/TOK/r/0\"\r\n"))
        assertTrue(out.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"/t/TOK/r/1\",IV=0x1234\r\n"))
        // Non-http keys are left alone (FairPlay/inline): nothing to fetch, nothing to route.
        assertTrue(out.contains("URI=\"skd://fairplay-key\""))
        assertTrue(out.contains("URI=\"data:text/plain;base64,AAAA\""))
        assertTrue(out.endsWith("#EXT-X-ENDLIST\r\n"))
        assertNoUpstreamLeft(out)
    }

    @Test fun `low-latency parts, preload hints and rendition reports are routed too`() {
        val media = """
            #EXTM3U
            #EXT-X-PART:DURATION=1.0,URI="part1.mp4"
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part2.mp4"
            #EXT-X-RENDITION-REPORT:URI="../other/index.m3u8",LAST-MSN=10
        """.trimIndent()
        HlsPlaylistRewriter.rewrite(media, "https://cdn.example/v/low/index.m3u8", ::route)!!
        assertEquals(
            listOf(
                "https://cdn.example/v/low/part1.mp4" to Kind.MEDIA,
                "https://cdn.example/v/low/part2.mp4" to Kind.MEDIA,
                "https://cdn.example/v/other/index.m3u8" to Kind.PLAYLIST,
            ),
            seen,
        )
    }

    @Test fun `a base that isn't http is refused, and only playlists are recognized`() {
        assertNull(HlsPlaylistRewriter.rewrite("#EXTM3U\na.ts", "file:///x.m3u8", ::route))
        assertTrue(HlsPlaylistRewriter.isPlaylist("﻿#EXTM3U\n"))
        assertFalse(HlsPlaylistRewriter.isPlaylist("<html>"))
    }

    private fun assertNoUpstreamLeft(out: String) {
        for (host in listOf("cdn.example", "other.example", "cdn2.example", "cdn3.example")) {
            assertFalse("$host left in:\n$out", out.contains(host))
        }
    }
}
