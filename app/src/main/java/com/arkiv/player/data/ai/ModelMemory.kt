package com.arkiv.player.data.ai

import org.json.JSONObject

/** Where the memory is persisted. In production, SharedPreferences (see `PreferencesStore`). */
internal interface MemoryStore {
    fun read(): String?
    fun save(json: String)
}

/** Why a model failed, which decides how long it's left on hold. */
internal sealed interface Failure {
    /** A 429. [retryAfterMs] is what `Retry-After` said, or null if it said nothing. */
    data class RateLimited(val retryAfterMs: Long?) : Failure
    /** A 5xx, a network error, or a delay of more than 45 s. */
    data object Server : Failure
    /** It answered, but it couldn't be read. Does NOT count against it: see [ModelMemory]'s KDoc. */
    data object Unreadable : Failure
}

/**
 * Which models to try first and which to leave on hold, based on what worked on THIS device.
 *
 * There are no probes or server scores: successes and failures are counted per model, persisted,
 * and the order comes from that. The cap ([COUNT_CAP]) keeps a long history from burying a model
 * forever.
 *
 * **An unreadable answer does not count against it.** In llm-libre it took nine rounds of review
 * to learn that a client-side failure can't rule out a route.
 *
 * Not thread-safe: [AiClient] always uses it under its `Mutex`.
 */
internal class ModelMemory(
    private val store: MemoryStore,
    private val nowMs: () -> Long,
) {
    private data class Record(val successes: Int = 0, val failures: Int = 0, val waitUntilMs: Long = 0L)

    private val records: MutableMap<String, Record> = load()

    fun order(models: List<KiloModel>): List<KiloModel> {
        val now = nowMs()
        return models
            .filter { (records[it.id]?.waitUntilMs ?: 0L) <= now }
            // `sortedByDescending` is stable: with no history, the catalog's order is kept.
            .sortedByDescending { score(records[it.id]) }
    }

    fun success(id: String) {
        val r = records[id] ?: Record()
        records[id] = r.copy(successes = (r.successes + 1).coerceAtMost(COUNT_CAP), waitUntilMs = 0L)
        persist()
    }

    fun failure(id: String, failure: Failure) {
        val now = nowMs()
        val r = records[id] ?: Record()
        records[id] = when (failure) {
            is Failure.RateLimited -> r.copy(waitUntilMs = now + (failure.retryAfterMs ?: RATE_LIMIT_WAIT_MS))
            Failure.Server -> r.copy(
                failures = (r.failures + 1).coerceAtMost(COUNT_CAP),
                waitUntilMs = now + SERVER_WAIT_MS,
            )
            Failure.Unreadable -> return
        }
        persist()
    }

    private fun score(r: Record?): Int = if (r == null) 0 else r.successes - 2 * r.failures

    private fun load(): MutableMap<String, Record> {
        val out = mutableMapOf<String, Record>()
        val json = runCatching { store.read()?.let { JSONObject(it) } }.getOrNull() ?: return out
        for (id in json.keys()) {
            val o = json.optJSONObject(id) ?: continue
            out[id] = Record(o.optInt("e"), o.optInt("f"), o.optLong("h"))
        }
        return out
    }

    private fun persist() {
        val json = JSONObject()
        records.forEach { (id, r) ->
            json.put(id, JSONObject().put("e", r.successes).put("f", r.failures).put("h", r.waitUntilMs))
        }
        runCatching { store.save(json.toString()) }
    }

    internal companion object {
        const val RATE_LIMIT_WAIT_MS = 10 * 60 * 1000L
        const val SERVER_WAIT_MS = 5 * 60 * 1000L
        const val COUNT_CAP = 20
    }
}
