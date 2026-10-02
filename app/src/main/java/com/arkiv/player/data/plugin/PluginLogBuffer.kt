package com.arkiv.player.data.plugin

/**
 * The last lines a plugin wrote with `kino.log` / `console.*`, per plugin, so a failed call's report can say what
 * the plugin itself was saying just before it failed (the author's own debug output; Kino's report otherwise only has
 * kind, function, timing and a redacted message).
 *
 * Pure memory, never persisted: a ring of [MAX_LINES] lines of at most [MAX_LINE_CHARS] each, per plugin. Lines come
 * in already redacted of the plugin's sealed secrets ([DefaultPluginHost.log]). Nothing leaves from here:
 * [PluginTelemetry] reads it only for a call that FAILED, only for the origins it allows, and cleans every line again
 * (addresses, tokens, the person's setting values and query) before it goes anywhere. A call's own lines are the ones
 * after its [mark]; calls of one plugin are serialized by the pool, so they are not mixed with another call's.
 */
class PluginLogBuffer(private val maxPlugins: Int = MAX_PLUGINS) {
    private class Ring {
        val lines = ArrayDeque<String>()
        var next = 0L
    }

    private val rings = object : LinkedHashMap<String, Ring>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ring>?) = size > maxPlugins
    }

    /** Appends [line] (cut to [MAX_LINE_CHARS]) to [pluginId]'s ring, dropping the oldest past [MAX_LINES]. */
    @Synchronized
    fun record(pluginId: String, line: String) {
        val ring = rings.getOrPut(pluginId) { Ring() }
        ring.lines.addLast(line.take(MAX_LINE_CHARS))
        ring.next++
        while (ring.lines.size > MAX_LINES) ring.lines.removeFirst()
    }

    /** A point in [pluginId]'s log: [since] gives what was written after it. */
    @Synchronized
    fun mark(pluginId: String): Long = rings[pluginId]?.next ?: 0L

    /** The lines [pluginId] wrote after [mark] (oldest first), at most what the ring still holds. */
    @Synchronized
    fun since(pluginId: String, mark: Long): List<String> {
        val ring = rings[pluginId] ?: return emptyList()
        val fresh = (ring.next - mark).coerceIn(0L, ring.lines.size.toLong()).toInt()
        return ring.lines.takeLast(fresh)
    }

    companion object {
        const val MAX_LINES = 30
        const val MAX_LINE_CHARS = 300

        /** The most a report carries of a call's lines, in characters, after cleaning. */
        const val MAX_REPORT_CHARS = 2_048
        private const val MAX_PLUGINS = 64

        /** The app's one buffer. */
        val shared = PluginLogBuffer()
    }
}
