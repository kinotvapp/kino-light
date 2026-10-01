package com.arkiv.player.playback

/**
 * Who asked one of the phone's LAN servers for what, and how it went. Lets a caster count and log
 * its renderer's requests without the server knowing which caster it serves: DLNA needs every
 * request the TV makes to count as "the TV came for the media" (see `DlnaDiagnosis`), or a TV
 * playing from [RemuxHlsServer] or [LocalFileServer] reads as one that never fetched anything.
 *
 * [started] is called as soon as the request line and headers are read, [finished] once the
 * answer is out (or the connection died), with the bytes of media sent and how long it took.
 * Neither may throw or block: they run on the serving thread.
 */
interface LanRequestListener {
    fun started(remote: String?, requestLine: String, range: String?, userAgent: String?)

    fun finished(remote: String?, requestLine: String, bytes: Long, ms: Long, failure: String?)
}
