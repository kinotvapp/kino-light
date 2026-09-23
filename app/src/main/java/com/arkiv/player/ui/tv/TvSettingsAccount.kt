package com.arkiv.player.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.arkiv.player.data.magis.MagisAccount
import com.arkiv.player.data.magis.MagisAccountState
import kotlinx.coroutines.launch

/**
 * TV's "Settings → Account" (Task 8, sub-project 2B): same as the phone's
 * (`ui/settings/AccountSection.kt`), just the Magis link. There's no Kino login here anymore
 * -that lived in `TvAnonimoSection`/`TvConectadoSection` (with `AccountManager`), removed in this
 * same task since they had no other caller: `TvPantallaDeEntrada.kt`, which built its own login
 * form (`PanelDeLogin`), was removed entirely in Task 9 (sub-project 2B)-.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsAccount(account: MagisAccount, onLinkMagis: () -> Unit, accountUnavailable: Boolean = false) {
    val state by account.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { account.refresh() }

    Text("Cuenta", style = MaterialTheme.typography.titleMedium, color = Color.White)
    when (val s = state) {
        is MagisAccountState.Linked -> TvLinkedSection(account, s)
        MagisAccountState.None -> if (accountUnavailable) {
            Text("Cuenta Xuper no disponible en tu región", color = Color.White)
        } else {
            TvActionOption(label = "Vincular Xuper", onClick = onLinkMagis)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLinkedSection(account: MagisAccount, state: MagisAccountState.Linked) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Text("Xuper vinculado como ${state.email}", color = Color.White)
    TvActionOption(
        label = if (busy) "Desvinculando…" else "Desvincular Xuper",
        onClick = {
            if (!busy) {
                scope.launch {
                    busy = true
                    // try/finally, not try/catch: unlink() doesn't throw -MagisSession.logout()
                    // never throws, it returns a MagisResult-, so a catch(MagisException) here
                    // would be unreachable. Same pattern as the phone's
                    // (AccountSection.VinculadaSection).
                    try {
                        account.unlink()
                    } finally {
                        busy = false
                    }
                }
            }
        },
    )
}
