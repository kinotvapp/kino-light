package com.arkiv.player.data.db

/**
 * The DDL that keeps `updatedAt` current on these six tables.
 *
 * `updatedAt` was the clock two removed cloud-sync paths depended on: the merge used it to decide
 * who won between two devices' copies of a row (`cloudsync.LwwMerge`, `sync.SyncMerge`), and the
 * push picked what to upload with `updatedAt > cursor` -- a row stuck at 0 never cleared that
 * cursor and never got uploaded. Neither exists anymore in this branch. The `updatedAt`/`deleted`
 * columns stay because dropping a column is a schema change; the triggers themselves aren't part
 * of Room's schema at all (see below), so they could be deleted freely -- nothing has needed to
 * yet, so they're still here sealing every local write with a clock nothing reads.
 *
 * It used to live only inside `MIGRATION_6_7`, and that was the problem: Room creates the tables
 * from its generated schema, and the triggers aren't part of that schema. A device **installed
 * fresh** on a later version never runs that migration, so it never had the triggers and
 * everything it created locally was born —and stayed— at `updatedAt = 0`. Measured on 2026-08-10:
 * the Fire TV had zero triggers and 77 of 116 items, 1054 of 1806 episodes and 7 progress rows
 * unsealed; the phone, which had been migrating since v6, had them all. That's why what was
 * watched or saved on the TV never reached the phone.
 *
 * That's why [ddl] gets applied on EVERY database open and not in a migration, so it holds equally
 * for whoever migrates and whoever installs fresh. What makes it safe to run on every open is NOT
 * the `IF NOT EXISTS` on the `CREATE TRIGGER`s -- it's the `DROP TRIGGER IF EXISTS` that precedes
 * each one (the why, with the bug that not having it caused, is in [ddl]'s KDoc).
 */
object SyncTriggers {

    /**
     * Table → its primary key. **The real one**: it's what goes in the trigger's `WHERE`, so if
     * this says a column that isn't the PK, sealing ONE row seals every row sharing that value.
     * Happened to `skip_markers`, which said `itemId` when its PK was already `id`
     * (`"<itemId>|<episodeId>"`, one row per chapter): a new marker gave a fresh clock to every
     * other chapter of the series too. When an entity's PK changes, this map changes with it.
     *
     * These are the six that travel through sync. `live_channels_cache` is left out on purpose:
     * it's rebuildable catalog cache, not user data, and giving it sync triggers would send ~1000
     * rows between devices for nothing.
     */
    private val TABLES = listOf(
        "items" to "identifier",
        "episodes" to "id",
        "playback" to "episodeId",
        "skip_markers" to "id",
        "live_favorites" to "code",
        "live_recents" to "code",
    )

    /** The time, in milliseconds, per SQLite's clock. */
    private const val NOW = "CAST(strftime('%s','now') AS INTEGER)*1000"

    /**
     * The triggers. They seal the LOCAL write but **respect an explicit `updatedAt`**, which is
     * what the merge writes when it adopts a row from the other device: sealing it here would
     * make it look newer than it is and the two ends would bounce it back and forth forever.
     *
     * Every CREATE is preceded by its own `DROP TRIGGER IF EXISTS`. Without that, `CREATE TRIGGER
     * IF NOT EXISTS` replaces nothing: a device that's already opened the database once keeps
     * forever the definition it had created the first time, even if the text here changes in a
     * new app version. That's exactly what happened to `trg_items_upd`: the old version sealed
     * with a bare `NOW` (see below) and it was left recursing on any device that already had it
     * created, until this DROP got added. Cheap: two triggers per table, four tables, and this
     * already runs on every open (see `ArkivDatabase.SEAL_UPDATED_AT`).
     */
    fun ddl(): List<String> = TABLES.flatMap { (table, pk) ->
        listOf(
            // Local INSERT: the writer set no clock (it stayed at 0) -> seal.
            "DROP TRIGGER IF EXISTS trg_${table}_ins",
            "CREATE TRIGGER IF NOT EXISTS trg_${table}_ins AFTER INSERT ON $table WHEN NEW.updatedAt = 0 " +
                "BEGIN UPDATE $table SET updatedAt = $NOW WHERE $pk = NEW.$pk; END",
            // Local UPDATE: the writer didn't move the clock -> seal. The merge does move it, and gets skipped.
            //
            // The internal sealing can't just be `$NOW` bare: `$NOW` has SECOND resolution, so if
            // this row already had `updatedAt` sealed less than a second ago (say the very INSERT
            // above, or any other prior write in the same second), the UPDATE here writes the SAME
            // number. That leaves `NEW.updatedAt = OLD.updatedAt` true again -> the WHEN evaluates
            // true again -> the trigger fires itself -> SQLite cuts it off with "too many levels of
            // trigger recursion" (a real crash: saving a Magis season does `upsertItem` followed by
            // a badge UPDATE on the same row in the same second). `MAX($NOW, OLD.updatedAt + 1)`
            // guarantees the value always CHANGES relative to what the row already had -- if the
            // second-clock didn't advance, it's still one more than `OLD.updatedAt`, so the
            // recursion cuts off on the second pass. Never goes backward (`MAX`, not a plain
            // replace): `updatedAt` is the clock both LWW merges and the push's cursor depend on,
            // and a value that goes backward would make a row stop uploading.
            "DROP TRIGGER IF EXISTS trg_${table}_upd",
            "CREATE TRIGGER IF NOT EXISTS trg_${table}_upd AFTER UPDATE ON $table WHEN NEW.updatedAt = OLD.updatedAt " +
                "BEGIN UPDATE $table SET updatedAt = MAX($NOW, OLD.updatedAt + 1) WHERE $pk = NEW.$pk; END",
        )
    }

    /**
     * Seals rows that had already been left at `updatedAt = 0` from being born with no triggers.
     *
     * Without this, the triggers fix whatever comes from now on but the library the TV already
     * had stays invisible to the cloud forever. Idempotent: only touches the 0s.
     */
    fun sealRowsWithNoClock(): List<String> =
        TABLES.map { (table, _) -> "UPDATE $table SET updatedAt = $NOW WHERE updatedAt = 0" }
}
