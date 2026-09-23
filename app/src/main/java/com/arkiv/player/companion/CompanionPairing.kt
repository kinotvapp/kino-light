package com.arkiv.player.companion

import org.json.JSONObject
import java.security.SecureRandom

data class Peer(val deviceId: String, val name: String, val token: String, val lastIp: String, val lastPort: Int)

fun interface PeerLookup { fun find(deviceId: String): Peer? }

sealed interface PairResult {
    data class Accept(val token: String) : PairResult
    data class Reject(val reason: String) : PairResult
}

object CompanionPairing {
    private val rng = SecureRandom()

    fun generateCode(): String = (rng.nextInt(1_000_000)).toString().padStart(6, '0')

    fun newToken(): String {
        val b = ByteArray(32); rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun decideHost(
        presentedCode: String,
        helloPayload: JSONObject,
        peers: PeerLookup,
        tokenFactory: () -> String,
    ): PairResult {
        val deviceId = helloPayload.optString("deviceId").ifBlank { return PairResult.Reject("bad_hello") }
        val token = helloPayload.optString("token").takeIf { it.isNotBlank() }
        if (token != null) {
            val known = peers.find(deviceId)
            if (known != null && known.token == token) return PairResult.Accept(token)
        }
        val code = helloPayload.optString("code").takeIf { it.isNotBlank() }
        if (code != null && code == presentedCode) return PairResult.Accept(tokenFactory())
        return PairResult.Reject("bad_code")
    }
}

/** Reconnect backoff: 1s, 2s, 4s, then capped. The cap is low on purpose -- this link is LAN-only,
 *  where a redial costs a single TCP SYN, so a snappy ceiling matters more than being gentle. It
 *  bounds worst-case reconnection for a host that came back on the SAME port (a fresh port is picked
 *  up immediately by the mDNS-driven reconnect, not by waiting this out). */
fun backoffDelayMs(attempt: Int): Long {
    val a = attempt.coerceAtLeast(1)
    val shifted = if (a - 1 >= 30) Long.MAX_VALUE else 1000L shl (a - 1)
    return minOf(5_000L, shifted)
}
