package com.arkiv.player.playback

import android.content.Context

/**
 * The live channels whose hardware video decoder showed no picture and had to be swapped for a software one.
 *
 * The failure belongs to the channel's encoding, not to the device as a whole (films and other channels play fine on
 * the same box), so it's remembered per channel: the next time the channel is opened it goes straight to software
 * instead of waiting the watchdog's ten seconds again. Kept on disk across launches; bounded so it can't grow.
 */
internal class SoftwareChannels(private val max: Int = MAX, initial: List<String> = emptyList()) {

    private val channels = LinkedHashSet(initial.takeLast(max))

    fun contains(channel: String): Boolean = channel in channels

    /** Adds [channel]; when full, the oldest one drops out. Returns whether it was new. */
    fun add(channel: String): Boolean {
        val added = channels.add(channel)
        while (channels.size > max) channels.remove(channels.first())
        return added
    }

    fun encode(): String = channels.joinToString(SEPARATOR)

    companion object {
        const val MAX = 60
        private const val SEPARATOR = "\n"

        fun decode(raw: String?): SoftwareChannels =
            SoftwareChannels(initial = raw.orEmpty().split(SEPARATOR).filter { it.isNotBlank() })
    }
}

internal object LiveDecoderMemory {
    private const val PREFS = "live_decoder"
    private const val KEY = "software_channels"

    @Volatile private var cache: SoftwareChannels? = null

    private fun load(context: Context): SoftwareChannels = cache ?: synchronized(this) {
        cache ?: SoftwareChannels.decode(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        ).also { cache = it }
    }

    fun prefersSoftware(context: Context, channel: String): Boolean = synchronized(this) { load(context).contains(channel) }

    fun remember(context: Context, channel: String) = synchronized(this) {
        val channels = load(context)
        if (channels.add(channel)) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, channels.encode()).apply()
        }
    }
}
