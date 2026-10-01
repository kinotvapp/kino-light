package com.arkiv.player.data.plugin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a sealed entry (apiVersion 5's `sealedEntry`, [SealedCode]) has verified at install time and
 * carries from the consent sheet to the commit: the downloaded `.kjs` (never its plaintext) and its
 * author's key. [firstKey]: no key was pinned for this plugin yet (a first install, or an update that
 * seals a plugin that was plain), so the consent sheet says it is the first time.
 */
class SealedEntryPreview(val blob: ByteArray, val authorKey: ByteArray, val firstKey: Boolean) {
    val fingerprint: String get() = SealedCode.fingerprint(authorKey)
}

/**
 * The script a runtime of [plugin] loads, read on [dispatcher] (never the caller's thread, never Main).
 * A plain entry is read exactly as it always was ([PluginStore.readVerifiedScript]). A sealed one is
 * read as stored (sha256-checked), its author key compared with the pinned one, opened in memory
 * ([SealedCode.open]) and handed back as a String: nothing decrypted is ever written to disk or
 * cached. [onSealedOpened] gets the open's duration and the script's size, for diagnostics only.
 *
 * Throws [PluginDamagedException] for a sealed file that no longer opens (the pool marks the plugin
 * damaged, as for a plain file whose hash changed) and [PluginScriptException] when this Kino cannot
 * open sealed code at all or the plugin is recorded at an explicit `@ref`.
 */
suspend fun loadEntryScript(
    store: PluginStore,
    plugin: InstalledPlugin,
    agreement: X25519Agreement,
    recipientPublic: ByteArray = SealedSecrets.KINO_PUBLIC_KEY_V1,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onSealedOpened: (elapsedMs: Long, scriptBytes: Int) -> Unit = { _, _ -> },
): String = withContext(dispatcher) {
    val id = plugin.manifest.id
    if (!plugin.manifest.entrySealed) return@withContext store.readVerifiedScript(id)
    val blob = store.readVerifiedEntry(id)
    val address = PluginAddress.parse(plugin.record.address)
    if (address == null || !SealedSecrets.opensAt(address)) throw PluginScriptException(PluginInstaller.NON_HEAD_CODE_MESSAGE)
    val pinned = plugin.record.authorKey?.let(::hexBytes) ?: throw PluginDamagedException()
    val started = System.nanoTime()
    val script = try {
        SealedCode.open(blob, SealedSecrets.bindingOf(address), id, agreement, recipientPublic, pinned)
    } catch (e: SealException) {
        if (e.message == SealedSecrets.NO_NATIVE_MESSAGE) throw PluginScriptException(PluginInstaller.NO_CODE_NATIVE_MESSAGE)
        throw PluginDamagedException()
    }
    onSealedOpened((System.nanoTime() - started) / 1_000_000, script.length)
    script
}

internal fun hexBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
