package com.arkiv.player.data.plugin.discovery

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubSearchParserTest {
    private fun item(
        fullName: String,
        fork: Any? = false,
        stars: Any = 1,
        html: String = "https://github.com/$fullName",
        login: String = fullName.substringBefore('/'),
    ) = JSONObject().put("full_name", fullName).put("html_url", html).put("stargazers_count", stars)
        .put("owner", JSONObject().put("login", login)).apply { if (fork != null) put("fork", fork) }

    private fun body(vararg items: Any) = JSONObject().put("total_count", items.size).put("items", JSONArray(items.toList())).toString()

    @Test fun `keeps valid repos in search order with their stars`() {
        val r = GithubSearchParser.parse(body(item("kinotvapp/kino-plugin-xuper", stars = 9), item("someone/kino-plugin-demo", stars = 2)))!!
        assertEquals(listOf(DiscoveredRepo("kinotvapp", "kino-plugin-xuper", 9), DiscoveredRepo("someone", "kino-plugin-demo", 2)), r)
    }

    @Test fun `forks, and results that do not say they are not forks, are dropped`() {
        val r = GithubSearchParser.parse(body(item("a/fork", fork = true), item("a/unknown", fork = null), item("a/text", fork = "false"), item("a/ok")))!!
        assertEquals(listOf("a/ok"), r.map { "${it.owner}/${it.repo}" })
    }

    @Test fun `only https github com owner repo with a matching owner and a valid address is accepted`() {
        val r = GithubSearchParser.parse(body(
            item("a/one", html = "http://github.com/a/one"),
            item("a/two", html = "https://evil.example/a/two"),
            item("a/three", html = "https://github.com/a/three/tree/main"),
            item("a/four", login = "b"),
            item("bad_owner/x"),
            item("-lead/x"),
            item("${"o".repeat(40)}/x"),
            item("a/.."),
            item("a/b/c"),
            item("Ok/Fine", html = "https://GitHub.com/ok/fine", login = "OK"),
        ))!!
        assertEquals(listOf("Ok/Fine"), r.map { "${it.owner}/${it.repo}" })
    }

    @Test fun `at most 30, duplicates ignoring case dropped, junk stars count as zero`() {
        val items = (1..45).map { item("o$it/r") } + item("O1/R")
        val r = GithubSearchParser.parse(body(*items.toTypedArray()))!!
        assertEquals(30, r.size)
        assertEquals(r.map { it.key }.distinct(), r.map { it.key })
        assertEquals(0, GithubSearchParser.parse(body(item("a/b", stars = "many")))!!.single().stars)
        assertEquals(0, GithubSearchParser.parse(body(item("a/b", stars = -4)))!!.single().stars)
    }

    @Test fun `junk items are skipped, a hostile body is refused whole`() {
        val r = GithubSearchParser.parse("""{"items":[1,"x",null,{"full_name":5},${item("ok/fine")}]}""")!!
        assertEquals(listOf("ok/fine"), r.map { it.key })
        listOf(
            "", "not json", "[]", """{"items":{}}""", """{"items":[]} // c""", """{'items':[]}""",
            "[".repeat(100) + "]".repeat(100),
            """{"items":["""" + "x".repeat(GithubApi.MAX_SEARCH_BYTES) + """"]}""",
        ).forEach { assertNull(it.take(30), GithubSearchParser.parse(it)) }
    }
}
