package com.arkiv.player.data.db

/** v35 -> v36: the Nuvio repos the person opened, for companion sync (see [NuvioRepoEntity]). Idempotent. */
object NuvioReposMigration {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS nuvio_repos (" +
            "address TEXT NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(address))",
    )
}
