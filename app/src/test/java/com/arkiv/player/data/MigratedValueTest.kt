package com.arkiv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `migratedValue(fromSettings, fromOldStore, default)`: `null` in either of the first two means
 * "that key doesn't exist there".
 */
class MigratedValueTest {

    @Test
    fun `the unlocked 18+ lock survives the migration`() {
        assertEquals(true, migratedValue(fromSettings = null, fromOldStore = true, default = false))
    }

    @Test
    fun `what's already in Settings wins, the old value doesn't come back to life`() {
        // The person locked it again AFTER migrating: the old store's true is still there,
        // but it can't come back on the next launch.
        assertEquals(false, migratedValue(fromSettings = false, fromOldStore = true, default = false))
    }

    @Test
    fun `with no old store it stays at the default`() {
        assertEquals(false, migratedValue(fromSettings = null, fromOldStore = null, default = false))
    }

    @Test
    fun `the already-migrated purge doesn't repeat even if the old store can no longer be read`() {
        // The day after Task 9: `SecureDeviceStore` no longer exists (or the Keystore broke
        // before getting there) and `fromOldStore` comes in as `null`, but the purge had already
        // been recorded here -- the old value isn't needed to avoid running it again.
        assertEquals(true, migratedValue(fromSettings = true, fromOldStore = null, default = false))
    }
}
