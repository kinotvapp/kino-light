package com.arkiv.player.data.db

/** v34 -> v35: the installed plugins, mirrored for companion sync (see [PluginInstallEntity]). Idempotent. */
object PluginInstallsMigration {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS plugin_installs (" +
            "id TEXT NOT NULL, address TEXT NOT NULL, name TEXT NOT NULL, nuvioRepo TEXT, nuvioScraperId TEXT, " +
            "version TEXT NOT NULL, sha256 TEXT NOT NULL, enabled INTEGER NOT NULL, " +
            "approvedJson TEXT NOT NULL, settingsJson TEXT NOT NULL, " +
            "updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(id))",
    )
}
