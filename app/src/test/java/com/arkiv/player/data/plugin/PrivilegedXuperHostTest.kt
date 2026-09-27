package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedXuperHostTest {
    /** Every [PluginHost] member that has no default body, given trivial answers: this base exists
     *  only so a fake needs to add the 5 xuper* members, not re-implement fetch/select/storage/log. */
    private abstract class DefaultPluginHostTestBase : PluginHost {
        override suspend fun fetch(requestJson: String): String = """{"error":{"code":"unavailable","message":""}}"""
        override fun select(html: String, css: String): String = "[]"
        override fun storageGet(key: String): String? = null
        override fun storageSet(key: String, value: String, ttlMs: Long?) = Unit
        override fun storageRemove(key: String) = Unit
        override fun log(level: String, message: String) = Unit
    }

    private class FakeXuperHost : DefaultPluginHostTestBase(), PrivilegedXuperHost {
        override suspend fun xuperSearch(argsJson: String) = """{"ok":true,"data":[]}"""
        override suspend fun xuperHome() = """{"ok":true,"data":[]}"""
        override suspend fun xuperBrowse(ref: String, cursor: String?) = """{"ok":true,"data":{"items":[]}}"""
        override suspend fun xuperEpisodes(ref: String) = """{"ok":true,"data":[]}"""
        override suspend fun xuperResolve(ref: String) = """{"ok":false,"code":"not_found","message":""}"""
    }

    @Test fun `a PrivilegedXuperHost is still an ordinary PluginHost`() {
        val host: PluginHost = FakeXuperHost()
        assertEquals(true, host is PrivilegedXuperHost)
    }
}
