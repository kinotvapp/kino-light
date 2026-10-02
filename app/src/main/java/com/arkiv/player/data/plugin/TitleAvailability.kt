package com.arkiv.player.data.plugin

import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** What asking a source for one title's stream said about whether it has that title. */
sealed interface Availability {
    /** It answered with a stream; [output] is the raw `resolve` answer, reusable by the tap. */
    data class Available(val output: String) : Availability

    /** It answered, and it doesn't have the title (no streams, only torrents, not that type). */
    data object Missing : Availability

    /** It didn't answer in time, or failed: whether it has the title is unknown. */
    data class NoAnswer(val error: Exception) : Availability
}

/** The items of a search answer that a check confirmed, and why the others were left out. */
data class CheckedItems<T>(
    val available: List<T>,
    /** How many answered "not here" (or were never checked: over the budget or the cap). */
    val missing: Int,
    /** The first failure among those that didn't answer; null when every checked one answered. */
    val noAnswer: Exception?,
)

/**
 * Confirms a search's titles with the source itself before listing them, for a source whose
 * `search` doesn't know whether it has a title (a converted Nuvio scraper answers ONE item for
 * every TMDB id it is asked about, and only `getStreams` -- its `resolve` -- finds out: listing
 * that item as is made every scraper a "source" of every title, and the tap then failed with
 * "no encontró este título").
 *
 * At most [max] items are checked, one after the other (a plugin's calls are serialized by the
 * pool anyway), sharing [budgetMs]: each check gets what is left of it. Unchecked items count as
 * missing: listing them is exactly the bug. Only [Availability.Available] items are kept.
 */
object TitleAvailability {
    suspend fun <T> check(
        items: List<T>,
        max: Int,
        budgetMs: Long,
        probe: suspend (item: T, timeoutMs: Long) -> Availability,
        now: () -> Long = System::currentTimeMillis,
    ): CheckedItems<T> {
        val start = now()
        val available = mutableListOf<T>()
        var noAnswer: Exception? = null
        var unknown = 0
        for (item in items.take(max)) {
            val left = budgetMs - (now() - start)
            if (left < MIN_CHECK_MS) break
            when (val a = probe(item, left)) {
                is Availability.Available -> available += item
                Availability.Missing -> Unit
                is Availability.NoAnswer -> {
                    unknown++
                    if (noAnswer == null) noAnswer = a.error
                }
            }
        }
        return CheckedItems(available, missing = items.size - available.size - unknown, noAnswer = noAnswer)
    }

    /**
     * What a `resolve` failure says about the title: the converted adapter's clean "nothing here"
     * answers ([NuvioPluginConverter.NO_STREAMS] is a `not_found`, [NuvioPluginConverter.ONLY_TORRENTS]
     * one Kino can't play) and any `not_found` mean the source doesn't have it; anything else (a
     * timeout, a site down, the scraper's own error) leaves it unknown. (A type the scraper doesn't
     * carry never gets here: its `search` already answers nothing.)
     */
    fun classify(e: Exception): Availability = when {
        e is PluginErrorException && e.code == PluginErrors.NOT_FOUND -> Availability.Missing
        e is PluginErrorException && e.code == PluginErrors.UNAVAILABLE && e.message == NuvioPluginConverter.ONLY_TORRENTS -> Availability.Missing
        else -> Availability.NoAnswer(e)
    }

    /** Runs [call] as one check: its answer is [Availability.Available], its failure [classify]'d. */
    suspend fun probe(call: suspend () -> String): Availability = try {
        Availability.Available(call())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        classify(e)
    }

    /** Below this, a check can't finish: the remaining items are left unchecked. */
    const val MIN_CHECK_MS = 1_000L
}

/**
 * The `resolve` answers [TitleAvailability] checks just got, for the tap that follows: a converted
 * scraper's `getStreams` can take most of a minute (PelisPlusHD), and the person who taps the title
 * it just confirmed shouldn't wait for it twice. Each answer is taken ONCE (a second play asks
 * again) and only within [TTL_MS], so a stream URL that expires never comes from here stale.
 */
object ProbedStreams {
    const val TTL_MS = 120_000L

    private class Entry(val output: String, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(pluginId: String, ref: String) = "$pluginId\u0000$ref"

    fun put(pluginId: String, ref: String, output: String, now: Long = System.currentTimeMillis()) {
        entries.entries.removeIf { now - it.value.at > TTL_MS }
        entries[key(pluginId, ref)] = Entry(output, now)
    }

    fun take(pluginId: String, ref: String, now: Long = System.currentTimeMillis()): String? =
        entries.remove(key(pluginId, ref))?.takeIf { now - it.at <= TTL_MS }?.output

    internal fun clear() = entries.clear()
}
