package com.arkiv.player.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128 of an HLS segment (RFC 8216 §5.2): CBC, PKCS7, the key's IV or the media sequence number. */
object HlsCrypto {
    /** The IV of [segment]: explicit on its key, else its media sequence number as a 16-byte big-endian. */
    fun ivFor(segment: HlsSegment): ByteArray =
        segment.key?.iv ?: ByteBuffer.allocate(16).putLong(8, segment.sequence).array()

    /** [input] decrypted as it is read; closes [input] with it. */
    fun decrypting(input: InputStream, key: ByteArray, iv: ByteArray): InputStream {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return CipherInputStream(input, cipher)
    }
}

/**
 * Where an MPEG-TS segment's packets start. Some hosts disguise their segments (a PNG or JPEG
 * header in front of the TS bytes, served as `.png`/`.jpg`): the player resyncs on its own when
 * streaming, but concatenated into one file the junk would sit in the middle of the video. The
 * first offset with a sync byte (0x47) at three packet boundaries in a row (one or two if the
 * segment is that short); -1 when there is none.
 */
object TsSync {
    const val PACKET = 188

    fun start(bytes: ByteArray, length: Int = bytes.size): Int {
        val limit = minOf(length, 64 * 1024)
        for (i in 0 until limit) {
            if (bytes[i] != SYNC) continue
            val packets = minOf(3, (length - i) / PACKET)
            if (packets == 0) continue
            if ((1 until packets).all { bytes[i + it * PACKET] == SYNC }) return i
        }
        return -1
    }

    private const val SYNC: Byte = 0x47
}

/**
 * The `sidx` box an fMP4 download carries between its init section and its first fragment, so the
 * player can seek in the saved file (a fragmented MP4 without one is unseekable). One reference
 * per HLS segment: its byte size and its EXTINF duration, in milliseconds.
 */
object Mp4Sidx {
    const val TIMESCALE = 1000L

    /** The box size for [count] references: reserved up front, written at the end. */
    fun size(count: Int): Int = 12 + 20 + 12 * count

    fun build(sizes: List<Long>, durationsSec: List<Double>, referenceId: Int = 1): ByteArray {
        require(sizes.size == durationsSec.size)
        val buf = ByteBuffer.allocate(size(sizes.size))
        buf.putInt(size(sizes.size)).put("sidx".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // version 0, flags 0
        buf.putInt(referenceId).putInt(TIMESCALE.toInt())
        buf.putInt(0) // earliest_presentation_time
        buf.putInt(0) // first_offset: the first fragment follows the box
        buf.putShort(0).putShort(sizes.size.toShort())
        sizes.forEachIndexed { i, size ->
            require(size in 1..0x7FFFFFFF) { "segment $i too large for a sidx reference" }
            buf.putInt(size.toInt()) // reference_type 0 (media)
            buf.putInt(Math.round(durationsSec[i] * TIMESCALE).toInt())
            buf.putInt(0x90000000.toInt()) // starts_with_SAP=1, SAP_type=1
        }
        return buf.array()
    }
}

/**
 * The resume record of an HLS download (`<episode>.hls.state`), written after every appended batch.
 * [fingerprint] identifies the CONTENT across re-resolutions (the URLs carry tokens that change on
 * each `resolve`): same segment count, same total duration, same format. Only then does a retry go
 * on from [segmentsDone]; anything else starts over.
 */
data class HlsResumeState(
    val resumeKey: String,
    val fingerprint: String,
    val segmentsDone: Int,
    val partBytes: Long,
    /** Byte size of each appended segment (fMP4's sidx needs them); init excluded. */
    val sizes: List<Long>,
    /** Bytes of the init section + reserved sidx at the start of the part (0 for TS). */
    val headerBytes: Long,
) {
    fun toJson(): String = JSONObject()
        .put("key", resumeKey).put("fp", fingerprint).put("done", segmentsDone).put("bytes", partBytes)
        .put("sizes", JSONArray(sizes)).put("header", headerBytes).toString()

    companion object {
        fun fromJson(s: String): HlsResumeState? = runCatching {
            val o = JSONObject(s)
            val arr = o.getJSONArray("sizes")
            HlsResumeState(
                o.getString("key"), o.getString("fp"), o.getInt("done"), o.getLong("bytes"),
                List(arr.length()) { arr.getLong(it) }, o.optLong("header"),
            )
        }.getOrNull()

        fun fingerprint(media: HlsMediaPlaylist): String =
            "${media.segments.size}:${Math.round(media.totalDurationSec)}:${if (media.init != null) "fmp4" else "ts"}"

        /**
         * Where a download starts: the saved state when it belongs to the same [resumeKey] and
         * [fingerprint], is internally consistent, and the part on disk still holds at least its
         * bytes (it is truncated back to them: a half-appended batch is dropped); else null = from zero.
         */
        fun resumable(saved: HlsResumeState?, resumeKey: String, fingerprint: String, partLength: Long, segmentCount: Int): HlsResumeState? {
            saved ?: return null
            if (saved.resumeKey != resumeKey || saved.fingerprint != fingerprint) return null
            if (saved.segmentsDone !in 1..segmentCount || saved.sizes.size != saved.segmentsDone) return null
            if (saved.partBytes != saved.headerBytes + saved.sizes.sum()) return null
            if (partLength < saved.partBytes) return null
            return saved
        }
    }
}
