package com.arkiv.player.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PeerStore(context: Context) : PeerLookup {
    private val prefs = context.applicationContext.getSharedPreferences("arkiv_companion", Context.MODE_PRIVATE)

    fun all(): List<Peer> = runCatching {
        val arr = JSONArray(prefs.getString("peers", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Peer(it.getString("deviceId"), it.getString("name"), it.getString("token"),
                it.optString("lastIp"), it.optInt("lastPort"))
        }
    }.getOrDefault(emptyList())

    override fun find(deviceId: String): Peer? = all().firstOrNull { it.deviceId == deviceId }

    // Host and controller transports can both read-modify-write this store from different
    // coroutines (e.g. a fresh peer accepted by the host while an old one is being forgotten from
    // the UI); `all()`+`write()` is not atomic on its own, so these two entry points are.
    @Synchronized
    fun save(peer: Peer) {
        val next = all().filterNot { it.deviceId == peer.deviceId } + peer
        write(next)
    }

    @Synchronized
    fun remove(deviceId: String) = write(all().filterNot { it.deviceId == deviceId })

    private fun write(peers: List<Peer>) = runCatching {
        val arr = JSONArray()
        peers.forEach {
            arr.put(JSONObject().put("deviceId", it.deviceId).put("name", it.name)
                .put("token", it.token).put("lastIp", it.lastIp).put("lastPort", it.lastPort))
        }
        prefs.edit().putString("peers", arr.toString()).apply()
    }
}
