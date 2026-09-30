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

    @Test fun `redact knows every form a server may echo the value in`() {
        val plain = "k3y with/sl?sh+plus~*>"
        val s = PluginSecrets(
            mapOf("apiKey" to TestSealing.seal(plain, binding, "apiKey")), binding, TestSealing.agreement,
            listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        val m = s.marker("apiKey")!!
        s.substitute(m)
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val form = java.net.URLEncoder.encode(plain, "UTF-8")
        val forms = listOf(
            plain,
            form,
            form.replace("+", "%20"),
            PluginSecrets.encode(plain, PluginSecrets.Encoding.URL_COMPONENT),
            java.util.Base64.getEncoder().encodeToString(bytes),
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes),
            java.util.Base64.getUrlEncoder().encodeToString(bytes),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        )
        // Eight different texts: the value was picked so that no two forms coincide.
        assertEquals(forms.toString(), 8, forms.toSet().size)
        for (f in forms) assertEquals(f, "<$m>", s.redact("<$f>"))
        assertEquals(forms.joinToString(" ") { m }, s.redact(forms.joinToString(" ")))
    }

    @Test fun `redact sees every declared value, used or not, and hands back text with none untouched`() {
        val s = secrets()
        val a = s.marker("apiKey")!!
        val t = s.marker("token")!!
        s.substitute(a)
        assertEquals("$a $t", s.redact("k-123 t-456"))
        val clean = "nothing to see " + "x".repeat(1000)
        org.junit.Assert.assertSame(clean, s.redact(clean))
        assertFalse(s.containsValue(clean))
        assertTrue(s.containsValue("..t-456.."))
    }

    // A value can reach the plugin before this runtime ever used it: a cookie a previous runtime's
    // request set, or a server echoing it to a request that didn't carry it. So the first redaction
    // of any text opens every declared seal (once each), not only the ones used so far.
    @Test fun `redact puts the marker back in place of a value this runtime never used`() {
        val s = secrets()
        assertEquals("seen ${s.marker("apiKey")} twice ${s.marker("apiKey")} and ${s.marker("token")}", s.redact("seen k-123 twice k-123 and t-456"))
        assertTrue(secrets().containsValue("..k-123.."))
    }

    @Test fun `the first non-empty redaction opens every seal once, and nothing opens them again`() {
        val opens = AtomicInteger()
        val s = secrets(agreement = { peer -> opens.incrementAndGet(); TestSealing.agreement.sharedSecret(peer) })
        assertEquals("", s.redact(""))
        assertFalse(s.containsValue(""))
        assertEquals(0, opens.get())
        assertEquals("x", s.redact("x"))
        assertEquals(2, opens.get())
        s.redact("k-123")
        s.containsValue("t-456")
        s.substitute(s.marker("apiKey")!! + s.marker("token")!!)
        assertEquals(2, opens.get())
    }

    @Test fun `a seal that won't open leaves redaction working and substitution failing, and is tried once`() {
        val opens = AtomicInteger()
        val s = secrets(agreement = { peer ->
            opens.incrementAndGet()
            if (peer.contentEquals(ephemeralOf(sealed.getValue("token")))) throw SealException(SealedSecrets.NO_NATIVE_MESSAGE)
            TestSealing.agreement.sharedSecret(peer)
        })
        assertEquals("${s.marker("apiKey")} t-456", s.redact("k-123 t-456"))
        assertEquals("${s.marker("apiKey")} t-456", s.redact("k-123 t-456"))
        assertEquals(2, opens.get())
        org.junit.Assert.assertThrows(SealException::class.java) { s.substitute(s.marker("token")!!) }
    }

    private fun ephemeralOf(seal: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(seal.removePrefix(SealedSecrets.PREFIX_V1)).copyOfRange(0, 32)

    @Test fun `redact knows the JSON echo forms - an escaped slash and non-ASCII as uXXXX in either case`() {
        val key = "ab/cd+ef=="
        val accented = "clé/ñ\u0001"
        val s = PluginSecrets(
            mapOf("key" to TestSealing.seal(key, binding, "key"), "accented" to TestSealing.seal(accented, binding, "accented")),
            binding, TestSealing.agreement, listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        val k = s.marker("key")!!
        val a = s.marker("accented")!!
        // PHP's json_encode (\/ and lowercase \u), Python's json.dumps (lowercase \u), and uppercase \u.
        val echoes = mapOf(
            "ab\\/cd+ef==" to k,
            "cl\\u00e9\\/\\u00f1\\u0001" to a,
            "cl\\u00e9/\\u00f1\\u0001" to a,
            "cl\\u00E9/\\u00F1\\u0001" to a,
            "cl\\u00E9\\/\\u00F1\\u0001" to a,
            "clé\\/ñ\\u0001" to a,
        )
        for ((echo, marker) in echoes) assertEquals(echo, "{\"v\":\"$marker\"}", s.redact("{\"v\":\"$echo\"}"))
        // Past the BMP: a surrogate pair, each half escaped.
        val emoji = "k\uD83D\uDE00y"
        val e = PluginSecrets(
            mapOf("e" to TestSealing.seal(emoji, binding, "e")), binding, TestSealing.agreement, listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        assertEquals("[${e.marker("e")}]", e.redact("[k\\ud83d\\ude00y]"))
        assertEquals("[${e.marker("e")}]", e.redact("[k\\uD83D\\uDE00y]"))
    }

    // At the caps: 16 secrets of 4096 bytes, each full of characters every encoding changes, all
    // opened, echoed in every form inside a multi-megabyte text. One pass must finish well inside a
    // plugin call's time, and leave no form of any value behind.
    @Test fun `redaction at the caps is complete and fast`() {
        fun tricky(i: Int): String {
            val unit = "\"\\ñ/+ é$i<"
            val sb = StringBuilder()
            while (sb.toString().toByteArray().size < 4096) sb.append(unit)
            var v = sb.toString()
            while (v.toByteArray().size > 4096) v = v.dropLast(1)
            return v
        }
        val values = (0 until SealedSecrets.MAX_SECRETS).associate { "s$it" to tricky(it) }
        values.values.forEach { assertTrue(it.toByteArray().size in 4090..4096) }
        val s = PluginSecrets(
            values.mapValues { (name, v) -> TestSealing.seal(v, binding, name) }, binding, TestSealing.agreement,
            listOf("api.example.com"), recipient = TestSealing.TEST_PUBLIC,
        )
        val forms = values.values.flatMap { PluginSecrets.echoForms(it) }
        val text = buildString {
            for (f in forms) append("filler ").append(f).append(' ')
            append("x".repeat(2_500_000))
        }
        assertTrue(text.length.toString(), text.length > 3_000_000)
        s.redact("warm up")
        val started = System.nanoTime()
        val out = s.redact(text)
        val ms = (System.nanoTime() - started) / 1_000_000
        for ((name, v) in values) {
            for (f in PluginSecrets.echoForms(v)) assertFalse("$name form left", f in out)
            assertTrue(s.marker(name)!! in out)
        }
        println("redaction at the caps: $ms ms for ${text.length} chars")
        assertTrue("redaction took $ms ms", ms < 10_000)
    }
}
