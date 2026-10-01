package com.arkiv.player.ui.player

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkiv.player.AppGraph
import com.arkiv.player.cast.CastSessionManager
import kotlinx.coroutines.delay

/**
 * What the phone says when the Chromecast stops playing by itself (see `CastIdleWatch`).
 *
 * Mounted once at the app root, not inside the player: a cast keeps going with the player closed,
 * and the player's composable is already at the size the ART verifier tolerates.
 *
 * The first drop is retried on its own and only announced; a second one asks: "Reintentar" or
 * "Ver en el celular". Never a loop -- the session manager decides that, this only shows it.
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
    AlertDialog(
        onDismissRequest = { s.dismissTrouble() },
        title = { Text("Se cortó en el Chromecast") },
        text = {
            Text(
                "\"${t.title}\" dejó de reproducirse en la TV, probablemente porque la red está lenta. " +
                    "Puedes intentarlo otra vez desde donde iba o seguir viéndolo en el celular.",
            )
        },
        confirmButton = { TextButton(onClick = { s.retryAfterTrouble() }) { Text("Reintentar") } },
        dismissButton = { TextButton(onClick = { s.watchOnPhone() }) { Text("Ver en el celular") } },
    )
}
