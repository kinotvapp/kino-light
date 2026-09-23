package com.arkiv.player.companion

import org.json.JSONObject
import java.util.UUID

data class Envelope(val v: Int, val type: String, val id: String, val payload: JSONObject)

object CompanionProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 64 * 1024
}

const val TYPE_HELLO = "hello"
const val TYPE_WELCOME = "welcome"
const val TYPE_REJECT = "reject"
const val TYPE_PING = "ping"
const val TYPE_PONG = "pong"

fun newEnvelope(type: String, payload: JSONObject): Envelope =
    Envelope(CompanionProtocol.VERSION, type, UUID.randomUUID().toString(), payload)

fun Envelope.encode(): String =
    JSONObject().put("v", v).put("type", type).put("id", id).put("payload", payload).toString()

/** Null on anything not a well-formed, current-version, size-bounded envelope. */
fun decodeEnvelope(text: String): Envelope? {
    if (text.length > CompanionProtocol.MAX_MESSAGE_BYTES) return null
    if (text.toByteArray(Charsets.UTF_8).size > CompanionProtocol.MAX_MESSAGE_BYTES) return null
    return runCatching {
        val o = JSONObject(text)
        val v = o.getInt("v")
        if (v != CompanionProtocol.VERSION) return null
        val type = o.getString("type").ifBlank { return null }
        val id = o.getString("id").ifBlank { return null }
        val payload = o.getJSONObject("payload")
        Envelope(v, type, id, payload)
    }.getOrNull()
}
