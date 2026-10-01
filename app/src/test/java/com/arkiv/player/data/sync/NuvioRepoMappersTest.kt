package com.arkiv.player.data.sync

import com.arkiv.player.data.db.NuvioRepoEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NuvioRepoMappersTest {
    @Test fun `a row round-trips`() {
        val e = NuvioRepoEntity("D3PR3D4DOR/pelisplus-latino-nuvio", 42L, deleted = true)
        assertEquals(e, jsonToNuvioRepo(nuvioRepoToJson(e)))
    }

    @Test fun `a peer's row that is not a canonical public repo is refused`() {
        fun j(address: String) = JSONObject().put("address", address).put("updatedAt", 1L).put("deleted", false)
        assertNull(jsonToNuvioRepo(j("https://evil.example.com/a")))
        assertNull(jsonToNuvioRepo(j("https://github.com/a/b"))) // parses, but not canonical
        assertNull(jsonToNuvioRepo(j("a/" + "b".repeat(400))))
        assertNull(jsonToNuvioRepo(j("xuper-plugin/kino-plugin-xuper"))) // never a Nuvio repo
        assertNull(jsonToNuvioRepo(JSONObject().put("updatedAt", 1L)))
    }
}
