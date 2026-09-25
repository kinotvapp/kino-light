package com.arkiv.player.data.plugin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin passwords in `EncryptedSharedPreferences` (Android Keystore), file `kino_plugin_secrets`,
 * keys `plugin.<id>.<key>`. Opened lazily, on the first read or write — always off the main thread
 * (PluginConfigStore's callers run on IO): creating it touches the Keystore and Tink.
 *
 * A file the Keystore can no longer decrypt (a restored backup on another device, a reset
 * Keystore) is deleted and recreated through the same `EncryptedPrefs.openOrRepair` rule the Magis
 * store uses — only this file, never the shared master key. If even a fresh file can't be opened,
 * the passwords live in memory for this process: the plugin shows "Falta configurar" after a
 * restart instead of the password ever touching plain storage. Nothing here logs a value.
 */
class EncryptedSecretStore(private val context: Context) : SecretStore {
    private val memory = ConcurrentHashMap<String, String>()

    private val prefs: SharedPreferences? by lazy {
        com.arkiv.player.data.magis.EncryptedPrefs.openOrRepair<SharedPreferences?>(
            create = {
                EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            },
            discardUndecryptable = {
                Log.w(TAG, "plugin secrets undecryptable: starting a new file")
                runCatching { context.deleteSharedPreferences(FILE) }
            },
            unencrypted = {
                Log.w(TAG, "plugin secrets unavailable: keeping them in memory for this run")
                null
            },
        )
    }

    override fun get(key: String): String? = prefs?.getString(key, null) ?: memory[key]

    override fun put(key: String, value: String) {
        val p = prefs
        if (p != null) p.edit().putString(key, value).apply() else memory[key] = value
    }

    override fun remove(key: String) {
        prefs?.edit()?.remove(key)?.apply()
        memory.remove(key)
    }

    private companion object {
        const val FILE = "kino_plugin_secrets"
        const val TAG = "KinoPlugin"
    }
}
