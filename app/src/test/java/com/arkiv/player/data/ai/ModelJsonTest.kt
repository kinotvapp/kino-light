package com.arkiv.player.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelJsonTest {

    @Test fun `a bare array`() {
        assertEquals(2, ModelJson.array("""["a","b"]""").length())
    }

    @Test fun `an array wrapped in a code fence`() {
        val text = "```json\n[\"one\", \"two\"]\n```"
        assertEquals("two", ModelJson.array(text).getString(1))
    }

    @Test fun `an array with text around it`() {
        val text = "Sure, here they are:\n[1, 2, 3]\nHope they help."
        assertEquals(3, ModelJson.array(text).length())
    }

    @Test fun `no array is unreadable`() {
        assertThrows(UnreadableJson::class.java) { ModelJson.array("dunno") }
    }

    @Test fun `a broken array is unreadable`() {
        assertThrows(UnreadableJson::class.java) { ModelJson.array("[1, 2,") }
    }

    @Test fun `a wrapped object`() {
        val text = "```\n{\"title\": \"Coco\"}\n```"
        assertEquals("Coco", ModelJson.obj(text).getString("title"))
    }

    /** Whoever asks for an object can't get a list and blow up somewhere else later. */
    @Test fun `an array where an object was requested is unreadable`() {
        assertThrows(UnreadableJson::class.java) { ModelJson.obj("""[{"title": "Coco"}]""") }
    }
}
