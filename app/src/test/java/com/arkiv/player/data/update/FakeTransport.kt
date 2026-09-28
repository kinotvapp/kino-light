package com.arkiv.player.data.update

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * A fake network for the OTA tests: an application interceptor that answers every call itself, so the
 * real OkHttp request path runs but nothing leaves the machine. Routes are keyed by the URL WITHOUT its
 * query (the checker cache-busts every manifest fetch). An unrouted URL fails like an unresolvable host.
 */
class FakeTransport : Interceptor {
    private val routes = HashMap<String, () -> Response.Builder>()
    private val failures = HashMap<String, IOException>()
    val requested = ArrayList<String>()

    fun body(url: String, body: String, code: Int = 200) = bytes(url, body.toByteArray(), code)

    fun bytes(url: String, body: ByteArray, code: Int = 200) {
        routes[url] = {
            Response.Builder().code(code).message("fake").protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody("application/octet-stream".toMediaType()))
        }
    }

    fun fail(url: String, e: IOException) { failures[url] = e }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val full = chain.request().url
        val key = full.newBuilder().query(null).build().toString()
        requested += key
        failures[key]?.let { throw it }
        val route = routes[key] ?: throw java.net.UnknownHostException("unrouted: ${full.host}")
        return route().request(chain.request()).build()
    }
}
