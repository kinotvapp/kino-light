package com.arkiv.player.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arkiv.player.data.SettingsStore
import com.arkiv.player.ui.theme.ArkivRed
import com.arkiv.player.ui.theme.ArkivTextSecondary

/**
 * The 18+ lock on the phone. Same section as the TV's (`TvAdultsSection`), with the same logic
 * --[AdultsLock] and [SettingsStore] are the same-- and a different visual layer: there it's
 * tv-material3 with D-pad focus, here it's Material3 with an on-screen keyboard.
 *
 * The consumer existed and the door was missing: `LiveViewModel` already asked for categories
 * with `includeAdults` per this preference, and its own comment said unlocking it "from Ajustes"
 * had to show on re-entry -- but on the phone there was nowhere to do it. It unlocked on the TV
 * or it didn't unlock at all.
 *
 * It's per DEVICE, not per account: the phone and the TV unlock separately and each can have its
 * own code.
 */
@Composable
internal fun AdultsSection(store: SettingsStore) {
    var unlocked by remember { mutableStateOf(store.adultsUnlocked.value) }
    var saved by remember { mutableStateOf(store.adultsCode.value) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    if (AdultsLock.shouldShowSection(unlocked)) {
        Text(
            "Adultos",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        // On the phone the 18+ only shows up in En vivo: the channel drawer, which on the TV also
        // shows it, doesn't exist here.
        Text(
            "La categoría 18+ está visible en En vivo de este aparato.",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Button(onClick = {
            store.setAdultsUnlocked(false)
            unlocked = false
            code = ""
        }) {
            Text("Ocultar 18+ en este aparato")
        }
        ChangeAdultsCode(store) { saved = it }
        return
    }
    if (!AdultsLock.shouldShowField(unlocked)) return

    fun attempt() {
        // The reset is checked BEFORE unlocking: it's the way out for whoever forgot the code
        // they set, and that's why there's no other hint that it exists. The sign that it worked
        // is that the default-code notice shows up again on its own.
        if (AdultsLock.requestsReset(code)) {
            store.setAdultsCode(null)
            saved = null
            code = ""
            error = false
            return
        }
        if (AdultsLock.unlocks(code, AdultsLock.effectiveCode(saved))) {
            store.setAdultsUnlocked(true)
            unlocked = true
            error = false
        } else {
            error = true
        }
    }

    // Not labeled as "adults": the row just says "Código" and nothing else. Whoever doesn't know
    // the code has no reason to find out there's a door here.
    Text(
        "Código",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    // While the code is the one anyone knows, it's stated. It's what makes the section usable by
    // whoever installs the APK without having built it themselves; it disappears with a code of one's own.
    if (AdultsLock.isDefault(saved)) {
        Text(
            "Por defecto: ${AdultsLock.DEFAULT_CODE}",
            style = MaterialTheme.typography.bodySmall,
            color = ArkivTextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; error = false },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { attempt(); keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error) {
        Text("Código incorrecto", style = MaterialTheme.typography.bodySmall, color = ArkivRed)
    }
    Button(onClick = ::attempt, modifier = Modifier.padding(top = 8.dp)) {
        Text("Aplicar código")
    }
}

/**
 * Changing the code, only from inside the already-unlocked section. Doesn't ask for the current
 * code: getting here already required typing it.
 *
 * [onSaved] tells [AdultsSection] which one ended up saved, so its "default" notice reflects the
 * change without re-reading the store.
 */
@Composable
private fun ChangeAdultsCode(store: SettingsStore, onSaved: (String) -> Unit) {
    var newCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun save() {
        val trimmed = newCode.trim()
        when {
            !AdultsLock.isValidFormat(trimmed) -> {
                message = "Usa 4 dígitos"
                done = false
            }
            // Without explaining why: saying "that one's the reset code" would announce the way
            // out the reset exists to NOT announce.
            AdultsLock.isReserved(trimmed) -> {
                message = "Ese código no está disponible, elige otro"
                done = false
            }
            else -> {
                store.setAdultsCode(trimmed)
                onSaved(trimmed)
                newCode = ""
                message = null
                done = true
            }
        }
    }

    Text(
        "Cambiar código",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
    OutlinedTextField(
        value = newCode,
        onValueChange = { newCode = it; message = null; done = false },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save(); keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth(),
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ArkivRed) }
    if (done) {
        Text("Código actualizado", style = MaterialTheme.typography.bodySmall, color = ArkivTextSecondary)
    }
    Button(onClick = ::save, modifier = Modifier.padding(top = 8.dp)) {
        Text("Guardar código")
    }
}
