package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `values persist across instances`() {
        val f = File(tmp.root, "d/storage.json")
        PluginStorage(f).set("token", "abc")
        assertEquals("abc", PluginStorage(f).get("token"))
        PluginStorage(f).remove("token")
        assertNull(PluginStorage(f).get("token"))
    }

    @Test fun `total size is capped and a refused write changes nothing`() {
        val s = PluginStorage(File(tmp.root, "s.json"), maxBytes = 100)
        s.set("a", "x".repeat(50))
        assertThrows(IllegalStateException::class.java) { s.set("b", "y".repeat(60)) }
        assertNull(s.get("b"))
        assertEquals("x".repeat(50), s.get("a"))
    }

    @Test fun `a corrupt file reads as empty`() {
        val f = File(tmp.root, "s.json").apply { writeText("{not json") }
        assertNull(PluginStorage(f).get("a"))
    }
}
