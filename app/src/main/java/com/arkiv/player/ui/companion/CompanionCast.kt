package com.arkiv.player.ui.companion

import android.content.Context
import android.widget.Toast
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
import com.arkiv.player.AppGraph
import com.arkiv.player.companion.CompanionPlayAck
import com.arkiv.player.companion.LinkState
import com.arkiv.player.companion.TYPE_PLAY
import com.arkiv.player.companion.TYPE_PLAY_ACK
import com.arkiv.player.companion.newEnvelope
import com.arkiv.player.ui.rememberGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone-side "Ver en el TV / Ver en el celular" decision. When a title is about to play AND the
 * phone is linked to a TV, [onPlay] shows the chooser; otherwise it plays locally straight away
 * (no chooser, no extra tap). "Ver en el TV" hands the TV a [com.arkiv.player.companion.CompanionPlayItem]
 * rebuilt from the phone's library and waits for the ack for feedback.
 */
class CompanionCastState(
    private val graph: AppGraph,
) {
    data class Pending(val episodeId: String, val playLocal: (String) -> Unit, val tvName: String)

    var pending by mutableStateOf<Pending?>(null)
        internal set

    /** Toast messages (send-result feedback); collected by [CompanionCastHost]. */
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Route a play through the chooser when linked to a TV, else play locally. */
    fun onPlay(episodeId: String, playLocal: (String) -> Unit) {
        val companion = graph.companion
        if (companion.linkState.value != LinkState.Connected) {
            playLocal(episodeId)
            return
        }
        val tvName = companion.pairedPeers()
            .firstOrNull { it.deviceId == companion.connectedDeviceId.value }?.name ?: "el TV"
        pending = Pending(episodeId, playLocal, tvName)
    }

    fun dismiss() { pending = null }

    fun chooseLocal() {
        val p = pending ?: return
        pending = null
        p.playLocal(p.episodeId)
    }

    fun chooseTv() {
        val p = pending ?: return
        pending = null
        // Link dropped between opening the chooser and this tap -> treat as not connected and play
        // here, so the user isn't left with nothing (spec's error handling). This runs on the Main
        // thread (dialog onClick), so navigating locally is safe.
        if (graph.companion.linkState.value != LinkState.Connected) {
            p.playLocal(p.episodeId)
            return
        }
        // App scope, not the composition scope: the 8s round-trip must survive an ArkivRoot config
        // change / disposal so a fast play still reaches the TV and the toast still fires.
        graph.applicationScope.launch {
            val item = graph.repository.companionPlayItem(p.episodeId)
            if (item == null) {
                // Nothing the TV could re-resolve (unknown/legacy source): fall back to local
                // playback instead of a dead end. Navigate on Main (app scope is IO).
                toasts.emit("No se pudo enviar al TV; reproduciendo aquí")
                withContext(Dispatchers.Main) { p.playLocal(p.episodeId) }
                return@launch
            }
            val env = newEnvelope(TYPE_PLAY, item.toPayload())
            // Subscribe to the ack BEFORE sending (onSubscription), so a fast ack can't be missed.
            val ack = withTimeoutOrNull(8_000) {
                graph.companion.incoming
                    .onSubscription { graph.companion.send(env) }
                    .first { it.type == TYPE_PLAY_ACK && it.id == env.id }
            }
            val msg = when {
                ack == null -> "El TV no respondió"
                CompanionPlayAck.fromPayload(ack.payload).ok -> "Reproduciendo en ${p.tvName}"
                else -> reasonText(CompanionPlayAck.fromPayload(ack.payload).reason)
            }
            toasts.emit(msg)
        }
    }
}

private fun reasonText(reason: String): String = when (reason) {
    "no_link" -> "El TV necesita vincular su cuenta"
    else -> "No se pudo reproducir en el TV"
}

@Composable
fun rememberCompanionCast(): CompanionCastState {
    val graph = rememberGraph()
    return remember(graph) { CompanionCastState(graph) }
}

/** Renders the chooser dialog and the send-result toasts. Place once near the root's content. */
@Composable
fun CompanionCastHost(state: CompanionCastState) {
    val context: Context = LocalContext.current
    LaunchedEffect(state) {
        state.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    state.pending?.let { p ->
        AlertDialog(
            onDismissRequest = { state.dismiss() },
            title = { Text("¿Dónde quieres verlo?") },
            confirmButton = { TextButton(onClick = { state.chooseTv() }) { Text("Ver en el TV (${p.tvName})") } },
            dismissButton = { TextButton(onClick = { state.chooseLocal() }) { Text("Ver en el celular") } },
        )
    }
}
