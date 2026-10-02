package com.arkiv.player.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.AppGraph
import com.arkiv.player.cast.CastDlnaOffer
import com.arkiv.player.cast.CastSessionManager
import com.arkiv.player.dlna.DlnaDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the automatic "Nueva versión" prompt has to wait: while a cast session is up or the
 * cast trouble dialog is showing. The OTA dialog once covered "Se cortó en el Chromecast" (2026-10-01),
 * leaving the person unable to answer it; the cast wins, the update is offered once it is over.
 */
internal fun updatePromptWaitsForCast(casting: Boolean, troubleShown: Boolean): Boolean = casting || troubleShown

/** [updatePromptWaitsForCast] for the app's cast session, as state. False without one (a TV, no Play services). */
@Composable
fun rememberUpdatePromptWaitsForCast(graph: AppGraph): Boolean {
    var session by remember { mutableStateOf<CastSessionManager?>(graph.castSession) }
    LaunchedEffect(session == null) {
        while (session == null) {
            delay(3_000)
            session = graph.castSession
        }
    }
    val s = session ?: return false
    val casting by s.casting.collectAsStateWithLifecycle()
    val trouble by s.trouble.collectAsStateWithLifecycle()
    return updatePromptWaitsForCast(casting, trouble?.retrying == false)
}

/**
 * What the phone says when the Chromecast stops playing by itself (see `CastIdleWatch`).
 *
 * Mounted once at the app root, not inside the player: a cast keeps going with the player closed,
 * and the player's composable is already at the size the ART verifier tolerates.
 *
 * The first drop is retried on its own and only announced; a second one asks: "Reintentar" or
 * "Ver en el celular". Never a loop -- the session manager decides that, this only shows it.
 *
 * When the Chromecast failed the same title twice and the player screen shows it, the same TV is
 * looked for over DLNA ([CastDlnaOffer]: a renderer at the Cast device's IP, or with its name), and
 * found, the question also offers "Probar por DLNA en <TV>": one tap ends the Chromecast session
 * and the player screen casts the title over DLNA from where the Chromecast was
 * ([CastToDlnaHandoff]). With no such TV, the question carries what [com.arkiv.player.cast.CastGaveUp]
 * offers once every route is spent ("Descargar y preparar para la TV", with its explanation).
 */
@Composable
fun CastTroubleDialog(graph: AppGraph) {
    // The session manager is built lazily once the Cast context is ready, possibly after this is
    // first composed: looked up again until it exists.
    var session by remember { mutableStateOf<CastSessionManager?>(graph.castSession) }
    LaunchedEffect(session == null) {
        while (session == null) {
            delay(3_000)
            session = graph.castSession
        }
    }
    val s = session ?: return
    val trouble by s.trouble.collectAsStateWithLifecycle()
    val t = trouble ?: return
    val context = LocalContext.current
    if (t.retrying) {
        LaunchedEffect(t) {
            android.widget.Toast.makeText(
                context,
                "El Chromecast dejó de reproducir. Reintentando desde donde iba…",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        return
    }
    val dlnaTv by produceState<DlnaDevice?>(null, t) {
        if (!CastDlnaOffer.worthLooking(t.failures, s.screenShows(t.episodeId))) return@produceState
        val (ip, name) = s.receiverAddress()
        value = withContext(Dispatchers.IO) {
            runCatching { CastDlnaOffer.match(ip, name, graph.dlna.discover(DLNA_LOOK_MS)) }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = { s.dismissTrouble() },
        title = { Text("Se cortó en el Chromecast") },
        text = {
            Column {
                Text(
                    "\"${t.title}\" dejó de reproducirse en la TV, probablemente porque la red está lenta. " +
                        "Puedes intentarlo otra vez desde donde iba o seguir viéndolo en el celular.",
                )
                val tv = dlnaTv
                if (tv != null) {
                    TextButton(onClick = { handOffToDlna(graph, s, tv) }) { Text(CastDlnaOffer.label(tv)) }
                } else {
                    val last = t.lastResort
                    val exhausted = t.exhausted
                    if (last != null && exhausted != null) {
                        Text(last.explanation, modifier = androidx.compose.ui.Modifier.padding(top = 12.dp))
                        TextButton(onClick = { s.dismissTrouble(); last.start(exhausted) }) { Text(last.label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { s.retryAfterTrouble() }) { Text("Reintentar") } },
        dismissButton = { TextButton(onClick = { s.watchOnPhone() }) { Text("Ver en el celular") } },
    )
}

/** How long the trouble dialog listens for the TV over DLNA. */
private const val DLNA_LOOK_MS = 2_500L

/**
 * "Probar por DLNA en <TV>": the Chromecast session ends (as "Ver en el celular" does) and, once it
 * is gone and the phone's player is back, the player screen is handed [tv] and where the
 * Chromecast was ([CastToDlnaHandoff]), which it casts the title to like a TV picked in its own
 * DLNA list. Main thread.
 */
private fun handOffToDlna(graph: AppGraph, s: CastSessionManager, tv: DlnaDevice) {
    val at = s.endForDlna()
    graph.applicationScope.launch {
        // The session's end resumes the phone's player; the DLNA send then holds it again.
        var waited = 0L
        while (s.casting.value && waited < HANDOFF_WAIT_MS) {
            delay(250)
            waited += 250
        }
        delay(HANDOFF_SETTLE_MS)
        CastToDlnaHandoff.offer(tv, at)
    }
}

private const val HANDOFF_WAIT_MS = 8_000L
private const val HANDOFF_SETTLE_MS = 1_000L
