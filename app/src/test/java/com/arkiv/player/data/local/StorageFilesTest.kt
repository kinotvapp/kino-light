package com.arkiv.player.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, bytes: Int): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun `the size of a folder counts every file in it, nested ones included`() {
        file("a.bin", 100)
        file("sub/b.bin", 250)
        file("sub/deeper/c.bin", 50)
        assertEquals(400L, StorageFiles.sizeOf(tmp.root))
    }

    @Test
    fun `the size of a single file is its length and a missing path is zero`() {
        val f = file("only.bin", 123)
        assertEquals(123L, StorageFiles.sizeOf(f))
        assertEquals(0L, StorageFiles.sizeOf(File(tmp.root, "nope")))
    }

    @Test
    fun `clearing a folder empties it but keeps the folder itself`() {
        val dir = tmp.newFolder("cache")
        File(dir, "x.bin").writeBytes(ByteArray(300))
        File(dir, "nested").mkdirs()
        File(dir, "nested/y.bin").writeBytes(ByteArray(200))

        val freed = StorageFiles.clearContents(dir)

        assertEquals(500L, freed)
        assertTrue("whoever owns the folder expects it to still exist", dir.isDirectory)
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `clearing leaves siblings alone and tolerates a missing folder`() {
        val target = tmp.newFolder("cache")
        val sibling = file("keep/precious.bin", 999)
        File(target, "z.bin").writeBytes(ByteArray(10))

        StorageFiles.clearContents(target)

        assertTrue("a sibling folder must not be touched", sibling.exists())
        assertEquals(0L, StorageFiles.clearContents(File(tmp.root, "does-not-exist")))
    }

    /**
     * The folder names are shared by whoever CREATES them (Coil, the archive proxy, the OTA
     * downloader) and whoever CLEARS them (AppStorage). If one side is renamed, "Limpiar caché"
     * would silently free nothing; this pins the values.
     */
    @Test
    fun `the cache folder names the cleaner uses are the ones on disk`() {
        assertEquals("image_cache", AppStorage.IMAGE_CACHE_DIR)
        assertEquals("archive-cache", AppStorage.ARCHIVE_CACHE_DIR)
        assertEquals("update.apk", AppStorage.UPDATE_APK)
        assertFalse(AppStorage.IMAGE_CACHE_DIR == AppStorage.ARCHIVE_CACHE_DIR)
    }
}
