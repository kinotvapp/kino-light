package com.arkiv.player.data.ditu

import org.json.JSONObject

/**
 * Fake client for testing the layers above with no network. Records what it was asked —the test
 * asserts on the PATH and the PARAMETERS, which is where porting mistakes are— and returns the
 * JSON loaded for that path.
 */
internal class FakeDituClient : DituClientLike {
    val calls = mutableListOf<Pair<String, Map<String, String>>>()
    private val responses = mutableMapOf<String, JSONObject>()
    var token: String = ""
    var failure: Throwable? = null

    fun respond(path: String, json: String) {
        responses[path] = JSONObject(json)
    }

    override suspend fun get(path: String, params: Map<String, String>): JSONObject {
        calls += path to params
        failure?.let { throw it }
        return responses[path] ?: JSONObject("""{"resultObj":{"containers":[]}}""")
    }

    override suspend fun getWithToken(path: String): DituResponse =
        DituResponse(get(path, emptyMap()), token)
}
