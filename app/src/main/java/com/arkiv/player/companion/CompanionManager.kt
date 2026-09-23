package com.arkiv.player.companion

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.arkiv.player.data.sync.RoomSyncSource
import com.arkiv.player.data.sync.SyncApply
import com.arkiv.player.data.sync.SyncCursorStore
import com.arkiv.player.playback.LanIp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

private const val TAG = "CompanionManager"

/** How long [startHost] waits for the WebSocket server thread to bind and report its port before
 *  giving up. Generous on purpose: a busy TV box can take several seconds to schedule that thread,
 *  and the wait ends the instant the port arrives, so the ceiling only bites on a real failure. */
private const val BIND_TIMEOUT_MS = 20_000L

/**
 * Thin coordinator over the companion LAN parts: identity, peer store, mDNS discovery, and the
 * host/controller WebSocket transports. Holds no protocol logic of its own -- that all lives in
 * [CompanionProtocol]/[CompanionPairing]/[CompanionTransport]. This just wires them together and
 * is the single entry point the UI (Task 8) talks to.
 *
 * Nothing here does IO at construction: [CompanionDiscovery]/[CompanionHostTransport]/
 * [CompanionControllerTransport] only start work when [startHost], [startBrowsing] or
 * [connect]/[connectByIp] is called explicitly. Combined with `AppGraph`'s `by lazy`, that means
 * the companion link never starts at app launch.
 */
class CompanionManager(context: Context) {
    private val appContext = context.applicationContext

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val identity: CompanionIdentity = CompanionIdentity(appContext)
    private val peers: PeerStore = PeerStore(appContext)
    private val discovery: CompanionDiscovery = CompanionDiscovery(appContext, identity)

    private val hostTransport: CompanionHostTransport = CompanionHostTransport(scope, identity, peers)
    private val controllerTransport: CompanionControllerTransport =
        CompanionControllerTransport(scope, identity, peers)

    /** Discovered hosts on the LAN (mDNS browse results). Empty until [startBrowsing] is called. */
    val hosts: StateFlow<List<DiscoveredHost>> = discovery.hosts

    /** The pairing code to show while hosting. Empty until [startHost] has generated one. */
    val code: StateFlow<String> = hostTransport.code

    /** The host's bound port, for the UI to show alongside the device's LAN IP. 0 until
     *  [startHost] resolves it. */
    val port: StateFlow<Int> = hostTransport.port

    /** deviceId of the host this controller is connected to (null when not connected). The UI marks
     *  that host in the discovered list so it doesn't offer to connect again. */
    val connectedDeviceId: StateFlow<String?> = controllerTransport.connectedDeviceId

    /** deviceId of the controller currently connected to this host, null when none is. The
     *  controller-side counterpart of [connectedDeviceId] -- see [startSync]. */
    val connectedPeerId: StateFlow<String?> = hostTransport.connectedPeerId

    private val _linkState = MutableStateFlow(LinkState.Idle)

    /** Reflects whichever role is currently active (host or controller); [LinkState.Idle] when
     *  neither [startHost] nor [connect]/[connectByIp] has been called yet. */
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)

    /** App-level envelopes (post-handshake) from whichever role is currently active. */
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private enum class Role { NONE, HOST, CONTROLLER }

    @Volatile private var activeRole: Role = Role.NONE
    private var followJob: Job? = null
    private var startHostJob: Job? = null
    private var autoConnectJob: Job? = null
    private var playReceiver: CompanionPlayReceiver? = null
    private var syncEngine: CompanionSyncEngine? = null

    /**
     * The connected peer's deviceId for whichever role is active this session -- HOST's
     * [connectedPeerId] or CONTROLLER's [connectedDeviceId] -- as a single [StateFlow] so
     * [CompanionSyncEngine] can observe the real Connected edge and fire its on-connect
     * `sync_hello` from there, instead of a one-shot check at [startSync] time (which runs
     * synchronously from the `ON_START` lifecycle observer, before the link is typically up --
     * see [startSync]'s KDoc).
     *
     * Built ONCE, for this manager's process lifetime (same pattern as [hostTransport]/
     * [controllerTransport] above), not per [startSync] call -- [startSync] is called on every
     * `ON_START`, and a fresh `combine`/`stateIn` per call would leak one uncancelled collector
     * per foreground cycle. [activeRole] is read (not collected) inside the `combine` lambda: it's
     * re-evaluated on every emission from either source `StateFlow`, and by the time either one
     * actually turns non-null the role switch that caused it ([followHost]/[followController]) has
     * already happened synchronously, so this never picks the wrong side.
     */
    private val syncPeerId: StateFlow<String?> =
        combine(hostTransport.connectedPeerId, controllerTransport.connectedDeviceId) { hostPeer, controllerPeer ->
            if (activeRole == Role.HOST) hostPeer else controllerPeer
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val wifiManager: WifiManager by lazy {
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val wifiLock: WifiManager.WifiLock by lazy {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL
        }
        wifiManager.createWifiLock(mode, "arkiv-companion-link").apply { setReferenceCounted(false) }
    }

    /**
     * Starts the WebSocket server and advertises it over mDNS. `host.start()` busy-polls for its
     * ephemeral port for up to 3s, so it's launched on the manager's IO scope rather than the
     * calling thread; `registerHost(port)` runs once the real port is known.
     *
     * If a controller link is active, it is stopped first (a single instance can't be both host
     * and controller at once) without releasing the WifiLock in between -- the host keeps it held.
     */
    fun startHost() {
        // Already hosting: don't restart. Re-entering the Conectar tab must not regenerate the code
        // or drop a phone that's already connected.
        if (activeRole == Role.HOST) return
        stopControllerIfActive()
        ensureWifiLock()
        followHost()
        startHostJob = scope.launch(Dispatchers.IO) {
            try {
                // Bind to the Wi-Fi IP so the server isn't reachable from other interfaces (e.g.
                // a VPN); resolved here (not on the caller's thread) since it can do a lookup.
                val bindIp = LanIp.current(appContext)?.let { InetAddress.getByName(it) }
                Log.i(TAG, "startHost: binding to ${bindIp?.hostAddress ?: "*"}")
                hostTransport.start(bindIp)
                // start() returns immediately; the server thread publishes the bound port on
                // hostTransport.port once WebSocketServer.onStart fires. Wait for it (event-driven,
                // with a ceiling) instead of polling -- registering mDNS only once it's really up.
                val boundPort = withTimeoutOrNull(BIND_TIMEOUT_MS) { hostTransport.port.first { it > 0 } }
                if (boundPort != null) {
                    Log.i(TAG, "startHost: bound on $boundPort, advertising over mDNS")
                    discovery.registerHost(boundPort)
                } else {
                    Log.w(TAG, "startHost: server did not bind within ${BIND_TIMEOUT_MS}ms")
                    stopHost()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "startHost failed", t)
                stopHost()   // releases WifiLock + unregisters + stops transport
            }
        }
    }

    fun stopHost() {
        // Cancel a still in-flight startHost() first so it can't finish binding/registering after
        // this stop -- otherwise it would leave an orphaned server + mDNS entry nothing stops.
        startHostJob?.cancel()
        startHostJob = null
        hostTransport.stop()
        discovery.unregisterHost()
        releaseWifiLock()
        // Clear the role so a failed/aborted host attempt can be retried by re-entering the tab: the
        // startHost() guard keys off activeRole, so leaving it HOST would block every later retry.
        if (activeRole == Role.HOST) activeRole = Role.NONE
    }

    fun startBrowsing() = discovery.startBrowsing()

    fun stopBrowsing() = discovery.stopBrowsing()

    /**
     * Connects to a discovered host. Passes the host's deviceId as `knownDeviceId` so the
     * controller can look up a previously stored token and reconnect without the code, even on a
     * fresh process (no in-memory state carried over from a prior connection).
     *
     * If a host link is active, it is stopped first (a single instance can't be both host and
     * controller at once).
     */
    fun connect(host: DiscoveredHost, code: String?) {
        stopHostIfActive()
        ensureWifiLock()
        followController()
        controllerTransport.connect(host.ip, host.port, code, knownDeviceId = host.deviceId)
    }

    /**
     * Connects by IP/port directly (mDNS fallback). Looks up a previously paired peer by its last
     * known IP so a token reconnect works without asking for the code again.
     *
     * If a host link is active, it is stopped first (a single instance can't be both host and
     * controller at once).
     */
    fun connectByIp(ip: String, port: Int, code: String?) {
        stopHostIfActive()
        ensureWifiLock()
        followController()
        val knownDeviceId = peers.all().firstOrNull { it.lastIp == ip }?.deviceId
        controllerTransport.connect(ip, port, code, knownDeviceId)
    }

    fun disconnect() {
        controllerTransport.disconnect()
        releaseWifiLock()
    }

    /**
     * Controller, app-wide: browse and connect to an already-paired host as soon as mDNS
     * (re)discovers it, so a paired phone stays linked on every screen -- not only on the Conectar
     * tab. Keyed on the discovery set, so a host that came back on a fresh port is picked up at once
     * (connect() re-dials the current address). Idempotent. Driven by the foreground lifecycle.
     */
    fun startAutoConnect() {
        startBrowsing()
        if (autoConnectJob != null) return
        autoConnectJob = scope.launch {
            hosts.collect { list ->
                val paired = list.firstOrNull { h -> peers.all().any { it.deviceId == h.deviceId } }
                    ?: return@collect
                val liveToThis = linkState.value == LinkState.Connected && connectedDeviceId.value == paired.deviceId
                if (!liveToThis) connect(paired, null)
            }
        }
    }

    fun stopAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        stopBrowsing()
    }

    /**
     * Host, app-wide: start turning incoming `play` envelopes into playback. Idempotent. The
     * resolve/open work is injected ([CompanionPlayReceiver.PlayResolver]/[onOpen]) so the
     * coordination stays testable and this class holds no Android playback knowledge.
     */
    fun startReceiving(resolver: CompanionPlayReceiver.PlayResolver, onOpen: (String) -> Unit) {
        if (playReceiver != null) return
        playReceiver = CompanionPlayReceiver(scope, incoming, ::send, resolver, onOpen).also { it.start() }
    }

    fun stopReceiving() {
        playReceiver?.stop()
        playReceiver = null
    }

    /**
     * Both roles, app-wide: start pushing/pulling the six synced tables for as long as the link is
     * up. Idempotent, same guard as [startReceiving]. [source]/[apply]/[cursors] come from OUTSIDE
     * (same pattern as [startReceiving]'s [resolver]/[onOpen]) so this class stays free of a direct
     * DB dependency, same as it already avoids forcing the credential lazies.
     *
     * The engine keys cursors on the PEER's deviceId, which differs by role: a controller's peer is
     * the host it dialed ([connectedDeviceId]); a host's peer is the controller connected to it
     * ([connectedPeerId]). [syncPeerId] already picks whichever matches [activeRole], as a
     * `StateFlow` the engine collects for the life of the link -- so it stays correct not just
     * across a reconnect within the same session, but crucially fires the engine's on-connect
     * `sync_hello` on the actual Connected transition instead of missing it: this method itself
     * runs synchronously from the `ON_START` lifecycle observer (`ArkivApp.wireCompanionLifecycle`),
     * typically before `startHost`/`startAutoConnect`'s async connect has produced a peer.
     */
    fun startSync(source: RoomSyncSource, apply: SyncApply, cursors: SyncCursorStore) {
        if (syncEngine != null) return
        syncEngine = CompanionSyncEngine(scope, incoming, ::send, source, apply, cursors, source.changes)
        syncEngine?.start(syncPeerId)
    }

    fun stopSync() {
        syncEngine?.stop()
        syncEngine = null
    }

    fun send(envelope: Envelope) {
        when (activeRole) {
            Role.HOST -> hostTransport.send(envelope)
            Role.CONTROLLER -> controllerTransport.send(envelope)
            Role.NONE -> {}
        }
    }

    fun pairedPeers(): List<Peer> = peers.all()

    fun forget(deviceId: String) {
        // Do NOT disconnect here: a live link may be to a DIFFERENT peer than the one being
        // forgotten. A forgotten-then-dropped active peer releases the WifiLock via followController's
        // Error handler; leaving the Connect screen releases it via onDispose.
        peers.remove(deviceId)
    }

    private fun followHost() {
        activeRole = Role.HOST
        followJob?.cancel()
        followJob = scope.launch {
            launch { hostTransport.state.collect { _linkState.value = it } }
            launch { hostTransport.incoming.collect { _incoming.emit(it) } }
        }
    }

    private fun followController() {
        activeRole = Role.CONTROLLER
        followJob?.cancel()
        followJob = scope.launch {
            launch {
                controllerTransport.state.collect { s ->
                    _linkState.value = s
                    // The reconnect loop is already stopped on reject/terminal (transport's
                    // TYPE_REJECT handling); release the lock once the link is terminal too, but
                    // not on Reconnecting -- that's a live link still retrying.
                    if (s == LinkState.Error) releaseWifiLock()
                }
            }
            launch { controllerTransport.incoming.collect { _incoming.emit(it) } }
        }
    }

    /** Stops the host transport/mDNS registration if the host role is currently active. Used by
     *  [connect]/[connectByIp] to enforce host/controller mutual exclusion before switching role. */
    private fun stopHostIfActive() {
        if (activeRole != Role.HOST) return
        hostTransport.stop()
        discovery.unregisterHost()
    }

    /** Stops the controller transport if the controller role is currently active. Used by
     *  [startHost] to enforce host/controller mutual exclusion before switching role. */
    private fun stopControllerIfActive() {
        if (activeRole != Role.CONTROLLER) return
        controllerTransport.disconnect()
        followJob?.cancel()
        followJob = null
    }

    /** Acquires the (non-ref-counted) WifiLock only if it isn't already held, so a role switch --
     *  which stops the other role's transport but never releases the lock -- ends up with exactly
     *  one acquire outstanding rather than two. */
    private fun ensureWifiLock() {
        runCatching { if (!wifiLock.isHeld) wifiLock.acquire() }
    }

    private fun releaseWifiLock() {
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
    }
}
