package com.arkiv.player.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCursorStoreTest {

    private fun store() = SyncCursorStore(FakeContext())

    @Test
    fun `default is 0 for an unset peer and table`() {
        assertEquals(0L, store().pulled("peerA", "items"))
    }

    @Test
    fun `setPulled then pulled returns the value`() {
        val s = store()

        s.setPulled("peerA", "items", 42L)

        assertEquals(42L, s.pulled("peerA", "items"))
    }

    @Test
    fun `cursors are independent per peer and per table`() {
        val s = store()

        s.setPulled("peerA", "items", 10L)

        assertEquals("a different peer must not see peerA's cursor", 0L, s.pulled("peerB", "items"))
        assertEquals("a different table for the same peer must not see it either", 0L, s.pulled("peerA", "playback"))
        assertEquals("peerA/items itself must still read back", 10L, s.pulled("peerA", "items"))
    }

    @Test
    fun `cursors survive across store instances backed by the same prefs file`() {
        val ctx = FakeContext()
        SyncCursorStore(ctx).setPulled("peerA", "items", 99L)

        assertEquals(99L, SyncCursorStore(ctx).pulled("peerA", "items"))
    }
}

/**
 * A minimal in-memory Context/SharedPreferences fake. This repo has no Robolectric (see the note
 * in `PlayerSourceTagIpcTest`), so a real read-after-write on `SharedPreferences` needs a stateful
 * fake instead. Follows the same `Proxy`-backed idiom already used for `SharedPreferences` in
 * `SearchViewModelSourcesTest.TestContext`, but keeps a real backing map so writes are observable.
 */
private class FakeContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, MutableMap<String, Long>>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val backing = stores.getOrPut(name ?: "") { mutableMapOf() }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getLong" -> backing[args[0] as String] ?: (args[1] as Long)
                "contains" -> backing.containsKey(args[0] as String)
                "edit" -> newEditor(backing)
                else -> null
            }
        } as SharedPreferences
    }

    private fun newEditor(backing: MutableMap<String, Long>): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putLong" -> { backing[args[0] as String] = args[1] as Long; proxy }
                "remove" -> { backing.remove(args[0] as String); proxy }
                "clear" -> { backing.clear(); proxy }
                "commit" -> true
                "apply" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor
}
