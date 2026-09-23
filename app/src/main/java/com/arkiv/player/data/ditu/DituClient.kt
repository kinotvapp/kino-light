package com.arkiv.player.data.ditu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A failure talking to Caracol.
 *
 * [httpCode] and [blockReason] exist for [CaracolFailure], which translates the error for the
 * person: the message is for the log, and guessing what happened by reading it is fragile.
 */
internal class DituException(
    message: String,
    cause: Throwable? = null,
    /** The status Caracol answered with, if the failure was an error status. */
    val httpCode: Int? = null,
    /** [DituEntitlement.block]'s reason, if Caracol won't let something be watched: already written for the person. */
    val blockReason: String? = null,
) : RuntimeException(message, cause)

/**
 * A Caracol response together with the `playback_token` it carried, or `""` if it didn't carry one.
 *
 * The token exists as a separate field because it does NOT come in the body: it travels in the
 * response's `Set-Cookie`, and it's what later authorizes the Widevine license request.
 */
internal data class DituResponse(val json: JSONObject, val playbackToken: String)

internal interface DituClientLike {
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject
    suspend fun getWithToken(path: String): DituResponse
}

/**
 * The app talking directly to Caracol Streaming's AVS API.
 *
 * There's no authentication of any kind: free content is requested and served. What IS mandatory
 * are the three headers in [HEADERS] — without them the CDN answers 403 on both the manifest and
 * the segments, a failure that reads as "the video doesn't exist".
 */
internal class DituClient(
    private val baseUrl: String = BASE,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // CAP ON THE WHOLE CALL. The two above don't bound it: `connectTimeout` counts per
        // connection attempt and `readTimeout` per read, so a response that trickles in never
        // trips either. Matters since `AppGraph.contentSource` is a `CompositeSource`: that
        // search emits a single `Done` once ALL sources are finished, meaning every Magis search
        // also waits on Caracol.
        //
        // 15s and not less: `DituCatalog.catalog` brings the whole catalog in a single GET, and on
        // a slow network like the TV's it might not make it in 10.
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : DituClientLike {

    override suspend fun get(path: String, params: Map<String, String>): JSONObject =
        fetch(path, params).json

    override suspend fun getWithToken(path: String): DituResponse = fetch(path, emptyMap())

    private suspend fun fetch(path: String, params: Map<String, String>): DituResponse =
        withContext(Dispatchers.IO) {
            val url = ("$baseUrl/$path").toHttpUrlOrNull()?.newBuilder()
                ?.apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                ?.build()
                ?: throw DituException("URL inválida: $baseUrl/$path")

            val httpRequest = Request.Builder().url(url).apply {
                HEADERS.forEach { (k, v) -> header(k, v) }
            }.build()

            val response = runCatching { http.newCall(httpRequest).execute() }
                .getOrElse { throw DituException("Caracol no responde: ${it.message}", it) }

            response.use {
                if (!it.isSuccessful) throw DituException("Caracol respondió ${it.code} en $path", httpCode = it.code)
                val body = it.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }
                    .getOrElse { e -> throw DituException("Caracol devolvió algo que no es JSON en $path", e) }
                // `headers("Set-Cookie")` and not the body: the token travels as a cookie.
                val token = it.headers("Set-Cookie")
                    .firstOrNull { c -> c.startsWith("$COOKIE_TOKEN=") }
                    ?.substringAfter("$COOKIE_TOKEN=")
                    ?.substringBefore(';')
                    .orEmpty()
                DituResponse(json, token)
            }
        }

    internal companion object {
        const val BASE = "https://middleware.ditu.caracoltv.com/AGL/1.6/A/ENG/ANDROID/ALL"

        /** Same base with the license path. There's an ANDROIDTV variant
         *  (`.../ANDROIDTV/ALL/CONTENT/LICENSE`) the gateway defines but never uses; the one that's
         *  proven is the one ported here. */
        const val LICENSE = "$BASE/CONTENT/LICENSE"

        const val COOKIE_TOKEN = "playback_token"

        val HEADERS = mapOf(
            "restful" to "yes",
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to "okhttp/4.12.0",
        )
    }
}
