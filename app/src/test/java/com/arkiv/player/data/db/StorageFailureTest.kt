package com.arkiv.player.data.db

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteFullException
import java.io.File
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ERRORES-AL6: the startup purge on a device out of space skips instead of reporting a crash. */
class StorageFailureTest {
    @Test
    fun `a database that cannot be opened or written is a storage failure, wrapped too`() {
        assertTrue(StorageFailure.isNoRoom(SQLiteCantOpenDatabaseException()))
        assertTrue(StorageFailure.isNoRoom(SQLiteFullException()))
        assertTrue(StorageFailure.isNoRoom(IllegalStateException("room", SQLiteCantOpenDatabaseException())))
        assertTrue(StorageFailure.isNoRoom(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `any other failure is still ours to report`() {
        assertFalse(StorageFailure.isNoRoom(IllegalStateException("migration")))
        assertFalse(StorageFailure.isNoRoom(IOException("connection reset")))
    }

    @Test
    fun `optional startup writes wait for some free space, an unknown amount still tries`() {
        assertFalse(StorageFailure.roomFor(0L))
        assertFalse(StorageFailure.roomFor(StorageFailure.MIN_FREE_BYTES - 1))
        assertTrue(StorageFailure.roomFor(StorageFailure.MIN_FREE_BYTES))
        assertTrue(StorageFailure.roomFor(-1L))
    }

    @Test
    fun `the startup purge never reports a storage failure and checks the space first`() {
        val src = File("src/main/java/com/arkiv/player/ArkivApp.kt").readText()
            .substringAfter("ERRORES-AL6").substringBefore("graph.startNetworkMonitor()")
        assertTrue(src.indexOf("StorageFailure.roomFor") in 0 until src.indexOf("deleteAll()"))
        assertTrue(src.indexOf("StorageFailure.isNoRoom") in 0 until src.indexOf("report(it, \"startup: purge recents\")"))
    }
}
