package com.arkiv.player.playback

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The streams [ArchiveCacheProxy] is allowed to serve, each behind an unguessable token.
 *
 * The proxy listens on every interface (a Chromecast or a DLNA TV has to reach it), and its URLs
 * used to CARRY the stream: the origin in `u=` and the CDN's auth headers in `h=`. Anyone on the
 * Wi-Fi could therefore make the phone fetch any URL they liked, with any headers, and could read
 * Magis's `Content-Auth`/`Content-License` straight off a cast URL. Now the app registers a stream
 * here, server side, and the URL carries only a 128-bit random token (in the PATH, `/t/<token>/…`,
 * because neither a DLNA renderer nor the Cast receiver can send a header). The token names exactly
 * one origin + header set; a request can no longer name an upstream URL at all, and the headers
 * never leave the phone's memory.
 *
 * Lifetime: a token lives while it is used. It dies after [idleTtlMs] without a request (a paused
 * cast resumes well within it), after [maxAgeMs] no matter what, when the proxy stops ([revokeAll])
 * and, past [maxEntries], the least recently used one makes room.
 *
 * Registering the SAME stream again returns the same token while it is alive, so the URL a title
 * plays from stays byte-identical across recompositions (the player's reuse decision compares it).
 */
class ProxyTokens(
    private val idleTtlMs: Long = IDLE_TTL_MS,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One registered stream. [headers] stay here, in memory, and are never written into a URL. */
    class Stream(val origin: String, val headers: Map<String, String>, val direct: Boolean, internal val createdAt: Long) {
        @Volatile internal var lastUsedAt: Long = createdAt
    }

    private data class Key(val origin: String, val headers: Map<String, String>, val direct: Boolean)

    private val byToken = ConcurrentHashMap<String, Stream>()
    private val byKey = ConcurrentHashMap<Key, String>()
    private val random = SecureRandom()

    /** The token for this stream: the live one if it is already registered, a new one otherwise. */
    @Synchronized
    fun register(origin: String, headers: Map<String, String>, direct: Boolean): String {
        val key = Key(origin, headers.toMap(), direct)
        byKey[key]?.let { existing -> if (resolve(existing) != null) return existing }
        val now = clock()
        val token = newToken()
        byToken[token] = Stream(origin, key.headers, direct, now)
        byKey[key] = token
        evictOverflow()
        return token
    }

    /** The stream [token] names, or null when it is unknown, expired or revoked. Counts as a use. */
    fun resolve(token: String): Stream? {
        if (token.length != TOKEN_HEX_CHARS) return null
        val stream = byToken[token] ?: return null
        val now = clock()
        if (now - stream.lastUsedAt > idleTtlMs || now - stream.createdAt > maxAgeMs) {
            revoke(token)
            return null
        }
        stream.lastUsedAt = now
        return stream
    }

    @Synchronized
    fun revoke(token: String) {
        val stream = byToken.remove(token) ?: return
        byKey.remove(Key(stream.origin, stream.headers, stream.direct), token)
    }

    @Synchronized
    fun revokeAll() {
        byToken.clear()
        byKey.clear()
    }

    /** How many tokens are registered (tests and diagnostics). */
    val size: Int get() = byToken.size

    private fun evictOverflow() {
        while (byToken.size > maxEntries) {
            val oldest = byToken.entries.minByOrNull { it.value.lastUsedAt }?.key ?: return
            revoke(oldest)
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 128 bits from [SecureRandom]: not guessable by scanning, and short enough for any renderer's URL field. */
        const val TOKEN_BYTES = 16
        const val TOKEN_HEX_CHARS = TOKEN_BYTES * 2

        /** Four hours without a single request: well past any pause, short of "forever". */
        const val IDLE_TTL_MS = 4L * 60 * 60 * 1000

        /** A day, however busy: no title is longer, and the CDN's own URL expires sooner. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** Plenty for one play/cast session plus the ones it replaced. */
        const val MAX_ENTRIES = 32
    }
}
