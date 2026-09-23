package com.arkiv.player.companion

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

private const val TAG = "CompanionTransport"
private const val PING_MS = 15_000L
private const val DEAD_MS = 30_000L

enum class LinkState { Idle, Connecting, Pairing, Connected, Reconnecting, Error }

/** HOST: one WebSocketServer; the first authenticated client becomes the peer. */
class CompanionHostTransport(
    private val scope: CoroutineScope,
    private val identity: CompanionIdentity,
    private val peers: PeerStore,
) {
    private val _state = MutableStateFlow(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()
    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()
    private val _port = MutableStateFlow(0)

    /** The bound TCP port; 0 until [start] resolves it (the underlying server assigns an ephemeral
     *  port asynchronously). Lets the UI show `ip:port` for the IP-fallback path. */
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _connectedPeerId = MutableStateFlow<String?>(null)

    /** deviceId of the controller currently connected to this host (from its `hello`), null when
     *  none is. Companion sync (Task 7) keys the host side's pull cursor on this -- the controller
     *  side already has an equivalent in [CompanionControllerTransport.connectedDeviceId]. */
    val connectedPeerId: StateFlow<String?> = _connectedPeerId.asStateFlow()

    private var server: WebSocketServer? = null
    @Volatile private var peerConn: WebSocket? = null
    private var heartbeat: Job? = null
    @Volatile private var lastSeen = 0L

    /** [bindIp] binds the server to that specific address (e.g. the Wi-Fi IP, so it isn't reachable
     *  from other interfaces); null binds all interfaces. Either way the port is ephemeral.
     *
     *  Returns immediately. The bound port surfaces on [port] from [WebSocketServer.onStart] -- the
     *  library's own "socket bound, selector ready" callback -- rather than being polled here.
     *  Polling `getPort()` from the caller raced the server thread: on a busy TV box the poll timed
     *  out before that thread was even scheduled to bind, so the host came back port 0 and was torn
     *  down while it was, in fact, about to come up. */
    fun start(bindIp: InetAddress? = null) {
        stop()
        _code.value = CompanionPairing.generateCode()
        val addr = if (bindIp != null) InetSocketAddress(bindIp, 0) else InetSocketAddress(0)
        val srv = object : WebSocketServer(addr) {
            override fun onStart() {
                // getPort() is valid here: doSetupSelectorAndServerThread() binds the server socket
                // (setting the `server` channel getPort() reads) before it calls onStart().
                val bound = port
                _port.value = bound
                _state.value = LinkState.Connecting
                Log.i(TAG, "host bound on port $bound")
            }
            override fun onOpen(conn: WebSocket, h: ClientHandshake) { _state.value = LinkState.Pairing }
            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                if (conn == peerConn) { peerConn = null; _connectedPeerId.value = null; _state.value = LinkState.Connecting }
            }
            override fun onError(conn: WebSocket?, ex: Exception) { Log.w(TAG, "host err", ex) }
            override fun onMessage(conn: WebSocket, message: String) { onHostMessage(conn, message) }
        }.apply { isReuseAddr = true; connectionLostTimeout = 0 }
        server = srv
        srv.start()
        startHeartbeat { peerConn }
    }

    private fun onHostMessage(conn: WebSocket, message: String) {
        if (conn == peerConn) lastSeen = System.currentTimeMillis()
        val env = decodeEnvelope(message) ?: return
        when (env.type) {
            TYPE_HELLO -> {
                val r = CompanionPairing.decideHost(_code.value, env.payload, peers, CompanionPairing::newToken)
                when (r) {
                    is PairResult.Accept -> {
                        val did = env.payload.optString("deviceId"); val nm = env.payload.optString("name")
                        val remote = conn.remoteSocketAddress
                        peers.save(Peer(did, nm, r.token, remote?.address?.hostAddress ?: "", remote?.port ?: 0))
                        peerConn?.takeIf { it != conn }?.let { runCatching { it.close() } }
                        peerConn = conn
                        lastSeen = System.currentTimeMillis()   // reset for the NEW peer
                        _connectedPeerId.value = did
                        conn.send(newEnvelope(TYPE_WELCOME, JSONObject()
                            .put("deviceId", identity.deviceId).put("name", identity.deviceName)
                            .put("token", r.token)).encode())
                        _state.value = LinkState.Connected
                    }
                    is PairResult.Reject -> {
                        conn.send(newEnvelope(TYPE_REJECT, JSONObject().put("reason", r.reason)).encode())
                        conn.close()
                    }
                }
            }
            TYPE_PING -> conn.send(newEnvelope(TYPE_PONG, JSONObject()).encode())
            TYPE_PONG -> {}
            else -> _incoming.tryEmit(env)
        }
    }

    fun send(env: Envelope) { runCatching { peerConn?.send(env.encode()) } }

    fun stop() {
        heartbeat?.cancel(); heartbeat = null
        runCatching { server?.stop(500) }; server = null; peerConn = null
        _port.value = 0
        _connectedPeerId.value = null
        _state.value = LinkState.Idle
    }

    private fun startHeartbeat(conn: () -> WebSocket?) {
        lastSeen = System.currentTimeMillis()
        heartbeat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PING_MS)
                val c = conn() ?: continue
                runCatching { c.send(newEnvelope(TYPE_PING, JSONObject()).encode()) }
                if (System.currentTimeMillis() - lastSeen > DEAD_MS) runCatching { c.close() }
            }
        }
    }
}

/** CONTROLLER: dials the host, authenticates, auto-reconnects with the stored token. */
class CompanionControllerTransport(
    private val scope: CoroutineScope,
    private val identity: CompanionIdentity,
    private val peers: PeerStore,
) {
    private val _state = MutableStateFlow(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()
    // The deviceId of the host we authenticated with (from `welcome`), null once disconnected. The
    // UI uses it to mark the already-connected host in the discovered list.
    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    private var client: WebSocketClient? = null
    private var loop: Job? = null
    @Volatile private var lastSeen = 0L
    @Volatile private var hostDeviceId: String? = null
    @Volatile private var establishedThisDial = false

    fun connect(ip: String, port: Int, code: String?, knownDeviceId: String? = null) {
        disconnect()
        hostDeviceId = knownDeviceId
        loop = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                _state.value = if (attempt == 0) LinkState.Connecting else LinkState.Reconnecting
                val established = dialOnce(ip, port, code)
                if (!isActive) break
                attempt = if (established) 0 else attempt + 1
                delay(backoffDelayMs(attempt.coerceAtLeast(1)))
            }
        }
    }

    /** Returns when the connection ends. */
    private suspend fun dialOnce(ip: String, port: Int, code: String?): Boolean {
        val done = CompletableDeferred<Boolean>()
        val token = hostDeviceId?.let { peers.find(it)?.token }
        val c = object : WebSocketClient(URI("ws://$ip:$port")) {
            override fun onOpen(h: ServerHandshake) {
                _state.value = LinkState.Pairing
                val payload = JSONObject().put("deviceId", identity.deviceId).put("name", identity.deviceName)
                if (token != null) payload.put("token", token) else if (code != null) payload.put("code", code)
                send(newEnvelope(TYPE_HELLO, payload).encode())
            }
            override fun onMessage(message: String) { onCtrlMessage(message) }
            override fun onClose(code: Int, reason: String, remote: Boolean) { if (!done.isCompleted) done.complete(false) }
            override fun onError(ex: Exception) { Log.w(TAG, "ctrl err", ex) }
        }
        client = c
        lastSeen = System.currentTimeMillis()
        establishedThisDial = false
        runCatching { c.connectBlocking() }
        val hb = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PING_MS)
                runCatching { c.send(newEnvelope(TYPE_PING, JSONObject()).encode()) }
                if (System.currentTimeMillis() - lastSeen > DEAD_MS) { runCatching { c.close() }; break }
            }
        }
        try { done.await() } finally { hb.cancel() }
        return establishedThisDial   // per FIX 1
    }

    private fun onCtrlMessage(message: String) {
        lastSeen = System.currentTimeMillis()
        val env = decodeEnvelope(message) ?: return
        when (env.type) {
            TYPE_WELCOME -> {
                val did = env.payload.optString("deviceId"); val nm = env.payload.optString("name")
                val tok = env.payload.optString("token")
                hostDeviceId = did
                _connectedDeviceId.value = did
                val remote = (client as? WebSocketClient)?.remoteSocketAddress
                peers.save(Peer(did, nm, tok, remote?.address?.hostAddress ?: "", remote?.port ?: 0))
                establishedThisDial = true
                _state.value = LinkState.Connected
            }
            TYPE_REJECT -> {
                loop?.cancel(); loop = null
                runCatching { client?.close() }; client = null
                _state.value = LinkState.Error
            }
            TYPE_PING -> client?.send(newEnvelope(TYPE_PONG, JSONObject()).encode())
            TYPE_PONG -> {}
            else -> _incoming.tryEmit(env)
        }
    }

    fun send(env: Envelope) { runCatching { client?.send(env.encode()) } }

    fun disconnect() {
        loop?.cancel(); loop = null
        runCatching { client?.close() }; client = null
        _connectedDeviceId.value = null
        _state.value = LinkState.Idle
    }
}
