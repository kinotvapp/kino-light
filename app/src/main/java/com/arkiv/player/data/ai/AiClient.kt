package com.arkiv.player.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What [AiClient] returns: the model's text, or that it couldn't. Never an exception. */
internal sealed interface AiResponse {
    data class Text(val text: String, val model: String) : AiResponse
    data object Unable : AiResponse
}

/** One turn in a chat sent to [AiClient.streamChat]. `role` is "system" | "user" | "assistant". */
internal data class ChatMessage(val role: String, val content: String)

/**
 * The app talking to Kilo's free models, with no server of its own and no key.
 *
 * **Never sends `Authorization`**: Kilo's anonymous tier depends on it never being sent (that's
 * how `llm-libre` uses it, omitting the header when the key is empty). So there's no secret to
 * embed.
 *
 * Discovers the models at `/models` ([KiloCatalog]), tries them in the order of what's worked on
 * this device ([ModelMemory]) and moves to the next if one fails. At most [MAX_ATTEMPTS] per ask:
 * measured live, a free model takes ~20 s to answer. It's transport only: it knows nothing about
 * movies.
 */
internal class AiClient(
    private val baseUrl: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build(),
    private val memory: ModelMemory,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Guards [memory] and the in-memory catalog: trivia and "For you" can ask at the same time. */
    private val lock = Mutex()
    private var catalog: List<KiloModel> = emptyList()
    private var catalogFetchedAtMs: Long? = null

    suspend fun ask(instruction: String): AiResponse = withContext(Dispatchers.IO) {
        val models = currentCatalog()
        for (model in lock.withLock { memory.order(models) }.take(MAX_ATTEMPTS)) {
            // Before each model, not in the middle of one: if the ask is cancelled while the previous
            // model had not answered yet, this ends the loop instead of spending the anonymous quota
            // on up to MAX_ATTEMPTS models (~45 s each) for an ask nobody waits for anymore (skipping
            // a chapter, leaving the player).
            currentCoroutineContext().ensureActive()
            val text = attempt(model, instruction) ?: continue
            lock.withLock { memory.success(model.id) }
            return@withContext AiResponse.Text(text, model.id)
        }
        AiResponse.Unable
    }

    /**
     * Streams a multi-turn chat as text deltas, for Kinobot. Tries [AUTO_ID] (Kilo's auto-router:
     * fast, picks a good free model, but ~1 in 4 calls streams nothing) TWICE first, then the
     * [ModelMemory]-ranked catalog, capped at [MAX_ATTEMPTS]. A model that streams ≥1 token wins; one
     * that streams nothing or errors is skipped (an empty stream carries no penalty, like [ask]'s
     * empty answer). Emits nothing (completes) if every attempt fails. Never throws.
     */
    fun streamChat(messages: List<ChatMessage>): Flow<String> = flow {
        val models = currentCatalog()
        val order = (listOf(AUTO_ID, AUTO_ID) + lock.withLock { memory.order(models) }.map { it.id })
            .take(MAX_ATTEMPTS)
        for (id in order) {
            currentCoroutineContext().ensureActive()
            var emitted = false
            val failure = streamAttempt(id, messages) { delta -> emitted = true; emit(delta) }
            if (emitted) {
                lock.withLock { memory.success(id) }
                return@flow
            }
            // No tokens: an empty stream is [Failure.Unreadable] (no penalty), so AUTO_ID's second
            // try — or the same model later — isn't parked for a transient empty.
            val f = failure ?: Failure.Unreadable
            Log.w(TAG, "$id (stream): $f")
            lock.withLock { memory.failure(id, f) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One streaming attempt. Reads the SSE body line by line, extracts `choices[0].delta.content`
     * from each `data:` frame and hands non-empty deltas to [onDelta], stopping at `[DONE]`. Returns
     * a [Failure] for a non-200 / network error, or null on a clean end (the caller decides based on
     * whether anything was emitted).
     */
    private suspend fun streamAttempt(
        id: String,
        messages: List<ChatMessage>,
        onDelta: suspend (String) -> Unit,
    ): Failure? {
        val msgArray = JSONArray()
        messages.forEach { msgArray.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("model", id).put("stream", true).put("messages", msgArray).toString()
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            execute(request).use { resp ->
                when {
                    resp.code == 429 -> Failure.RateLimited(
                        resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)?.coerceAtMost(RATE_LIMIT_WAIT_CAP_MS),
                    )
                    !resp.isSuccessful -> Failure.Server
                    else -> {
                        val source = resp.body?.source() ?: return Failure.Server
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.substring(5).trim()
                            if (payload == "[DONE]") break
                            val delta = runCatching {
                                JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                                    .optJSONObject("delta")?.optString("content").orEmpty()
                            }.getOrDefault("")
                            if (delta.isNotEmpty()) onDelta(delta)
                        }
                        null
                    }
                }
            }
        } catch (e: IOException) {
            Failure.Server
        }
    }

    /** One attempt against a model: its text, or null after logging the failure in [memory]. */
    private suspend fun attempt(model: KiloModel, instruction: String): String? {
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", instruction)))
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body.toRequestBody(JSON))
            .build()
        val failure: Failure = try {
            execute(request).use { resp ->
                when {
                    resp.code == 429 -> Failure.RateLimited(
                        // Persisted (`ModelMemory.failure`): a `Retry-After` of days would leave the
                        // model parked for days. One hour is a generous cap against the 10-minute
                        // default and still leaves the model available the same day.
                        resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)?.coerceAtMost(RATE_LIMIT_WAIT_CAP_MS),
                    )
                    !resp.isSuccessful -> Failure.Server
                    else -> {
                        // Reading the body stays OUTSIDE the runCatching: if the connection drops
                        // mid-200 (after `execute()` already delivered the response), it's a real
                        // `IOException` and has to fall into the outer catch as Failure.Server
                        // (network error), not as Unreadable (which doesn't count against it).
                        val responseBody = resp.body?.string().orEmpty()
                        val text = runCatching {
                            JSONObject(responseBody)
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                        }.getOrNull()?.trim()
                        if (!text.isNullOrEmpty()) return text
                        Failure.Unreadable
                    }
                }
            }
        } catch (e: IOException) {
            // Includes the 45 s timeout (`InterruptedIOException` is an `IOException`).
            Failure.Server
        }
        // A CancellationException (thrown by `execute` if the coroutine is cancelled while
        // waiting) doesn't land here: it isn't an IOException, so it keeps propagating upward
        // without going through this logging. A model nobody waited for can't be penalized for it.
        Log.w(TAG, "${model.id}: $failure")
        lock.withLock { memory.failure(model.id, failure) }
        return null
    }

    /**
     * Runs [request] cancelably: if the coroutine is cancelled while waiting for the response,
     * this aborts the OkHttp call (`call.cancel()`) instead of leaving it running on its own on a
     * pool thread until the server answers or the 45 s timeout cuts it off.
     */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                // The handler closes the response when the coroutine was cancelled before or right
                // after this resume, so a late answer never leaks its connection.
                cont.resume(response) { response.close() }
            }
        })
    }

    /** The catalog from the last [CATALOG_TTL_MS]; if refreshing it fails, whatever was there last. */
    private suspend fun currentCatalog(): List<KiloModel> {
        lock.withLock {
            val fetchedAt = catalogFetchedAtMs
            if (fetchedAt != null && nowMs() - fetchedAt < CATALOG_TTL_MS) return catalog
        }
        currentCoroutineContext().ensureActive()
        val fresh = try {
            http.newCall(Request.Builder().url("$baseUrl/models").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) null
                else KiloCatalog.candidates(JSONObject(resp.body?.string().orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network down or broken JSON (`JSONException`): same as a 5xx, keep the last one.
            Log.w(TAG, "catalog: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        return lock.withLock {
            if (!fresh.isNullOrEmpty()) {
                catalog = fresh
                catalogFetchedAtMs = nowMs()
            }
            catalog
        }
    }

    internal companion object {
        const val BASE = "https://api.kilo.ai/api/gateway"
        /** Kilo's auto-router (free tier): fast, but occasionally streams nothing — see [streamChat]. */
        const val AUTO_ID = "kilo-auto/free"
        const val MAX_ATTEMPTS = 4
        const val TIMEOUT_S = 45L
        const val CATALOG_TTL_MS = 6 * 60 * 60 * 1000L
        const val RATE_LIMIT_WAIT_CAP_MS = 60 * 60 * 1000L
        private val JSON = "application/json".toMediaType()
        private const val TAG = "ArkivIA"
    }
}
