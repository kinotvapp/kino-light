package com.arkiv.player.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PluginSecretsTest {
    private val binding = "owner/repo"
    private val sealed = mapOf(
        "apiKey" to TestSealing.seal("k-123", binding, "apiKey"),
        "token" to TestSealing.seal("t-456", binding, "token"),
    )

    private fun secrets(agreement: X25519Agreement = TestSealing.agreement, nonce: String = PluginSecrets.randomNonce()) =
        PluginSecrets(sealed, binding, agreement, listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC, nonce = nonce)

    @Test fun `a marker names the secret and a 16-hex nonce`() {
        val m = secrets().marker("apiKey")!!
        assertTrue(m, Regex("^__kinoSecret_apiKey_[0-9a-f]{16}__$").matches(m))
        assertEquals(m, secrets(nonce = m.removePrefix("__kinoSecret_apiKey_").removeSuffix("__")).marker("apiKey"))
    }

    @Test fun `every runtime gets its own nonce`() {
        assertNotEquals(secrets().marker("apiKey"), secrets().marker("apiKey"))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(PluginSecrets.randomNonce()))
    }

    @Test fun `an undeclared name has no marker`() {
        assertNull(secrets().marker("nope"))
    }

    @Test fun `substitute replaces every occurrence of every marker`() {
        val s = secrets()
        val a = s.marker("apiKey")!!
        val t = s.marker("token")!!
        assertEquals("a=k-123&b=k-123&c=t-456", s.substitute("a=$a&b=$a&c=$t"))
        assertEquals("nothing here", s.substitute("nothing here"))
    }

    @Test fun `a marker with another runtime's nonce is left alone`() {
        val mine = secrets(nonce = "0123456789abcdef")
        val other = secrets(nonce = "fedcba9876543210").marker("apiKey")!!
        assertEquals("k=$other", mine.substitute("k=$other"))
        assertFalse(mine.containsMarker("k=$other"))
    }

    @Test fun `containsMarker sees a marker anywhere in the text`() {
        val s = secrets()
        assertTrue(s.containsMarker("https://api.example.com/x?key=" + s.marker("token")))
        assertFalse(s.containsMarker("https://api.example.com/x?key=__kinoSecret_token__"))
    }

    @Test fun `each seal is opened at most once`() {
        val opens = AtomicInteger()
        val s = secrets(agreement = { peer -> opens.incrementAndGet(); TestSealing.agreement.sharedSecret(peer) })
        val a = s.marker("apiKey")!!
        assertEquals(0, opens.get())
        repeat(3) { s.substitute("x=$a&y=$a") }
        assertEquals(1, opens.get())
        s.substitute("z=" + s.marker("token"))
        assertEquals(2, opens.get())
    }

    @Test fun `each context gets its own encoding of the value`() {
        val s = PluginSecrets(
            mapOf("apiKey" to TestSealing.seal("a b/c&d=é\"\\\n", binding, "apiKey")), binding, TestSealing.agreement,
            listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        val m = s.marker("apiKey")!!
        assertEquals("[a b/c&d=é\"\\\n]", s.substitute("[$m]"))
        assertEquals("[a%20b%2Fc%26d%3D%C3%A9%22%5C%0A]", s.substitute("[$m]", PluginSecrets.Encoding.URL_COMPONENT))
        assertEquals("[a b/c&d=é\\\"\\\\\\n]", s.substitute("[$m]", PluginSecrets.Encoding.JSON_STRING))
        assertEquals("-._~AZaz09", PluginSecrets.encode("-._~AZaz09", PluginSecrets.Encoding.URL_COMPONENT))
        assertEquals("\\u0001", PluginSecrets.encode("\u0001", PluginSecrets.Encoding.JSON_STRING))
        // Every form written is a form redacted.
        for (e in PluginSecrets.Encoding.entries) assertEquals("[$m]", s.redact(s.substitute("[$m]", e)))
    }

    @Test fun `redact replaces the longest value first`() {
        val s = PluginSecrets(
            mapOf("short" to TestSealing.seal("abc", binding, "short"), "long" to TestSealing.seal("abcdef", binding, "long")),
            binding, TestSealing.agreement, listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        val short = s.marker("short")!!
        val long = s.marker("long")!!
        s.substitute(short + long)
        assertEquals("x $long y $short z", s.redact("x abcdef y abc z"))
    }

    @Test fun `redact puts the marker back in place of an opened value`() {
        val s = secrets()
        val a = s.marker("apiKey")!!
        assertEquals("seen k-123", s.redact("seen k-123"))
        s.substitute(a)
        assertEquals("seen $a twice $a", s.redact("seen k-123 twice k-123"))
    }
}
