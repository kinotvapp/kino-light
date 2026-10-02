package com.arkiv.player.cast

import android.content.SharedPreferences
import android.util.Log
import com.arkiv.player.data.local.EnqueueOutcome
import com.arkiv.player.data.local.PrepState
import com.arkiv.player.dlna.DlnaDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The decisions of [DownloadForTv], pure so they are tested on the JVM. */
object DownloadForTvPolicy {

    enum class Kind { DOWNLOAD, SEND_DOWNLOAD }

    const val DOWNLOAD_LABEL = "Descargar y preparar para la TV"
    const val DOWNLOAD_EXPLANATION =
        "Este TV no pudo reproducir el video en línea. Kino puede descargarlo y prepararlo en tu teléfono para que lo " +
            "reproduzca sin depender de internet. Tarda unos minutos y ocupa espacio en tu teléfono."
    const val SEND_LABEL = "Enviar la descarga a la TV"
    const val SEND_EXPLANATION =
        "Ya lo tienes descargado. Kino lo prepara en tu teléfono si hace falta y lo envía a la TV desde el archivo, " +
            "sin depender de internet."

    /**
     * What the last option is for an exhausted cast: a VOD title only (never live), never on a TV
     * (nothing downloads there). Already downloaded: send that file -- unless its preparation found
     * something an MP4 cannot carry ([keptAsIs]), when the same file would fail the same way.
     * Otherwise only a title whose source can download ([downloadable]: a strategy for it, a
     * plugin's `download` capability).
     */
    fun offer(e: CastGaveUp.Exhausted, isTelevision: Boolean, downloadable: Boolean, downloaded: Boolean, keptAsIs: Boolean): Kind? = when {
        e.live || e.episodeId.isBlank() || isTelevision -> null
        downloaded -> if (keptAsIs) null else Kind.SEND_DOWNLOAD
        downloadable -> Kind.DOWNLOAD
        else -> null
    }

    /**
     * Whether a cast starting from a player that shows [screenEpisode] should first reopen it from
     * the download: the title is on the phone ([downloaded]) but the player is not playing that file
     * ([playingLocalEpisode] is what the phone's player opened from disk last) -- it was opened
     * before the download finished. The TV then gets the local file, not the network.
     */
    fun reopenAsLocal(screenEpisode: String?, downloaded: Set<String>, playingLocalEpisode: String?): Boolean =
        !screenEpisode.isNullOrBlank() && screenEpisode in downloaded && playingLocalEpisode != screenEpisode

    /** Pending entries older than this are forgotten (a download that never finished). */
    const val PENDING_MAX_AGE_MS = 3L * 24 * 3600 * 1000

    /** Pending entries as stored: `episodeId \t createdAt \t title` per line, the stale ones dropped. */
    fun decodePending(raw: String?, now: Long): Map<String, Pair<Long, String>> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            val at = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (p[0].isBlank() || now - at > PENDING_MAX_AGE_MS) null else p[0] to (at to p.getOrElse(2) { "" })
        }.toMap()

    fun encodePending(entries: Map<String, Pair<Long, String>>): String =
        entries.entries.joinToString("\n") { (id, v) -> listOf(id, v.first.toString(), v.second.replace('\t', ' ').replace('\n', ' ')).joinToString("\t") }
}

/** Where a prepared download goes once it is ready: the TV that gave up, at its position. */
data class TvTarget(val receiver: CastGaveUp.Receiver, val dlnaDevice: DlnaDevice?, val positionMs: Long)

/**
 * "Send this title to the TV" for the player screen that shows it ([com.arkiv.player.ui.player.SendToTvPrompt]):
 * from the Downloads screen's "Enviar a la TV", from the "Listo para la TV" notification, or on its
 * own when a download prepared for a TV that gave up is ready ([DownloadForTv]). [target] null: ask
 * which TV. Taken once, by the player of that title; a stale request is dropped.
 */
object SendToTv {
    class Request(val episodeId: String, val target: TvTarget?, val createdAt: Long = System.currentTimeMillis())

    val requests = MutableStateFlow<Request?>(null)

    const val MAX_AGE_MS = 60_000L

    fun offer(episodeId: String, target: TvTarget? = null) {
        requests.value = Request(episodeId, target)
    }

    /** [r] when it is still pending, for [episodeId] and fresh; never handed out twice. */
    fun take(r: Request?, episodeId: String, now: Long = System.currentTimeMillis()): Request? {
        if (r == null || r.episodeId != episodeId || !requests.compareAndSet(r, null)) return null
        return r.takeIf { now - it.createdAt <= MAX_AGE_MS }
    }
}

/**
 * Opens the player for an episode again, replacing the player screen on top: what turns a title
 * playing from the network into the download now on the phone (`LocalLibrary.fileFor` wins on every
 * load). Taken by the app's root navigation; holds the latest request until then (a cold start).
 */
object PlayerReopen {
    val requests = MutableStateFlow<String?>(null)

    fun request(episodeId: String) {
        requests.value = episodeId
    }

    fun take(episodeId: String): Boolean = requests.compareAndSet(episodeId, null)
}

/** The player screen in the foreground and the title it shows (set by `CastScreenPresence`). */
object PlayerOnScreen {
    @Volatile var episodeId: String? = null
    @Volatile var foreground: Boolean = false

    fun shows(episodeId: String): Boolean = foreground && this.episodeId == episodeId
}

/**
 * The last option of an exhausted VOD cast ([CastGaveUp.lastResort]): "Descargar y preparar para la
 * TV", or "Enviar la descarga a la TV" when the title is already downloaded ([DownloadForTvPolicy]).
 *
 * Accepted, the title is queued in the existing download system (its strategy, a plugin's `download`
 * capability, the TV ban) or, already downloaded, its preparation is asked for; either way it is
 * remembered as waiting for the TV ([pending], kept across a restart). When its preparation ends
 * ([onPrepared], from `Mp4PrepWorker`), "Listo para la TV" is notified, and if the player still shows
 * that title it is reopened from the file and sent to the same TV (a DLNA renderer is handed the
 * file directly; a Chromecast session still up gets it as the reopened player loads; otherwise the
 * person picks the TV).
 */
class DownloadForTv(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val isTelevision: () -> Boolean,
    private val downloadable: (episodeId: String) -> Boolean,
    /** The completed downloads' episode ids, as the table changes. */
    completedIds: Flow<Set<String>>,
    /** Whether the download's preparation left it as it is for good (`PrepState.UNSUPPORTED`). */
    private val keptAsIs: (episodeId: String) -> Boolean,
    private val enqueueDownload: suspend (episodeId: String) -> EnqueueOutcome,
    private val requestPrep: (episodeId: String) -> Unit,
    /** "Listo para la TV" for [episodeId] ([title] may be blank). */
    private val notifyReady: (episodeId: String, title: String) -> Unit,
    /** A short message to the person (a toast). */
    private val say: (String) -> Unit,
) : CastGaveUp.LastResort {

    @Volatile
    var downloaded: Set<String> = emptySet()
        private set

    private val targets = ConcurrentHashMap<String, TvTarget>()

    init {
        scope.launch { completedIds.collect { downloaded = it } }
    }

    override fun offerFor(e: CastGaveUp.Exhausted): CastGaveUp.Offer? {
        val kind = DownloadForTvPolicy.offer(
            e,
            isTelevision = isTelevision(),
            downloadable = runCatching { downloadable(e.episodeId) }.getOrDefault(false),
            downloaded = e.episodeId in downloaded,
            keptAsIs = runCatching { keptAsIs(e.episodeId) }.getOrDefault(false),
        ) ?: return null
        return when (kind) {
            DownloadForTvPolicy.Kind.DOWNLOAD ->
                CastGaveUp.Offer(DownloadForTvPolicy.DOWNLOAD_LABEL, DownloadForTvPolicy.DOWNLOAD_EXPLANATION, ::startDownload)
            DownloadForTvPolicy.Kind.SEND_DOWNLOAD ->
                CastGaveUp.Offer(DownloadForTvPolicy.SEND_LABEL, DownloadForTvPolicy.SEND_EXPLANATION, ::startSend)
        }
    }

    private fun startDownload(e: CastGaveUp.Exhausted) {
        remember(e)
        scope.launch {
            when (runCatching { enqueueDownload(e.episodeId) }.getOrNull()) {
                EnqueueOutcome.QUEUED, EnqueueOutcome.ALREADY_QUEUED ->
                    say("Descargando \"${e.title}\" para la TV. Te avisamos cuando esté listo.")
                EnqueueOutcome.ALREADY_DOWNLOADED -> {
                    say("Ya estaba descargado. Preparándolo para la TV…")
                    requestPrep(e.episodeId)
                }
                EnqueueOutcome.UNAVAILABLE_ON_TV, null -> {
                    forget(e.episodeId)
                    say("No se pudo descargar en este dispositivo.")
                }
            }
        }
    }

    private fun startSend(e: CastGaveUp.Exhausted) {
        remember(e)
        say("Preparando la descarga para la TV…")
        requestPrep(e.episodeId)
    }

    private fun remember(e: CastGaveUp.Exhausted) {
        targets[e.episodeId] = TvTarget(e.receiver, e.dlnaDevice, e.positionMs)
        synchronized(this) {
            val now = System.currentTimeMillis()
            val pending = DownloadForTvPolicy.decodePending(prefs.getString(KEY_PENDING, null), now) + (e.episodeId to (now to e.title))
            prefs.edit().putString(KEY_PENDING, DownloadForTvPolicy.encodePending(pending)).apply()
        }
        Log.i(TAG, "waiting for ${e.episodeId} to be ready for the TV (${e.receiver})")
    }

    /** Takes [episodeId] off the waiting list; its title, or null when it was not waiting. */
    private fun forget(episodeId: String): String? = synchronized(this) {
        val pending = DownloadForTvPolicy.decodePending(prefs.getString(KEY_PENDING, null), System.currentTimeMillis())
        val entry = pending[episodeId] ?: return null
        prefs.edit().putString(KEY_PENDING, DownloadForTvPolicy.encodePending(pending - episodeId)).apply()
        entry.second
    }

    /**
     * [episodeId]'s preparation ended in [state] (null: there was no finished download to prepare
     * yet). A title waiting for the TV is ready now -- whatever the state: an MP4, or the original
     * kept as it is, which the local cast paths still serve.
     */
    fun onPrepared(episodeId: String, state: PrepState?) {
        if (state == null) return
        val title = forget(episodeId) ?: return
        val target = targets.remove(episodeId)
        Log.i(TAG, "$episodeId ready for the TV ($state), on screen=${PlayerOnScreen.shows(episodeId)}")
        notifyReady(episodeId, title)
        if (PlayerOnScreen.shows(episodeId)) {
            SendToTv.offer(episodeId, target)
            PlayerReopen.request(episodeId)
        }
    }

    private companion object {
        const val TAG = "KinoDownloadForTv"
        const val KEY_PENDING = "download_for_tv_pending"
    }
}
