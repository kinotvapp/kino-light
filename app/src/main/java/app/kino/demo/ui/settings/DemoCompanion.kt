package app.kino.demo.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The app's "good state" green (connected, synced); red stays for destructive actions only. */
val ConnectedGreen = Color(0xFF4CAF50)

/** Where a paired device's sync stands. */
enum class SyncState { SYNCED, PENDING, NEVER }

/** A device paired with this one (a TV from the phone, a phone from the TV), held in memory only. */
class PairedDevice(val id: String, val name: String, val address: String, selected: Boolean, state: SyncState, minutesAgo: Int = 0) {
    var selected by mutableStateOf(selected)
    var state by mutableStateOf(state)
    var minutesAgo by mutableStateOf(minutesAgo)

    /** The line under the device's name in "Dispositivos emparejados". */
    fun statusLine(): String = when (state) {
        SyncState.SYNCED -> if (minutesAgo == 0) "Sincronizado hace un momento" else "Sincronizado hace $minutesAgo min"
        SyncState.PENDING -> "Hay cambios sin sincronizar con $name"
        SyncState.NEVER -> "Aún no se ha sincronizado"
    }
}

/** Conectar's state on both sides: paired devices, the connected one, and a running "Sincronizar ahora". */
object DemoCompanion {
    /** What the phone sees: the TVs it is paired with. */
    val pairedTvs = mutableStateListOf(
        PairedDevice("tv-sala", "TV de la sala", "192.168.1.40:8765", selected = true, state = SyncState.SYNCED, minutesAgo = 5),
        PairedDevice("tv-cuarto", "TV del cuarto", "192.168.1.52:8765", selected = true, state = SyncState.PENDING),
        PairedDevice("tv-estudio", "TV del estudio", "192.168.1.61:8765", selected = false, state = SyncState.NEVER),
    )

    /** What the TV sees: the phones paired with it. */
    val pairedPhones = mutableStateListOf(
        PairedDevice("cel-1", "Celular principal", "192.168.1.23", selected = true, state = SyncState.SYNCED, minutesAgo = 5),
        PairedDevice("tablet", "Tablet", "192.168.1.31", selected = false, state = SyncState.NEVER),
    )

    /** The TV this phone is connected to and controls (null: none). */
    var connectedTvId by mutableStateOf<String?>("tv-sala")

    var syncing by mutableStateOf(false)

    /** Every TV on the network, paired or not. */
    val foundTvs = listOf(
        "tv-sala" to ("TV de la sala" to "192.168.1.40:8765"),
        "tv-cuarto" to ("TV del cuarto" to "192.168.1.52:8765"),
    )

    fun connectedTvName(): String? = pairedTvs.firstOrNull { it.id == connectedTvId }?.name

    fun forget(list: MutableList<PairedDevice>, device: PairedDevice) {
        list.remove(device)
        if (device.id == connectedTvId) connectedTvId = null
    }

    /** Marks every selected device as just synced; called when "Sincronizando…" ends. */
    fun finishSync(list: List<PairedDevice>) {
        list.filter { it.selected }.forEach { it.state = SyncState.SYNCED; it.minutesAgo = 0 }
        syncing = false
    }
}
