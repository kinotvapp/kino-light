package com.arkiv.player.playback

import com.arkiv.player.data.magis.TweakedMd5
import com.arkiv.player.data.gateway.LiveSignature

/**
 * Where each CDN request's `sign2` comes from.
 *
 * This branch has a single implementation ([LocalSignature]) and the interface is kept as-is: what
 * left was the backup, which consisted of asking the gateway for the signature and switching to it
 * after two rejections in a row (`FirmaDelGateway`/`FirmaConRespaldo`/`FirmaSegunAjustes`, plus the
 * "Force server" switch in Settings). With no server of our own there's nowhere to switch to: if
 * Magis ever changes the algorithm, it gets fixed by publishing an APK. [rejected]/[accepted] still
 * exist because `LiveHlsProxy` calls them, and because they're the natural hook if there's ever
 * another local signature.
 */
interface SegmentSignature {
    suspend fun sign(token: String): LiveSignature

    /** The CDN rejected the last signature handed to it. */
    fun rejected() {}

    /** The CDN accepted the last signature handed to it (`requestFromOrigin` used it and was NOT followed by a 403). */
    fun accepted() {}
}

/** Signature on-device. It's local arithmetic: no network, no waiting, no pool. */
class LocalSignature : SegmentSignature {
    override suspend fun sign(token: String): LiveSignature {
        val now = System.currentTimeMillis()
        return LiveSignature(now, TweakedMd5.signO3(token, now))
    }
}

/**
 * Whether this CDN response means "I don't authorize you", i.e. the signature didn't work.
 *
 * Exists because this CDN rejects with **401**, not 403, and the code only checked for 403.
 * Measured on the Google TV on 2026-08-14: no channel loaded while the gateway resolved perfectly
 * (`live resolve OK ... direcciones=2`, 200 across the board). The device's log uncovered it:
 *
 * ```
 * 12:56:58.653  playlist → 401 in 346ms
 * 12:56:58.654  502 to the player: playlist with code 401
 * ```
 *
 * With a 401, `requestFromOrigin` used to call `accepted()` -- marking the signature GOOD-- the
 * consecutive-rejections counter reset and [FirmaConRespaldo] never switched to the gateway's
 * signer. The session wasn't given up for dead either, the other recovery path: both defenses were
 * watching the wrong code and the channel died on an unrecoverable 502.
 *
 * Only 401 and 403. A 5xx or a timeout are NOT a signature rejection -- they're the CDN having a
 * problem-- and counting them would switch to the backup over any network hiccup.
 */
fun isSignatureRejection(code: Int): Boolean = code == 401 || code == 403
