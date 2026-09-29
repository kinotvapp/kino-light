package com.arkiv.player.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a failure that repeats from flooding the log.
 *
 * A stuck channel makes the proxy fail the same way dozens of times in a row (46 `playlist → 409` in 15-40 s, measured),
 * and GlitchTip keeps only the last ~100 breadcrumbs of an event: the lines that explain what the app did about it
 * (the seed rotation, the reopens) were pushed out by the repeats. So the first failure of a run and every
 * [logEvery]-th after it are logged, the rest are not, and the success that ends the run reports how long it was.
 */
internal class RepeatedFailureLog(private val logEvery: Int = 10) {

    private val runs = ConcurrentHashMap<String, Int>()

    /** The count to log for this failure of [key] (its position in the run), or null when it should stay quiet. */
    fun onFailure(key: String): Int? {
        val n = runs.merge(key, 1, Int::plus) ?: 1
        return if (n == 1 || n % logEvery == 0) n else null
    }

    /** [key] worked: how many failures in a row it had before, 0 if none. The run starts over. */
    fun onSuccess(key: String): Int = runs.remove(key) ?: 0
}
