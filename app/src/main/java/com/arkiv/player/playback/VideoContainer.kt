package com.arkiv.player.playback

/** Video container and the MIME it has to be announced with. */
enum class Container(val mime: String) {
    MP4("video/mp4"),
    MATROSKA("video/x-matroska"),
    WEBM("video/webm"),
    MPEGTS("video/mp2t"),
    AVI("video/x-msvideo"),
    MPEGPS("video/mpeg"),
    ASF("video/x-ms-asf"),
    OGG("video/ogg"),
}

/**
 * What container a file is, by looking at its BYTES before its name.
 *
 * It's the rule magis already applied -- the container comes from the file, not from a guess --
 * carried over to the cast side, which is where guessing costs something. There used to be three
 * different MIME tables deciding by extension and contradicting each other: the unknown case was
 * matroska in `TorrentStreamServer`, mp4 in `LocalFileServer`, and mp4 again in
 * `CastRequestBuilder`. That string is exactly what the Chromecast receiver and the DLNA renderer
 * use to decide whether to open the stream; libVLC used to ignore it and probe instead, they
 * don't. An `.avi` or `.ts` from a torrent was announced to the TV as Matroska, and an `.mkv`
 * downloaded from the NUC -- which `LocalFilePaths.fileNameFor` saves as `.mp4` because the source
 * URL is a web page with no extension -- was announced as mp4.
 *
 * Pure and Android-free so the edges can be pinned by test: here the failure mode is silent (the
 * TV rejects the item, or worse, opens it and stays mute) and leaves no trace in any log of ours.
 */
object VideoContainer {

    /**
     * How many bytes have to be read from the start of the file for the signature to be conclusive.
     *
     * MPEG-TS sets the floor: its signature is the sync byte REPEATED every 188 bytes, and in an
     * m2ts the first packet starts at byte 4 (the 4 timestamp bytes Blu-ray puts in front). That
     * makes 4 + 2×188 + 1 = 381 as the absolute minimum. 512 is the next round block and is read
     * in one go.
     */
    const val SIGNATURE_BYTES = 512

    /** Size of an MPEG-TS transport packet. */
    private const val TS_PACKET = 188

    /** Same in an m2ts, where each packet carries 4 timestamp bytes in front. */
    private const val M2TS_PACKET = 192

    /** Sync byte every transport packet starts with. */
    private const val TS_SYNC = 0x47.toByte()

    /**
     * Boxes an MP4/MOV can start with. `ftyp` is the normal case since MP4; old `.mov` files
     * start directly with a content box.
     */
    private val MP4_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pnot")

    private val BY_EXTENSION = mapOf(
        "mp4" to Container.MP4, "m4v" to Container.MP4, "mov" to Container.MP4,
        "mkv" to Container.MATROSKA,
        "webm" to Container.WEBM,
        "ts" to Container.MPEGTS, "m2ts" to Container.MPEGTS, "mts" to Container.MPEGTS,
        "avi" to Container.AVI,
        "mpg" to Container.MPEGPS, "mpeg" to Container.MPEGPS,
        "wmv" to Container.ASF, "asf" to Container.ASF,
        "ogv" to Container.OGG, "ogg" to Container.OGG,
    )

    /** The container the first bytes declare, or null if none is recognized. */
    fun bySignature(header: ByteArray): Container? {
        if (header.size < 4) return null
        if (text(header, 4, 4) in MP4_BOXES) return Container.MP4
        if (isEbml(header)) return ebmlDocType(header)
        if (text(header, 0, 4) == "RIFF" && text(header, 8, 4) == "AVI ") return Container.AVI
        if (text(header, 0, 4) == "OggS") return Container.OGG
        if (startsWith(header, 0x00, 0x00, 0x01, 0xBA)) return Container.MPEGPS
        if (startsWith(header, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11)) return Container.ASF
        // TS goes last: it's the only one not resolved with a prefix comparison. Two variants, and
        // the STEP changes with the offset: bare TS goes 188 by 188 from byte 0, and m2ts goes
        // 192 by 192 from byte 4 (188 of packet + the 4 timestamp bytes Blu-ray puts in front of
        // each one). Looking for m2ts with a step of 188 never finds it.
        if (isMpegTs(header, 0, TS_PACKET) || isMpegTs(header, 4, M2TS_PACKET)) {
            return Container.MPEGTS
        }
        return null
    }

    /**
     * Does [nameOrUrl] look like a video file, by its extension?
     *
     * This is THE list, and exists so there's only one again: there used to be four scattered
     * around the app (`TorrentEngine`, `SubtitleFilePicker`, `MetadataParser`, `LocalFilePaths`)
     * and none agreed with another. Every difference was an invisible file — a `.ts` on
     * archive.org that never came out as an episode, a torrent `.m2ts` saved with the extension
     * changed.
     */
    fun isVideo(nameOrUrl: String): Boolean = byExtension(nameOrUrl) != null

    /** The container the extension of [nameOrUrl] suggests, or null if it says nothing useful. */
    fun byExtension(nameOrUrl: String): Container? = BY_EXTENSION[rawExtension(nameOrUrl)]

    /**
     * The video extension of [nameOrUrl], already normalized (lowercase, no querystring or path),
     * or null if it's not one we know how to play. For whoever has to name a file.
     */
    fun videoExtension(nameOrUrl: String): String? =
        rawExtension(nameOrUrl).takeIf { it in BY_EXTENSION }

    /** The last segment after the dot, with no querystring, fragment or directories. "" if none. */
    private fun rawExtension(nameOrUrl: String): String =
        nameOrUrl.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()

    /**
     * The decision: the signature rules, the name is the fallback, and with neither of the two it
     * falls back to [Container.MP4] — lying there is strictly better than sending no type, because
     * a receiver with no `Content-Type` rejects the item without even trying.
     */
    fun of(header: ByteArray, nameOrUrl: String): Container =
        bySignature(header) ?: byExtension(nameOrUrl) ?: Container.MP4

    /** The MIME of [nameOrUrl] when there are no bytes at hand (a remote URL, for instance). */
    fun mimeByName(nameOrUrl: String): String =
        (byExtension(nameOrUrl) ?: Container.MP4).mime

    /** The canonical extension for [container] -- for synthesizing a URL a renderer can name-sniff too. */
    fun extensionFor(container: Container): String = when (container) {
        Container.MP4 -> "mp4"
        Container.MATROSKA -> "mkv"
        Container.WEBM -> "webm"
        Container.MPEGTS -> "ts"
        Container.AVI -> "avi"
        Container.MPEGPS -> "mpg"
        Container.ASF -> "wmv"
        Container.OGG -> "ogv"
    }

    /**
     * [of] reading [file]'s header. Any read problem —the file doesn't exist yet, or a torrent
     * that hasn't downloaded its head yet— falls back to the name in silence: there's nothing to
     * report here, it's the normal state of a file that's still downloading.
     */
    fun ofFile(file: java.io.File): Container {
        val header = runCatching {
            java.io.FileInputStream(file).use { input ->
                val buf = ByteArray(SIGNATURE_BYTES)
                // InputStream#readNBytes(byte[],int,int) is API 33; this fill loop over the API-1
                // read(byte[],int,int) does the same (read the header fully, short at EOF) on any
                // Android, so container detection by header works down to minSdk 24.
                var read = 0
                while (read < SIGNATURE_BYTES) {
                    val n = input.read(buf, read, SIGNATURE_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                if (read == SIGNATURE_BYTES) buf else buf.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        return of(header, file.name)
    }

    private fun isEbml(b: ByteArray) = startsWith(b, 0x1A, 0x45, 0xDF, 0xA3)

    /**
     * Matroska and WebM share the EBML magic; DocType is what tells them apart, and it's in the
     * document's header (the first bytes). With no readable DocType, MATROSKA is returned, which
     * is the common case and also the superset: a receiver that opens matroska opens the webm
     * inside.
     */
    private fun ebmlDocType(b: ByteArray): Container {
        val head = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1)
        return if (head.contains("webm")) Container.WEBM else Container.MATROSKA
    }

    /**
     * TS sync at [offset] and in the next two packets, [step] apart from one another.
     *
     * THREE are required, not one: `0x47` is just some byte —it shows up in almost any file— and
     * what identifies a TS is that it repeats exactly every packet.
     */
    private fun isMpegTs(b: ByteArray, offset: Int, step: Int): Boolean {
        val last = offset + 2 * step
        if (b.size <= last) return false
        return b[offset] == TS_SYNC && b[offset + step] == TS_SYNC && b[last] == TS_SYNC
    }

    private fun startsWith(b: ByteArray, vararg expected: Int): Boolean {
        if (b.size < expected.size) return false
        return expected.withIndex().all { (i, e) -> b[i] == e.toByte() }
    }

    /** [length] bytes from [from] as ASCII, or "" if they're not all there. */
    private fun text(b: ByteArray, from: Int, length: Int): String =
        if (b.size < from + length) "" else String(b, from, length, Charsets.US_ASCII)
}
