package com.arkiv.player.data.db

/**
 * v35 -> v36: the Nuvio repos the person opened, for companion sync (see [NuvioRepoEntity]), and the
 * clock of each plugin's last password save (`plugin_installs.secretsAt`). [STATEMENTS] is idempotent;
 * [ADD_SECRETS_AT] runs once, only on a v35 table (a fresh v36 database already has the column).
 */
object NuvioReposMigration {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS nuvio_repos (" +
            "address TEXT NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(address))",
    )

    const val ADD_SECRETS_AT = "ALTER TABLE plugin_installs ADD COLUMN secretsAt INTEGER NOT NULL DEFAULT 0"
}
