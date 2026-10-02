package com.arkiv.player.companion

import com.arkiv.player.data.sync.SyncApply
import com.arkiv.player.data.sync.SyncCursorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Reads one [table]'s rows changed since [cursor], ASCENDING by `updatedAt`, already encoded to
 * the wire JSON shape [SyncApply] decodes (see `SyncMappers`). Defined here (ruling R3) because
 * the engine is the only consumer; Task 7's `RoomSyncSource` implements it over the DAOs'
 * `getXSince` queries (Task 2).
 */
interface SyncSource {
    suspend fun changedSince(table: String, cursor: Long): List<JSONObject>
}

/**
 * A synced table whose rows depend on WHICH peer they go to or come from (plugin passwords, sealed
 * with the key agreed with that peer: `PluginSecretSync`). It is announced in our hello, and pushed,
 * only while [ready] for the connected peer, and only to a peer whose own hello listed it -- an older
 * build never does, so it never gets one of these rows.
 */
interface PeerScopedTable {
    val table: String

    /** Fires when this table has something new to push that no database change signals (the engine pushes on it). */
    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    fun ready(peerId: String): Boolean
    suspend fun changedSince(cursor: Long, peerId: String): List<JSONObject>

    /** False when the row could not be applied yet: the pull cursor then stays, and it comes again. */
    suspend fun apply(row: JSONObject, peerId: String): Boolean
}

/**
 * Symmetric LAN-sync coordinator: runs for the lifetime of a companion link, pushing local changes
 * to the peer and applying the peer's changes locally. "Symmetric" is the key word: responding to
 * a peer's `sync_hello` only PUSHES this device's rows. To PULL the peer's rows this device must
 * ALSO send its own `sync_hello` -- sent on every transition to a connected (non-null) peer, see
 * [start] -- and let the peer's `sync_rows` response drive the pull.
 *
 * Three independent jobs run for as long as the link is up:
 * - the [incoming] collector, handling `sync_hello`/`sync_rows`/`sync_done` from the peer;
 * - a debounced push driven by [changes] (DB invalidation for the six synced tables), so a local
 *   edit -- especially watch progress, written roughly every 5s -- reaches the peer without
 *   waiting for the next full hello round;
 * - a collector on the connected-peer signal ([start]'s `peer` param) that fires our own
 *   `sync_hello` on each Connected edge -- see [start]'s KDoc for why this can't just be a
 *   one-shot check at [start] time.
 *
 * [source] (row reads) and [changes] (the DB-invalidation signal) are separate constructor params
 * on purpose: Task 7 wires them from two different places (a Room-backed `SyncSource` and a
 * separate invalidation flow), not a single combined dependency.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class) // Flow.debounce -- stable behavior, preview-annotated API
class CompanionSyncEngine(
    private val scope: CoroutineScope,
    private val incoming: SharedFlow<Envelope>,
    private val send: (Envelope) -> Unit,
    private val source: SyncSource,
    private val apply: SyncApply,
    private val cursors: SyncCursorStore,
    private val changes: Flow<Unit>,
    private val peerScoped: PeerScopedTable? = null,
    /** Counts only (tables, row numbers): never a row's content. */
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("CompanionSync", it) } },
) {
    private var incomingJob: Job? = null
    private var changesJob: Job? = null
    private var peerJob: Job? = null

    /** The connected peer, set by [start]'s peer collector to whatever [start]'s `peer` StateFlow
     *  most recently emitted (null while disconnected). Read by [handleRows] to key the pull-cursor
     *  advance -- a plain `StateFlow.value` read, always current, no caching/race window of its own
     *  (see [start]'s KDoc for why the hello itself can't just be a one-shot check here). */
    private lateinit var peer: StateFlow<String?>

    /**
     * In-memory only: the highest `updatedAt` already pushed per table, this session. NOT
     * persisted -- push has no cursor of its own (see [SyncCursorStore]'s KDoc): on reconnect the
     * peer's own `sync_hello` (carrying ITS last-pulled cursor) re-drives the catch-up from
     * scratch, so losing this map on process death is harmless, just a touch more re-sent data.
     *
     * Mutated from TWO independently-launched coroutines -- [incomingJob]'s hello-response push
     * and [changesJob]'s debounced incremental push (the third job, [peerJob], never touches this
     * map) -- which, on the multi-threaded dispatcher this engine is actually wired onto in
     * production (`CompanionManager`'s `CoroutineScope(SupervisorJob() + Dispatchers.IO)`), can run
     * concurrently on different threads. Every read AND write of this map (see
     * [pushTable]/[pushIncrementalTable]) goes through [pushMutex] so a plain `HashMap` never sees
     * a concurrent read-modify-write.
     */
    private val lastPushed = mutableMapOf<String, Long>()

    /** Guards [lastPushed] -- see its KDoc. */
    private val pushMutex = Mutex()

    /**
     * Idempotent -- calling twice while already running is a no-op, same guard as
     * [CompanionPlayReceiver.start].
     *
     * [peer] is the connected-peer signal (deviceId, or null while disconnected) for whichever role
     * is active this session -- see `CompanionManager.startSync`. The engine fires its own
     * `sync_hello` on every transition to a non-null value, NOT just once here at [start] time:
     * `CompanionManager.startSync` calls this synchronously from the `ON_START` lifecycle observer,
     * while the companion link is typically still coming up asynchronously, so a one-shot check of
     * the peer at this instant would almost always see null and skip the hello for good (that was
     * the bug -- the peer job below is what fixes it). [peer] being a `StateFlow` means an
     * ALREADY-connected peer at [start] time is handled too: collecting a `StateFlow` immediately
     * replays its current value to a fresh collector, so that case also gets its hello, same as
     * every later reconnect.
     */
    fun start(peer: StateFlow<String?>) {
        if (incomingJob != null) return
        this.peer = peer

        incomingJob = scope.launch {
            incoming.collect { env ->
                when (env.type) {
                    TYPE_SYNC_HELLO -> handleHello(env)
                    TYPE_SYNC_ROWS -> handleRows(env)
                    TYPE_SYNC_DONE -> Unit // round closed; cursors already advanced off each
                    // table's final sync_rows page, not this message (R2) -- see SyncDone's KDoc.
                }
            }
        }
        changesJob = scope.launch {
            changes.debounce(PUSH_DEBOUNCE_MS).collect { pushIncremental() }
        }

        // Start a round from THIS side too (see the class KDoc on symmetry) on every Connected
        // edge. `peer` being a StateFlow already gives "one hello per edge, not one per emission"
        // for free -- a StateFlow conflates and never re-emits the same value to a collector twice
        // in a row (see kotlinx.coroutines.flow.distinctUntilChanged's own KDoc: applying it to a
        // StateFlow is a documented no-op, so it isn't used here). A null (disconnected) value is
        // skipped: nothing to key `since` by, and the collector below still runs so the NEXT
        // Connected edge -- or the peer's own hello, once linked -- triggers our push side.
        peerJob = scope.launch {
            peer.collect { pid ->
                if (pid == null) return@collect
                val since = TABLES.filter { table -> tableHere(table, pid) }.associateWith { table -> cursors.pulled(pid, table) }
                send(newEnvelope(TYPE_SYNC_HELLO, SyncHello(since).toPayload()))
            }
        }
    }

    /** Cancels all three jobs so nothing keeps collecting past link teardown (and so an infinite
     *  `SharedFlow`/`StateFlow.collect` doesn't hang a `runTest`). */
    fun stop() {
        incomingJob?.cancel()
        incomingJob = null
        changesJob?.cancel()
        changesJob = null
        peerJob?.cancel()
        peerJob = null
    }

    /**
     * The tables the connected peer's last hello listed, with the id of THAT peer; null until one hello. A TV can
     * accept a different controller, so tables announced by one peer never count for another: an older build
     * that connects later has not said what it knows, and its SyncApply throws on a table it has never heard of.
     */
    @Volatile private var peerTables: Pair<String?, Set<String>>? = null

    private fun peerKnows(table: String): Boolean {
        val pid = peer.value
        if (!tableHere(table, pid)) return false
        if (table !in OPTIONAL_TABLES) return true
        val known = peerTables ?: return false
        return known.first == pid && table in known.second
    }

    /** Whether THIS side syncs [table] with [peerId] at all: a peer-scoped one only with its own one, and while ready. */
    private fun tableHere(table: String, peerId: String?): Boolean {
        if (table != PEER_SCOPED_TABLE) return true
        val scoped = peerScoped ?: return false
        return peerId != null && scoped.table == table && scoped.ready(peerId)
    }

    private suspend fun handleHello(env: Envelope) {
        val hello = SyncHello.fromPayload(env.payload)
        // The link's peer id is derived asynchronously: right after connecting, the peer's hello can
        // arrive while it is still null. Keyed to null, the announced tables never counted for the
        // real peer, so nothing optional (plugins, Nuvio repos, own sources) was pushed to it until
        // the next connection. Wait briefly for the id instead.
        val pid = peer.value ?: withTimeoutOrNull(PEER_ID_WAIT_MS) { peer.filterNotNull().first() }
        peerTables = pid to hello.since.keys.toSet()
        log("hello from the peer: ${hello.since.size} tables")
        var overallHwm = 0L
        for (table in TABLES) {
            if (!peerKnows(table)) continue
            val sentHwm = pushTable(table, hello.since[table] ?: 0L)
            if (sentHwm > overallHwm) overallHwm = sentHwm
        }
        send(newEnvelope(TYPE_SYNC_DONE, SyncDone(overallHwm).toPayload()))
    }

    private suspend fun handleRows(env: Envelope) {
        val syncRows = SyncRows.fromPayload(env.payload)
        log("received ${syncRows.rows.size} ${syncRows.table}")
        if (syncRows.table == PEER_SCOPED_TABLE) {
            val pid = peer.value ?: return
            if (!tableHere(syncRows.table, pid)) return
            val scoped = peerScoped ?: return
            var all = true
            syncRows.rows.forEach { row -> if (!scoped.apply(row, pid)) all = false }
            if (!syncRows.more && all) cursors.setPulled(pid, syncRows.table, syncRows.hwm)
            return
        }
        syncRows.rows.forEach { row -> apply.apply(syncRows.table, row) }
        if (!syncRows.more) {
            // R2: the pull cursor advances per-table from THAT table's final page hwm, not from
            // sync_done. Guarded on a non-null peer id -- nothing to key the cursor by otherwise.
            val pid = peer.value ?: return
            cursors.setPulled(pid, syncRows.table, syncRows.hwm)
        }
    }

    private suspend fun pushIncremental() {
        log("local change: pushing to ${if (peer.value == null) "no peer" else "the peer"}")
        for (table in TABLES) {
            if (peerKnows(table)) pushIncrementalTable(table)
        }
    }

    /**
     * Reads [table]'s rows changed since [cursor] (an explicit cursor -- the hello-response path's
     * `since[table]` from the peer, which never touches [lastPushed]) and pushes them. See
     * [sendAndAdvance] for what "pushes" means and the return value.
     */
    private suspend fun pushTable(table: String, cursor: Long): Long =
        pushMutex.withLock { sendAndAdvance(table, cursor) }

    /**
     * Same as [pushTable], but for the incremental path: the cursor itself is
     * `lastPushed[table] ?: 0`, so unlike [pushTable] this ALSO reads [lastPushed] -- and that read
     * must happen under [pushMutex] too, not just the write, otherwise it can race a concurrent
     * [pushTable] call's write from the other job.
     */
    private suspend fun pushIncrementalTable(table: String): Long =
        pushMutex.withLock { sendAndAdvance(table, lastPushed[table] ?: 0L) }

    /**
     * Reads [table]'s rows changed since [cursor], sends them as one or more `sync_rows` pages
     * (ascending, chunked to the transport's message budget), advances [lastPushed] for [table] to
     * the highest `updatedAt` sent, and returns that value. Returns 0 and sends nothing, leaving
     * [lastPushed] untouched, when there's nothing new to push.
     *
     * MUST only be called while holding [pushMutex] -- see [pushTable]/[pushIncrementalTable].
     */
    private suspend fun sendAndAdvance(table: String, cursor: Long): Long {
        val rows = if (table == PEER_SCOPED_TABLE) {
            val pid = peer.value ?: return 0L
            peerScoped?.changedSince(cursor, pid).orEmpty()
        } else {
            source.changedSince(table, cursor)
        }
        if (rows.isEmpty()) return 0L
        log("sending ${rows.size} $table")

        val pages = chunkRows(rows)
        for ((index, page) in pages.withIndex()) {
            val pageHwm = page.maxOf { it.optLong("updatedAt") }
            val more = index < pages.lastIndex
            send(newEnvelope(TYPE_SYNC_ROWS, SyncRows(table, page, pageHwm, more).toPayload()))
        }

        val maxSent = rows.maxOf { it.optLong("updatedAt") }
        lastPushed[table] = maxOf(lastPushed[table] ?: 0L, maxSent)
        return maxSent
    }

    private companion object {
        /**
         * The seven tables that travel through companion sync -- pinned to match [SyncApply]'s
         * `when(table)` cases. Mirrors (doesn't share: that one is `private`)
         * `com.arkiv.player.data.db.SyncTriggers.TABLES`.
         */
        val TABLES = listOf(
            "items", "episodes", "playback", "skip_markers", "live_favorites", "live_recents",
            // The text of pasted lists BEFORE their rows: when a pasted list's row lands, its text is already there.
            OWN_LIST_PARTS_TABLE, "own_live_sources", "plugin_installs", "nuvio_repos", PEER_SCOPED_TABLE,
        )

        /**
         * Tables added after the first six. An older peer's SyncApply throws on a table it does not
         * know, so these are pushed only to a peer whose hello lists them (see [peerKnows]).
         */
        val OPTIONAL_TABLES = setOf(OWN_LIST_PARTS_TABLE, "own_live_sources", "plugin_installs", "nuvio_repos", PEER_SCOPED_TABLE)

        /** Pasted "Mis canales" lists' text, in parts that fit one message (`OwnPastedList`). */
        const val OWN_LIST_PARTS_TABLE = "own_live_list_parts"

        /** Plugin passwords, sealed per peer ([PeerScopedTable]); after `plugin_installs`, whose rows it needs. */
        const val PEER_SCOPED_TABLE = "plugin_secrets"

        const val PUSH_DEBOUNCE_MS = 3000L

        /** How long a peer's hello waits for the link to say who that peer is. */
        const val PEER_ID_WAIT_MS = 5000L
    }
}
