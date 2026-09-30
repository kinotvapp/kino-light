package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedSecretsTest {
    private val b = "owner/repo"
    private fun open(seal: String, binding: String = b, name: String = "apiKey") =
        SealedSecrets.open(seal, binding, name, TestSealing.agreement, TestSealing.TEST_PUBLIC)

    @Test fun `the test key pair matches RFC 7748`() {
        assertEquals(TestSealing.TEST_PUBLIC.toList(), TestSealing.publicOf(TestSealing.TEST_PRIVATE).toList())
    }

    @Test fun `a seal opens to its value`() {
        assertEquals("s3cr3t-ñ", open(TestSealing.seal("s3cr3t-ñ", b, "apiKey")))
    }

    @Test fun `a seal for another repo, path or name does not open`() {
        val s = TestSealing.seal("v", b, "apiKey")
        assertThrows(SealException::class.java) { open(s, binding = "other/repo") }
        assertThrows(SealException::class.java) { open(s, binding = "owner/repo/sub") }
        assertThrows(SealException::class.java) { open(s, name = "token") }
    }

    @Test fun `a tampered seal does not open`() {
        val s = TestSealing.seal("value", b, "apiKey")
        val raw = java.util.Base64.getUrlDecoder().decode(s.removePrefix(SealedSecrets.PREFIX_V1))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        val bad = SealedSecrets.PREFIX_V1 + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertThrows(SealException::class.java) { open(bad) }
    }

    @Test fun `format checks`() {
        val s = TestSealing.seal("v", b, "apiKey")
        assertTrue(SealedSecrets.isWellFormed(s))
        assertFalse(SealedSecrets.isWellFormed("kino-sealed:v2:" + s.removePrefix(SealedSecrets.PREFIX_V1)))
        assertFalse(SealedSecrets.isWellFormed("kino-sealed:v1:@@@"))
        assertFalse(SealedSecrets.isWellFormed("kino-sealed:v1:" + "A".repeat(20)))
        assertFalse(SealedSecrets.isWellFormed(TestSealing.seal("x".repeat(4097), b, "apiKey")))
    }

    @Test fun `binding is lowercase owner-repo-path without ref`() {
        assertEquals("owner/repo", SealedSecrets.bindingOf(PluginAddress.parse("Owner/Repo@main")!!))
        assertEquals("owner/repo/plugins/x", SealedSecrets.bindingOf(PluginAddress.parse("owner/repo/plugins/x@v2")!!))
    }

    @Test fun `names follow the pattern`() {
        assertTrue(SealedSecrets.NAME.matches("apiKey_2"))
        assertFalse(SealedSecrets.NAME.matches("2key"))
        assertFalse(SealedSecrets.NAME.matches("a".repeat(33)))
    }
}
