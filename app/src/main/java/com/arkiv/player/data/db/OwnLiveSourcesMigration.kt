package com.arkiv.player.data.db

/** v33 -> v34: the person's own live sources (En vivo -> "Mis canales"). Idempotent. */
object OwnLiveSourcesMigration {
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS own_live_sources (" +
            "id TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, " +
            "groupName TEXT, logo TEXT, epgUrl TEXT, userAgent TEXT, referer TEXT, " +
            "refreshHours INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, " +
            "deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))",
    )
}

/** v36 -> v37: lists pasted or opened from a file ("Mis canales"): their digest and their text in parts. Idempotent. */
object OwnListPartsMigration {
    const val ADD_DIGEST = "ALTER TABLE own_live_sources ADD COLUMN contentDigest TEXT"
    val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS own_live_list_parts (" +
            "sourceId TEXT NOT NULL, part INTEGER NOT NULL, parts INTEGER NOT NULL, digest TEXT NOT NULL, " +
            "data TEXT NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(sourceId, part))",
    )
}
