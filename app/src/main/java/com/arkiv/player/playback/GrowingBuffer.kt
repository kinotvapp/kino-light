package com.arkiv.player.playback

/**
 * A buffer that fills on one end while it's read from the other.
 *
 * Exists so magis's startup stops BLOCKING. Until 2026-08-11, `preWarm` downloaded the whole
 * 2 MB startup chunk before publishing the playlist: measured on the Fire TV, that cost 0.5 to 5s
 * of spinner on every playback, and was the dominant phase of startup.
 *
 * But what libVLC needed to avoid giving up on identifying the stream was never the 2 MB itself:
 * it was that its FIRST READ didn't wait (see [ArchiveCacheProxy.preWarm]). With this, the
 * proxy hands out bytes as they arrive from the origin: the player opens as soon as there's
 * something, and never runs out of data because the buffer keeps growing behind it. It's what any
 * player that "starts right away" does -- start and keep downloading -- instead of fetching a
 * fixed block first.
 *
 * Downloading a fixed block was already tried and isn't the same: trimming it to 512 KB left the
 * player without data mid-identification (see the note on `HOT_STARTUP_SIZE`). Nothing gets
 * trimmed here -- it just stops waiting.
 *
 * Safe for one writer and several readers: [write] is called by the thread downloading from
 * the origin, and [slice]/[waitUntil] by the threads serving the player.
 */
class GrowingBuffer(capacity: Int) {

    private val data = ByteArray(capacity)
    private val lock = Object()

    /** How many valid bytes there are. Volatile: readers look at it without taking the lock. */
    @Volatile private var length = 0

    /** Nothing more is going to arrive (the origin finished, cut off or failed). */
    @Volatile var closed = false
        private set

    val available: Int get() = length

    /** Adds [n] bytes from [source]. Whatever doesn't fit in the capacity is silently dropped. */
    fun write(source: ByteArray, n: Int) {
        synchronized(lock) {
            val fits = minOf(n, data.size - length)
            if (fits > 0) {
                source.copyInto(data, length, 0, fits)
                length += fits
            }
            // ALWAYS notified, even if nothing got in: if the buffer filled up, whoever is waiting
            // for more bytes still has to wake up so it doesn't hang until the timeout.
            (lock as Object).notifyAll()
        }
    }

    /** No more data is coming. Wakes up everyone who was waiting. */
    fun close() {
        synchronized(lock) {
            closed = true
            (lock as Object).notifyAll()
        }
    }

    /**
     * Waits until there are at least [n] bytes. Returns false if it closed before reaching that
     * mark or if [timeoutMs] expired -- in both cases the caller has to leave the loop, not retry.
     */
    fun waitUntil(n: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (length < n && !closed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                (lock as Object).wait(remaining)
            }
            return length >= n
        }
    }

    /** Copy of the bytes there are from [from] up to whatever has arrived. Empty if there's nothing new. */
    fun slice(from: Int): ByteArray {
        val until = length
        if (from >= until) return ByteArray(0)
        return data.copyOfRange(from, until)
    }
}
