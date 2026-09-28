package com.arkiv.player.data.db

/**
 * v32 -> v33: `provider` on the three live tables (the generic En vivo module): existing rows
 * become Xuper's, primary keys become `(provider, code)` / `(provider, code, categoria)`, the
 * cache's `categoria` becomes TEXT (plugin category ids are strings) and gains a nullable `ref`.
 * SQLite can't change a primary key in place, so each table is rebuilt and copied. The copy
 * carries `updatedAt` as-is (no triggers exist on the new tables yet; `SEAL_UPDATED_AT`
 * recreates them on open). Its own object so `LiveProviderMigrationTest` runs these exact
 * statements against real SQLite.
 */
internal object LiveProviderMigration {
    val STATEMENTS: List<String> = listOf(
        "CREATE TABLE live_favorites_new (code TEXT NOT NULL, nombre TEXT NOT NULL, numero INTEGER NOT NULL, logo TEXT, " +
            "updatedAt INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, provider TEXT NOT NULL DEFAULT 'xuper', " +
            "PRIMARY KEY(provider, code))",
        "INSERT INTO live_favorites_new (code, nombre, numero, logo, updatedAt, deleted, provider) " +
            "SELECT code, nombre, numero, logo, updatedAt, deleted, 'xuper' FROM live_favorites",
        "DROP TABLE live_favorites",
        "ALTER TABLE live_favorites_new RENAME TO live_favorites",
        "CREATE TABLE live_recents_new (code TEXT NOT NULL, nombre TEXT NOT NULL, vistoAt INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL DEFAULT 0, provider TEXT NOT NULL DEFAULT 'xuper', PRIMARY KEY(provider, code))",
        "INSERT INTO live_recents_new (code, nombre, vistoAt, updatedAt, provider) " +
            "SELECT code, nombre, vistoAt, updatedAt, 'xuper' FROM live_recents",
        "DROP TABLE live_recents",
        "ALTER TABLE live_recents_new RENAME TO live_recents",
        "CREATE TABLE live_channels_cache_new (code TEXT NOT NULL, categoria TEXT NOT NULL, nombre TEXT NOT NULL, " +
            "numero INTEGER NOT NULL, logo TEXT, guardadoAt INTEGER NOT NULL, provider TEXT NOT NULL DEFAULT 'xuper', ref TEXT, " +
            "PRIMARY KEY(provider, code, categoria))",
        "INSERT INTO live_channels_cache_new (code, categoria, nombre, numero, logo, guardadoAt, provider, ref) " +
            "SELECT code, CAST(categoria AS TEXT), nombre, numero, logo, guardadoAt, 'xuper', NULL FROM live_channels_cache",
        "DROP TABLE live_channels_cache",
        "ALTER TABLE live_channels_cache_new RENAME TO live_channels_cache",
    )
}
