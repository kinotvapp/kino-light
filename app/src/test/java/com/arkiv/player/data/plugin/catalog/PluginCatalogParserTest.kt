package com.arkiv.player.data.plugin.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCatalogParserTest {
    private fun entry(id: String = "internet-archive", repo: String = "kinotvapp/kino-plugin-archive", extra: String = "") =
        """{"id":"$id","repo":"$repo","name":"Internet Archive","description":"Películas de dominio público"$extra}"""

    private fun catalog(vararg entries: String, schema: Int = 1) =
        """{"schema":$schema,"plugins":[${entries.joinToString(",")}]}"""

    private fun parse(json: String, caps: Set<String> = emptySet()) = PluginCatalogParser.parse(json, caps)

    @Test fun `entries come back in the order they are listed`() {
        val c = parse(catalog(entry("a", "o/a"), entry("b", "o/b"), entry("c", "o/c")))!!
        assertEquals(listOf("a", "b", "c"), c.entries.map { it.id })
        assertEquals("o/b", c.entries[1].repo)
    }

    @Test fun `a repo that is not owner slash name is dropped and the rest kept`() {
        val bad = listOf("../etc/passwd", "https://evil.example/x", "a/b/c", "owner/", "/name", "own er/name", "a/b?x=1", "", "../x", "x/..", "./x", "x/.")
        for (repo in bad) {
            val c = parse(catalog(entry("bad", repo), entry("good", "o/good")))!!
            assertEquals("repo '$repo'", listOf("good"), c.entries.map { it.id })
        }
    }

    @Test fun `a missing or newer schema gives null`() {
        assertNull(parse(catalog(entry(), schema = 2)))
        assertNull(parse("""{"plugins":[${entry()}]}"""))
    }

    @Test fun `garbage gives null`() {
        for (s in listOf("", "<html>portal</html>", "[]", "null", "{", """{"schema":1}""")) assertNull("'$s'", parse(s))
    }

    @Test fun `more than 64 KB gives null`() {
        val big = catalog(entry(extra = ""","note":"${"x".repeat(PluginCatalogParser.MAX_BYTES)}""""))
        assertNull(parse(big))
    }

    @Test fun `only the first 50 entries are read`() {
        val many = (1..60).map { entry("p$it", "o/p$it") }.toTypedArray()
        assertEquals(50, parse(catalog(*many))!!.entries.size)
    }

    @Test fun `a duplicate id keeps the first`() {
        val c = parse(catalog(entry("x", "o/first"), entry("x", "o/second")))!!
        assertEquals(listOf("o/first"), c.entries.map { it.repo })
    }

    @Test fun `an entry that requires a capability this build lacks is hidden`() {
        val json = catalog(entry("xuper", "o/x", extra = ""","requires":["xuper-bridge"]"""), entry("ia", "o/ia"))
        assertEquals(listOf("ia"), parse(json, caps = emptySet())!!.entries.map { it.id })
        assertEquals(listOf("xuper", "ia"), parse(json, caps = setOf("xuper-bridge"))!!.entries.map { it.id })
    }

    @Test fun `flags are read and default to false`() {
        val c = parse(catalog(
            entry("a", "o/a", extra = ""","needsSetup":true,"legacyDefault":true,"bundled":true"""),
            entry("b", "o/b"),
        ))!!
        assertTrue(c.entries[0].needsSetup && c.entries[0].legacyDefault && c.entries[0].bundled)
        assertFalse(c.entries[1].needsSetup || c.entries[1].legacyDefault || c.entries[1].bundled)
    }

    @Test fun `unknown fields are ignored and long text is cut`() {
        val long = "y".repeat(500)
        val c = parse("""{"schema":1,"future":true,"plugins":[{"id":"a","repo":"o/a","name":"${"n".repeat(200)}","description":"$long","surprise":[1]}]}""")!!
        assertEquals(60, c.entries.single().name.length)
        assertEquals(200, c.entries.single().description.length)
    }

    @Test fun `tags are capped and blank ones dropped`() {
        val c = parse(catalog(entry(extra = ""","tags":["a"," ","b","c","d","e","f","${"z".repeat(50)}"]""")))!!
        assertEquals(listOf("a", "b", "c", "d", "e"), c.entries.single().tags)
    }

    @Test fun `isValidRepo is the same rule the parser applies`() {
        for (good in listOf("kinotvapp/kino-plugin-archive", "owner/my.plugin", "some.org/repo_name.v2")) assertTrue("'$good'", PluginCatalogParser.isValidRepo(good))
        for (bad in listOf("../x", "a/b/c", "a/b?x=1", "own er/name", "", "https://x/y", "./x", "x/.", "x/..", "../..", "..")) assertFalse("'$bad'", PluginCatalogParser.isValidRepo(bad))
    }

    @Test fun `catalog entries with dotted repo names are kept`() {
        val c = parse(catalog(entry("dotted", "owner/my.plugin")))!!
        assertEquals(listOf("owner/my.plugin"), c.entries.map { it.repo })
    }

    @Test fun `deeply nested JSON in valid catalog gives null`() {
        val nested = "[".repeat(200)
        val deepCatalog = """{"schema":1,"plugins":[$nested${entry()}${"]".repeat(200)}]}"""
        assertNull(parse(deepCatalog))
    }

    @Test fun `brackets inside string values do not count toward depth`() {
        val bracketsInString = """{"schema":1,"plugins":[{"id":"a","repo":"o/a","name":"Test","description":"${"[".repeat(100)}"}]}"""
        val c = parse(bracketsInString)!!
        assertEquals(100, c.entries.single().description.length)
    }

    @Test fun `a legitimate catalog parses despite brackets in description`() {
        val bracketsInDesc = """{"schema":1,"plugins":[{"id":"test","repo":"o/r","name":"Test","description":"See [docs] for [details]"}]}"""
        val c = parse(bracketsInDesc)!!
        assertEquals("See [docs] for [details]", c.entries.single().description)
    }

    @Test fun `an id that is not a slug is dropped`() {
        val c = parse(catalog(entry("Bad Id!", "o/a"), entry("ok-1", "o/b")))!!
        assertEquals(listOf("ok-1"), c.entries.map { it.id })
    }
}
