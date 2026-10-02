package com.arkiv.player.data.plugin.sync

import com.arkiv.player.companion.PeerKeyStore
import com.arkiv.player.data.plugin.SecretStore
import com.arkiv.player.data.subtitles.SubtitleKeys
import com.arkiv.player.data.subtitles.SubtitleProviderId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleKeySyncTest {
    private class Keys : PeerKeyStore {
        val map = HashMap<String, ByteArray>()
        override fun get(deviceId: String) = map[deviceId]
        override fun put(deviceId: String, key: ByteArray) { map[deviceId] = key }
        override fun remove(deviceId: String) { map.remove(deviceId) }
    }

    private class Mem : SecretStore {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    private val key = ByteArray(32) { (it * 5).toByte() }
    private val shared = "SHARED-KINO-KEY-VALUE"

    private fun device(peer: String, peerKey: ByteArray? = key): Pair<SubtitleKeys, SubtitleKeySync> {
        val keys = SubtitleKeys(Mem(), { shared }, null)
        val store = Keys().apply { if (peerKey != null) put(peer, peerKey) }
        return keys to SubtitleKeySync(keys, store) {}
    }

    @Test fun `own values travel sealed and arrive on the other device`() = runTest {
        val (phoneKeys, phone) = device("tv")
        val (tvKeys, tv) = device("phone")
        phoneKeys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "my-os-key-123")
        phoneKeys.setAccount("ana", "pa55word")
        val rows = phone.changedSince(0, "tv")
        assertEquals(1, rows.size)
        val wire = rows.single().toString()
        listOf("my-os-key-123", "pa55word", "ana", shared).forEach { assertFalse("$it travelled in the clear", it in wire) }
        assertTrue(tv.apply(rows.single(), "phone"))
        assertEquals("my-os-key-123", tvKeys.userKey(SubtitleProviderId.OPENSUBTITLES))
        assertEquals("ana" to "pa55word", tvKeys.account())
        assertEquals(phoneKeys.stamp(), tvKeys.stamp())
    }

    @Test fun `the shared key never travels`() = runTest {
        val (phoneKeys, phone) = device("tv")
        phoneKeys.setUserKey(SubtitleProviderId.SUBDL, "own-subdl")
        val (tvKeys, tv) = device("phone")
        tv.apply(phone.changedSince(0, "tv").single(), "phone")
        assertEquals("", tvKeys.userKey(SubtitleProviderId.OPENSUBTITLES))
        assertEquals(shared, tvKeys.effective(SubtitleProviderId.OPENSUBTITLES)?.auth?.apiKey)
    }

    @Test fun `clearing a key syncs too, and the last saved side wins`() = runTest {
        val (phoneKeys, phone) = device("tv")
        val (tvKeys, tv) = device("phone")
        phoneKeys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "first")
        tv.apply(phone.changedSince(0, "tv").single(), "phone")
        assertEquals("first", tvKeys.userKey(SubtitleProviderId.OPENSUBTITLES))
        Thread.sleep(3)
        tvKeys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "")
        // The TV cleared later: its row is newer, so the phone takes it.
        val tvRow = SubtitleKeySync(tvKeys, Keys().apply { put("phone", key) }) {}.changedSince(0, "phone").single()
        assertTrue(phone.apply(tvRow, "tv"))
        assertEquals("", phoneKeys.userKey(SubtitleProviderId.OPENSUBTITLES))
        // An older row never overrides a newer local value.
        phoneKeys.setUserKey(SubtitleProviderId.SUBDL, "newer-local")
        assertTrue(phone.apply(tvRow, "tv"))
        assertEquals("newer-local", phoneKeys.userKey(SubtitleProviderId.SUBDL))
    }

    @Test fun `a value sealed with another key is not applied and asked again`() = runTest {
        val (phoneKeys, phone) = device("tv", ByteArray(32) { 9 })
        val (tvKeys, tv) = device("phone")
        phoneKeys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "x")
        assertFalse(tv.apply(phone.changedSince(0, "tv").single(), "phone"))
        assertEquals("", tvKeys.userKey(SubtitleProviderId.OPENSUBTITLES))
    }

    @Test fun `nothing is sent to a peer without a key, or when nothing was ever saved`() = runTest {
        val (keys, sync) = device("tv", peerKey = null)
        keys.setUserKey(SubtitleProviderId.OPENSUBTITLES, "x")
        assertTrue(sync.changedSince(0, "tv").isEmpty())
        val (_, none) = device("tv")
        assertTrue(none.changedSince(0, "tv").isEmpty())
        val (k2, s2) = device("tv")
        k2.setUserKey(SubtitleProviderId.OPENSUBTITLES, "x")
        assertTrue(s2.changedSince(k2.stamp(), "tv").isEmpty())
    }

    @Test fun `applying a peer's values does not fire a local change`() = runTest {
        val (keys, _) = device("tv")
        var fired = false
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).let { sc -> sc.launch { keys.localChanges.collect { fired = true } } }
        keys.applyRemote(mapOf("opensubtitles" to "z"), 5L)
        assertFalse(fired)
        job.cancel()
    }
}
