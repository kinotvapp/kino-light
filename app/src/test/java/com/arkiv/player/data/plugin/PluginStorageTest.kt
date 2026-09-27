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

    @Test fun `an entry with a ttl expires, its neighbor without one does not`() {
        var now = 1_000_000L
        val s = PluginStorage(File(tmp.root, "s.json"), clock = { now })
        s.set("temp", "v", ttlMs = 1_000)
        s.set("perm", "v")
        assertEquals("v", s.get("temp"))
        now += 999
        assertEquals("v", s.get("temp"))
        now += 1
        assertNull(s.get("temp"))
        assertEquals("v", s.get("perm"))
    }

    @Test fun `keys omits an expired key`() {
        var now = 0L
        val s = PluginStorage(File(tmp.root, "s.json"), clock = { now })
        s.set("perm", "1")
        s.set("temp", "2", ttlMs = 500)
        assertEquals(listOf("perm", "temp"), s.keys())
        now = 501
        assertEquals(listOf("perm"), s.keys())
    }

    @Test fun `an expired entry does not count against the cap once purged on a later write`() {
        var now = 0L
        val s = PluginStorage(File(tmp.root, "s.json"), maxBytes = 100, clock = { now })
        s.set("temp", "x".repeat(60), ttlMs = 10)
        now = 11
        // Without purging the expired "temp" first, 60 stale + 60 fresh bytes would refuse this.
        s.set("fresh", "y".repeat(60))
        assertEquals("y".repeat(60), s.get("fresh"))
        assertNull(s.get("temp"))
    }

    @Test fun `a read also purges, so a later write sees the room it freed`() {
        var now = 0L
        val s = PluginStorage(File(tmp.root, "s.json"), maxBytes = 100, clock = { now })
        s.set("temp", "x".repeat(60), ttlMs = 10)
        now = 11
        s.get("temp") // a plain read, not a write
        s.set("fresh", "y".repeat(60))
        assertEquals("y".repeat(60), s.get("fresh"))
    }

    @Test fun `ttlMs must be a positive integer of at most 30 days`() {
        val s = PluginStorage(File(tmp.root, "s.json"))
        assertThrows(IllegalArgumentException::class.java) { s.set("k", "v", ttlMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { s.set("k", "v", ttlMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { s.set("k", "v", ttlMs = PluginStorage.MAX_TTL_MS + 1) }
        assertNull(s.get("k"))
        s.set("k", "v", ttlMs = PluginStorage.MAX_TTL_MS)
        assertEquals("v", s.get("k"))
    }

    @Test fun `data written before ttl existed keeps loading, permanent, under the new code`() {
        val f = File(tmp.root, "s.json").apply { writeText("""{"legacy":"still here"}""") }
        var now = 10_000_000_000L
        val s = PluginStorage(f, clock = { now })
        assertEquals("still here", s.get("legacy"))
        now += PluginStorage.MAX_TTL_MS * 100 // Far beyond any ttl: still there, because it has none.
        assertEquals("still here", s.get("legacy"))
        assertEquals(listOf("legacy"), s.keys())
    }

    @Test fun `a permanent entry written after this feature stays a bare string on disk`() {
        val f = File(tmp.root, "s.json")
        PluginStorage(f).set("k", "v")
        assertEquals("""{"k":"v"}""", f.readText())
    }
}
