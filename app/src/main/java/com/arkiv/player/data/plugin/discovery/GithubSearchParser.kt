package com.arkiv.player.data.plugin.discovery

import com.arkiv.player.data.plugin.PluginAddress
import com.arkiv.player.data.plugin.catalog.PluginCatalogParser
import org.json.JSONException
import org.json.JSONObject

/** One usable search result: a public, non-fork repo at `https://github.com/<owner>/<repo>`. */
data class DiscoveredRepo(val owner: String, val repo: String, val stars: Int) {
    /** `owner/repo` lowercased: GitHub names are case-insensitive. */
    val key: String get() = "$owner/$repo".lowercase()
}

/**
 * Reads GitHub's search answer. It is DATA from the network: a body that is too big, not strict JSON
 * or nested too deep is refused whole (null); an item that is not exactly a non-fork repo at
 * `https://github.com/<owner>/<repo>` with a matching owner and an address [PluginAddress] accepts is
 * skipped. At most [DiscoveryRules.MAX_RESULTS] results, first ones win.
 */
object GithubSearchParser {
    private const val MAX_ITEMS_READ = 100

    fun parse(json: String): List<DiscoveredRepo>? {
        if (json.toByteArray(Charsets.UTF_8).size > GithubApi.MAX_SEARCH_BYTES) return null
        if (!PluginCatalogParser.isSafeToParse(json)) return null
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        val items = root.optJSONArray("items") ?: return null
        val seen = HashSet<String>()
        val out = ArrayList<DiscoveredRepo>()
        for (i in 0 until minOf(items.length(), MAX_ITEMS_READ)) {
            if (out.size == DiscoveryRules.MAX_RESULTS) break
            val repo = items.optJSONObject(i)?.let(::repoOf) ?: continue
            if (seen.add(repo.key)) out += repo
        }
        return out
    }

    private fun repoOf(o: JSONObject): DiscoveredRepo? {
        if (o.opt("fork") != false) return null
        val fullName = o.opt("full_name") as? String ?: return null
        if (!PluginCatalogParser.isValidRepo(fullName)) return null
        val address = PluginAddress.parse(fullName) ?: return null
        if (address.path.isNotEmpty() || address.ref != PluginAddress.HEAD) return null
        val htmlUrl = o.opt("html_url") as? String ?: return null
        if (!htmlUrl.equals("https://github.com/$fullName", ignoreCase = true)) return null
        val login = o.optJSONObject("owner")?.opt("login") as? String ?: return null
        if (!login.equals(address.owner, ignoreCase = true)) return null
        val stars = (o.opt("stargazers_count") as? Int)?.coerceAtLeast(0) ?: 0
        return DiscoveredRepo(address.owner, address.repo, stars)
    }
}
