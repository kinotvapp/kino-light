package com.arkiv.player.cast

import com.arkiv.player.dlna.DlnaXml

/**
 * The cast's permanent diagnostic trail, under the tag [TAG] (`adb logcat -s KinoCastDiag`).
 *
 * Born as a throw-away patch for the 2026-10-01 KALLEY tests, where it was the only thing that
 * showed what the TV actually asked for (the first segment it wanted, how long each one took to
 * reach it, which request died with a broken pipe) and what the receiver reported back. It stays,
 * one line per event: receiver state changes, each request the TV makes to the remux server, the
 * remux pacing pausing and resuming. Nothing per byte, nothing per frame.
 *
 * PRIVATE by construction: every message goes through [scrub], which reduces any URL to
 * scheme/host/path with its token segments hidden and its query dropped, and blanks any long hex
 * run (tokens, signatures, ids). Callers never hand it headers.
 */
object CastDiag {
    const val TAG = "KinoCastDiag"

    private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'|,]+""")
    private val LONG_HEX = Regex("""\b[0-9a-fA-F]{24,}\b""")

    /** [message] with every URL reduced by [DlnaXml.safeUrl] and every long hex run blanked. */
    fun scrub(message: String): String =
        message.replace(URL) { DlnaXml.safeUrl(it.value) }.replace(LONG_HEX, "…")

    fun i(message: String) {
        runCatching { android.util.Log.i(TAG, scrub(message)) }
    }

    fun w(message: String) {
        runCatching { android.util.Log.w(TAG, scrub(message)) }
    }
}
