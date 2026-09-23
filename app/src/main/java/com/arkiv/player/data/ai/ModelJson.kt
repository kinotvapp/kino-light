package com.arkiv.player.data.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Couldn't get the requested JSON out of the model's text. */
internal class UnreadableJson(message: String) : Exception(message)

/**
 * Pulls the JSON out of a model's answer, even when it comes wrapped.
 *
 * Ported from `arkiv-api/src/arkiv_api/llm_json.py`: asking the model not to wrap it in
 * ```` ```json ```` isn't enough, and throwing away a good answer because of the wrapper would
 * waste work that was already done.
 */
internal object ModelJson {

    private val FENCE = Regex("^```(?:json)?|```$", RegexOption.MULTILINE)

    private fun withoutFence(text: String): String = text.trim().replace(FENCE, "").trim()

    fun array(text: String): JSONArray {
        val raw = withoutFence(text)
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end < start) throw UnreadableJson("no JSON array arrived")
        return try {
            JSONArray(raw.substring(start, end + 1))
        } catch (e: JSONException) {
            throw UnreadableJson("invalid JSON: ${e.message}")
        }
    }

    /**
     * Like [array], but for a SINGLE object. An array does NOT count: if a bracket opens before
     * the first brace, what arrived IS a list, and the object found would be one of its elements.
     */
    fun obj(text: String): JSONObject {
        val raw = withoutFence(text)
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) throw UnreadableJson("no JSON object arrived")
        val bracket = raw.indexOf('[')
        if (bracket in 0 until start) throw UnreadableJson("an array arrived where an object was expected")
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: JSONException) {
            throw UnreadableJson("invalid JSON: ${e.message}")
        }
    }
}
