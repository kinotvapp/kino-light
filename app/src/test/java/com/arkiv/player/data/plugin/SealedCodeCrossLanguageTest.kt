package com.arkiv.player.data.plugin

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Cross-language proof: a `.kjs` made by the Node kit's `seal.mjs` (`sealCode`, what `--code` runs)
 * verifies and opens here with Kotlin's own [SealedCode] and [Ed25519], for the RFC 7748 test key pair
 * both sides know ([TestSealing.TEST_PUBLIC]/[TestSealing.TEST_PRIVATE]).
 *
 * The fixture was produced once with `sealCode(script, "Owner/Repo/Sub", "demo", <a fresh author key>,
 * <TestSealing.TEST_PUBLIC hex>)` and committed as-is; nothing here regenerates it, so a real
 * regression in either side (not a matched pair of bugs) fails this test.
 */
class SealedCodeCrossLanguageTest {
    private val f = JSONObject(File("src/test/resources/plugin/sealed-code/fixture.json").readText())
    private val blob = Base64.getDecoder().decode(f.getString("blob"))

    @Test fun `seal-mjs's sealed code verifies, names its author, and opens for its binding and id`() {
        val key = SealedCode.verifiedAuthorKey(blob)
        assertArrayEquals(TestSealing.hex(f.getString("authorKey")), key)
        assertEquals(f.getString("fingerprint"), SealedCode.fingerprint(key))
        assertEquals(f.getString("script"), SealedCode.open(blob, f.getString("binding"), f.getString("id"), TestSealing.agreement, TestSealing.TEST_PUBLIC, key))
    }

    @Test fun `the fixture refuses another binding, another id and the production key`() {
        assertThrows(SealException::class.java) { SealedCode.open(blob, "owner/repo", "demo", TestSealing.agreement, TestSealing.TEST_PUBLIC) }
        assertThrows(SealException::class.java) { SealedCode.open(blob, f.getString("binding"), "other", TestSealing.agreement, TestSealing.TEST_PUBLIC) }
        assertThrows(Exception::class.java) { SealedCode.open(blob, f.getString("binding"), "demo", TestSealing.agreement, SealedSecrets.KINO_PUBLIC_KEY_V1) }
    }
}
