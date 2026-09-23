package com.arkiv.player.data.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KiloCatalogTest {

    private fun model(
        id: String,
        price: Any? = "0",
        tools: Boolean = true,
        name: String = id,
        description: String = "a general chat model",
    ): String {
        val params = if (tools) """["tools","temperature"]""" else """["temperature"]"""
        val pricing = if (price == null) "{}" else """{"prompt": ${JSONObject.quote(price.toString())}}"""
        return """{"id":"$id","name":${JSONObject.quote(name)},"description":${JSONObject.quote(description)},
                   "pricing":$pricing,"supported_parameters":$params}"""
    }

    private fun catalog(vararg models: String) = JSONObject("""{"data":[${models.joinToString(",")}]}""")

    @Test fun `free and with tools gets in`() {
        val c = KiloCatalog.candidates(catalog(model("nvidia/nemotron-3-super:free")))
        assertEquals(listOf("nvidia/nemotron-3-super:free"), c.map { it.id })
    }

    @Test fun `paid does not get in`() {
        assertTrue(KiloCatalog.candidates(catalog(model("paid/model", price = "0.0000015"))).isEmpty())
    }

    @Test fun `free with no tools does not get in`() {
        assertTrue(KiloCatalog.candidates(catalog(model("no/tools:free", tools = false))).isEmpty())
    }

    @Test fun `no price is not free`() {
        assertTrue(KiloCatalog.candidates(catalog(model("no/price", price = null))).isEmpty())
    }

    @Test fun `a price with decimals at zero is free`() {
        assertTrue(KiloCatalog.isFree(JSONObject(model("x", price = "0.0000"))))
    }

    /** The classifier that in llm-libre ended up first in the ranking by answering "safe" to everything. */
    @Test fun `a safety classifier does not get in`() {
        val m = model(
            "nvidia/nemotron-3.5-content-safety:free",
            description = "A content safety classifier that flags unsafe prompts",
        )
        assertTrue(KiloCatalog.candidates(catalog(m)).isEmpty())
    }

    @Test fun `a random meta router does not get in`() {
        val m = model("kilo-auto/free", description = "Rotates through available free models")
        assertTrue(KiloCatalog.candidates(catalog(m)).isEmpty())
    }

    @Test fun `an embeddings model does not get in`() {
        val m = model("x/emb:free", name = "Text Embeddings Small")
        assertTrue(KiloCatalog.candidates(catalog(m)).isEmpty())
    }

    @Test fun `keeps the catalog's order`() {
        val c = KiloCatalog.candidates(catalog(model("b:free"), model("a:free")))
        assertEquals(listOf("b:free", "a:free"), c.map { it.id })
    }

    @Test fun `a catalog with no data gives empty`() {
        assertTrue(KiloCatalog.candidates(JSONObject("{}")).isEmpty())
    }

    @Test fun `isFree with a numeric price`() {
        assertTrue(KiloCatalog.isFree(JSONObject("""{"pricing":{"prompt":0}}""")))
        assertFalse(KiloCatalog.isFree(JSONObject("""{"pricing":{"prompt":0.1}}""")))
    }
}
