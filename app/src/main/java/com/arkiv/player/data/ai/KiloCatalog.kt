package com.arkiv.player.data.ai

import org.json.JSONObject

/** A Kilo model good for this app: free, with `tools`, and genuinely chat. */
internal data class KiloModel(val id: String)

/**
 * Which models from Kilo's catalog qualify. The rules are the same as
 * `llm-libre/src/llm_libre/catalog.py`'s.
 *
 * - **Free** is `pricing.prompt == 0`, nothing more.
 * - **`tools`** isn't required because they're used: requiring it would leave out the fronts that
 *   splice their own text inside `content`, and the app draws that text verbatim on screen.
 * - **Discarded by what the model says about itself** (`name` and `description`), not by its id: a
 *   blacklist of ids rots; a guardrail that shows up tomorrow under another name will keep
 *   describing itself as a guardrail. In llm-libre,
 *   `nvidia/nemotron-3.5-content-safety:free` —a classifier that answers "User Safety: safe" to
 *   everything— ended up first in the ranking.
 */
internal object KiloCatalog {

    private val DISCARD = Regex(
        listOf(
            // Specialties that aren't chat.
            "guardrail", "content safety", "\\bmoderation\\b", "\\bmoderates\\b", "\\bclassifier\\b",
            "\\breranker\\b", "\\bre-ranker\\b", "\\breranking\\b",
            "embeddings? model", "text embeddings?\\b",
            "speech[- ]to[- ]text", "text[- ]to[- ]speech",
            // Meta-routers: not a model, a lottery among others.
            "\\bmodels? router\\b", "\\bis a router\\b", "rotates through",
            "\\brouter\\b.{0,60}\\bselects\\b", "selects .{0,40}\\bmodels\\b.{0,20}at random",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    fun candidates(json: JSONObject): List<KiloModel> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!isFree(m)) return@mapNotNull null
            if (!acceptsTools(m)) return@mapNotNull null
            if (DISCARD.containsMatchIn(m.optString("name") + " " + m.optString("description"))) {
                return@mapNotNull null
            }
            KiloModel(id)
        }
    }

    fun isFree(model: JSONObject): Boolean {
        val price = model.optJSONObject("pricing")?.opt("prompt") ?: return false
        return price.toString().toDoubleOrNull() == 0.0
    }

    private fun acceptsTools(model: JSONObject): Boolean {
        val params = model.optJSONArray("supported_parameters") ?: return false
        return (0 until params.length()).any { params.optString(it) == "tools" }
    }
}
