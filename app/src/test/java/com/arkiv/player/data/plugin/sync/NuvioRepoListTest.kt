package com.arkiv.player.data.plugin.sync

import com.arkiv.player.data.db.NuvioRepoDao
import com.arkiv.player.data.db.NuvioRepoEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeNuvioRepoDao : NuvioRepoDao {
    val rows = LinkedHashMap<String, NuvioRepoEntity>()
    private val flow = MutableStateFlow<List<NuvioRepoEntity>>(emptyList())
    override fun flowAll() = flow
    override suspend fun get(address: String) = rows[address]
    override suspend fun save(row: NuvioRepoEntity) { rows[row.address] = row; flow.value = rows.values.toList() }
    override suspend fun getSince(cursor: Long) = rows.values.filter { it.updatedAt > cursor }.sortedBy { it.updatedAt }
}

class NuvioRepoListTest {
    private val dao = FakeNuvioRepoDao()
    private var now = 1_000L
    private val list = NuvioRepoList(dao) { now }

    @Test fun `a repo the person opened is listed once, sorted, with a clock`() = runTest {
        list.add("zeta/nuvio")
        list.add("D3PR3D4DOR/pelisplus-latino-nuvio")
        now = 2_000L
        list.add("zeta/nuvio") // already there: nothing written
        assertEquals(listOf("D3PR3D4DOR/pelisplus-latino-nuvio", "zeta/nuvio"), list.addresses.first())
        assertEquals(1_000L, dao.rows.getValue("zeta/nuvio").updatedAt)
    }

    @Test fun `removing leaves a tombstone, and opening it again brings it back with a newer clock`() = runTest {
        list.add("a/b")
        now = 2_000L
        list.remove("a/b")
        assertTrue(dao.rows.getValue("a/b").deleted)
        assertTrue(list.addresses.first().isEmpty())
        now = 1_500L // a clock that went backwards still moves the row forward
        list.add("a/b")
        assertFalse(dao.rows.getValue("a/b").deleted)
        assertTrue(dao.rows.getValue("a/b").updatedAt > 2_000L)
    }

    @Test fun `an address that is not a canonical repo is never written`() = runTest {
        list.add("https://evil.example.com/x")
        list.add("")
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `backfill lists the repos of installed Nuvio plugins but never resurrects a removed one`() = runTest {
        list.add("a/b")
        list.remove("a/b")
        list.backfill(listOf("a/b", "c/d"))
        assertTrue(dao.rows.getValue("a/b").deleted)
        assertEquals(listOf("c/d"), list.addresses.first())
    }
}
