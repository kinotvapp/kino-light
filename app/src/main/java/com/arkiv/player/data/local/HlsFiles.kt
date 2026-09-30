package com.arkiv.player.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-128 of an HLS segment (RFC 8216 §5.2): CBC, PKCS7, the key's IV or the media sequence number. */
object HlsCrypto {
    /** The IV of [segment]: explicit on its key, else its media sequence number as a 16-byte big-endian. */
    fun ivFor(segment: HlsSegment): ByteArray =
        segment.key?.iv ?: ByteBuffer.allocate(16).putLong(8, segment.sequence).array()

    /**
     * The first whole blocks of [ciphertext] (a segment's head, not the whole segment) decrypted
     * with no padding check: CBC decrypts any prefix of whole blocks on its own. For the resume
     * fingerprint, which must see the same plaintext whatever key the stream was encrypted with.
     */
    fun decryptPrefix(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val length = ciphertext.size / 16 * 16
        if (length == 0) return ByteArray(0)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext, 0, length)
    }

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
    /** A reference's size is 31 bits. */
    const val MAX_REFERENCE_SIZE = 0x7FFFFFFFL

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
            require(size in 1..MAX_REFERENCE_SIZE) { "segment $i too large for a sidx reference" }
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
 * each `resolve`, and a retry may land on another CDN): same segment count, total duration and
 * format, the same chosen variant (bandwidth, height, codecs) and the same bytes (a hash of the
 * fMP4 init section, or of the first TS segment's head -- decrypted when the stream is AES-128, so
 * a site that issues a new key on every resolve still resumes: the key's URL and bytes are never
 * part of it). Only then does a retry go on from [segmentsDone]; anything else deletes the part and
 * starts over, so two encodes are never spliced.
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

        fun fingerprint(media: HlsMediaPlaylist, variant: HlsVariant?, contentHash: String): String =
            listOf(
                media.segments.size, Math.round(media.totalDurationSec), if (media.init != null) "fmp4" else "ts",
                variant?.bandwidth ?: 0, variant?.height ?: 0, variant?.codecs.orEmpty(), contentHash,
            ).joinToString(":")

        /** A short SHA-256 of [bytes]: the content part of the fingerprint. */
        fun contentHash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }

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

/** One elementary stream a TS program map table declares: its [pid] and its stream [type]. */
data class TsStream(val pid: Int, val type: Int)

/**
 * The elementary streams (H.264, AAC, HEVC, ID3…) a TS segment's program map table (PMT) declares,
 * read from its first packets (PAT → PMT, both at a segment's start in HLS). Null when the head
 * holds no complete PAT + PMT: nothing is concluded from it then.
 */
object TsProgram {
    /** The PMT's streams, in its order. */
    fun streams(bytes: ByteArray): List<TsStream>? {
        var pmtPid = -1
        var at = TsSync.start(bytes).takeIf { it >= 0 } ?: return null
        while (at + TsSync.PACKET <= bytes.size) {
            val p = at
            at += TsSync.PACKET
            if (bytes[p] != 0x47.toByte() || bytes[p + 1].toInt() and 0x40 == 0) continue // no section starts here
            val pid = (bytes[p + 1].toInt() and 0x1F shl 8) or u8(bytes, p + 2)
            if (pid != 0 && pid != pmtPid) continue
            var payload = p + 4
            if (u8(bytes, p + 3) and 0x20 != 0) payload += 1 + u8(bytes, p + 4) // adaptation field
            if (u8(bytes, p + 3) and 0x10 == 0 || payload >= p + TsSync.PACKET) continue
            val table = payload + 1 + u8(bytes, payload) // pointer_field
            val end = p + TsSync.PACKET
            if (table + 3 > end) continue
            val sectionEnd = minOf(table + 3 + ((u8(bytes, table + 1) and 0x0F shl 8) or u8(bytes, table + 2)), end) - 4 // CRC
            if (pid == 0) {
                // PAT: the first program that is not the network PID.
                var e = table + 8
                while (e + 4 <= sectionEnd) {
                    val program = (u8(bytes, e) shl 8) or u8(bytes, e + 1)
                    if (program != 0) { pmtPid = (u8(bytes, e + 2) and 0x1F shl 8) or u8(bytes, e + 3); break }
                    e += 4
                }
            } else {
                // PMT: skip PCR_PID and the program descriptors, then the stream entries.
                if (table + 12 > sectionEnd) return null
                var e = table + 12 + ((u8(bytes, table + 10) and 0x0F shl 8) or u8(bytes, table + 11))
                val streams = mutableListOf<TsStream>()
                while (e + 5 <= sectionEnd) {
                    streams += TsStream((u8(bytes, e + 1) and 0x1F shl 8) or u8(bytes, e + 2), u8(bytes, e))
                    e += 5 + ((u8(bytes, e + 3) and 0x0F shl 8) or u8(bytes, e + 4))
                }
                return streams.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** The PMT's stream types, sorted. */
    fun streamTypes(bytes: ByteArray): List<Int>? = streams(bytes)?.map { it.type }?.sorted()

    /**
     * Whether [type] is audio or video the player decodes (MPEG-1/2 video, H.264, HEVC, VVC/AVS…;
     * MPEG audio, AAC, AC-3, E-AC-3, DTS, AC-4). Everything else -- ID3 (0x15), SCTE-35 (0x86),
     * private data (0x06), subtitles -- is metadata for this purpose: it may come and go at a splice.
     */
    fun isAudioVideo(type: Int): Boolean = type in AUDIO_VIDEO

    private val AUDIO_VIDEO = setOf(
        0x01, 0x02, 0x10, 0x1B, 0x24, 0x33, 0x42, 0xD1, 0xEA, // video
        0x03, 0x04, 0x0F, 0x11, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x87, 0x8A, 0xAC, // audio
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}

/**
 * What a concatenated MPEG-TS does at an `EXT-X-DISCONTINUITY`. The segments are kept as they are:
 * a splice with the same streams (an intro, an ad, a re-encoded part) restarts its timestamps, and
 * the player's TS reader follows that jump the way it follows a 33-bit timestamp wrap -- the audio
 * clock resyncs and playback goes on; only seeking near the splice may land a little off.
 *
 * The player reads a saved `.ts` with ONE program map: the first segment's PMT fixes which PIDs are
 * read and with which decoder, and every later PMT is ignored. So:
 * - a change of audio/video codecs at a discontinuity (H.264 → HEVC, AAC → AC-3, a track added or
 *   gone) cannot play: it is refused ([HlsRefusedException]) as soon as the segment arrives;
 * - metadata streams (ID3, SCTE-35, private data: [TsProgram.isAudioVideo]) are ignored: they may
 *   appear, vanish or move without changing what plays;
 * - the same codecs on other PIDs (a splice encoded by another muxer) are kept, and their packets are
 *   written back under the first segment's PIDs ([rewriter]); a packet of the new segment left on one
 *   of those PIDs becomes a null packet, so it can't be read as the video or the audio.
 * The renumbering found at a PMT holds for the segments after it, until the next PMT says otherwise.
 * fMP4 needs no check: its one init section ([HlsDownloadPlan]) fixes the tracks for the whole stream.
 * Called in segment order ([HlsDownloader] appends in order).
 */
class TsProgramCheck(private var base: List<TsStream>?) {
    /** Current segment PID → the first segment's PID; empty = written as is. */
    private var remap: Map<Int, Int> = emptyMap()

    /** First-segment audio/video PIDs no stream of the current segments maps to: nulled. */
    private var nulled: Set<Int> = emptySet()

    fun check(index: Int, first: Boolean, discontinuity: Boolean, head: ByteArray) {
        val here = TsProgram.streams(head)
        if (first) { base = here; remap = emptyMap(); nulled = emptySet(); return }
        here ?: return // no PMT in this head: the renumbering in force goes on
        val expected = base ?: return
        val baseAv = expected.filter { TsProgram.isAudioVideo(it.type) }
        val hereAv = here.filter { TsProgram.isAudioVideo(it.type) }
        if (baseAv.map { it.type }.sorted() != hereAv.map { it.type }.sorted()) {
            // A change WITHOUT a discontinuity tag is not refused (the player would not reset either).
            if (discontinuity) {
                throw HlsRefusedException("streams change at the discontinuity of segment $index: ${types(baseAv)} -> ${types(hereAv)}")
            }
            remap = emptyMap(); nulled = emptySet()
            return
        }
        // Same codecs: pair them per type, in PID order.
        val pairs = HashMap<Int, Int>()
        baseAv.groupBy { it.type }.forEach { (type, olds) ->
            val news = hereAv.filter { it.type == type }.sortedBy { it.pid }
            olds.sortedBy { it.pid }.zip(news).forEach { (old, new) -> if (old.pid != new.pid) pairs[new.pid] = old.pid }
        }
        remap = pairs
        nulled = if (pairs.isEmpty()) emptySet() else baseAv.map { it.pid }.toSet() - hereAv.map { it.pid }.toSet()
    }

    /** How to rewrite a packet's PID for the segment just [check]ed, or null when it is written as is. */
    fun rewriter(): ((Int) -> Int)? {
        if (remap.isEmpty()) return null
        val map = remap
        val drop = nulled
        return { pid -> map[pid] ?: if (pid in drop) TsPidRewriter.NULL_PID else pid }
    }

    private fun types(streams: List<TsStream>) = streams.map { it.type }.sorted()
}

/**
 * Writes MPEG-TS packets to [out] with each one's PID passed through [pidFor]. Written from a packet
 * boundary on (what [TsSync.start] found), whole packets at a time; a trailing partial packet is
 * written as it is by [finish].
 */
class TsPidRewriter(private val out: java.io.OutputStream, private val pidFor: (Int) -> Int) {
    private val packet = ByteArray(TsSync.PACKET)
    private var filled = 0

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(TsSync.PACKET - filled, end - i)
            System.arraycopy(bytes, i, packet, filled, n)
            filled += n
            i += n
            if (filled == TsSync.PACKET) {
                rewrite()
                out.write(packet)
                filled = 0
            }
        }
    }

    fun finish() {
        if (filled > 0) out.write(packet, 0, filled)
        filled = 0
    }

    private fun rewrite() {
        if (packet[0] != SYNC) return
        val pid = (packet[1].toInt() and 0x1F shl 8) or (packet[2].toInt() and 0xFF)
        val to = pidFor(pid)
        if (to == pid) return
        packet[1] = ((packet[1].toInt() and 0xE0) or (to shr 8 and 0x1F)).toByte()
        packet[2] = to.toByte()
    }

    companion object {
        /** MPEG-TS null packets: every reader skips them. */
        const val NULL_PID = 0x1FFF
        private const val SYNC: Byte = 0x47
    }
}

/**
 * The sentence the person reads when an HLS download fails for a reason other than a refusal
 * ([HlsRefusedException] is always "Este video no se puede descargar"): always Spanish, whatever
 * the JVM or the server said. The retry policy still classifies the exception itself.
 */
object HlsFailureText {
    fun of(t: Throwable): String = when {
        PluginHostRefusal.of(t) != null -> PluginHostRefusal.message(t)
        else -> ofNetwork(t)
    }

    private fun ofNetwork(t: Throwable): String = when (t) {
        is InsufficientSpaceException -> t.message ?: NO_SPACE
        is HttpStatusException -> when (t.code) {
            401, 403 -> "El servidor del video no dio acceso (HTTP ${t.code})."
            404, 410 -> "El servidor ya no tiene este video (HTTP ${t.code})."
            else -> "El servidor del video respondió con un error (HTTP ${t.code})."
        }
        is IncompleteDownloadException -> "La conexión se cortó a mitad de la descarga."
        is java.net.UnknownHostException, is java.net.SocketException, is java.io.InterruptedIOException, is javax.net.ssl.SSLException ->
            "No se pudo conectar con el servidor del video. Revisa tu conexión."
        is java.io.IOException -> if (DownloadRetryPolicy.isTransient(t)) "Falló la conexión con el servidor del video." else NO_SPACE
        else -> "Falló la descarga del video."
    }

    private const val NO_SPACE = "No hay espacio suficiente en el dispositivo. Libera espacio y toca Reintentar."
}
