package com.arkiv.player.data.plugin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SealedCodeTest {
    private val author = TestSealing.newAuthorKey()
    private val binding = "o/r"
    private val id = "demo"
    private val script = "export async function search(){ return [] }\nexport async function resolve(){ return { url: 'https://a.example.com/x.m3u8' } }\n// ñandú ✓\n"

    private fun open(blob: ByteArray, binding: String = this.binding, id: String = this.id, expected: ByteArray? = null) =
        SealedCode.open(blob, binding, id, TestSealing.agreement, TestSealing.TEST_PUBLIC, expected)

    private fun refusal(message: String, block: () -> Unit) {
        val e = assertThrows(SealException::class.java) { block() }
        assertEquals(message, e.message)
    }

    @Test fun `a sealed script opens for its binding and id, deflated or stored`() {
        assertEquals(script, open(TestSealing.sealCode(script, binding, id, author)))
        assertEquals(script, open(TestSealing.sealCode(script, binding, id, author, compression = 0)))
        assertEquals(script, open(TestSealing.sealCode(script, binding, id, author), expected = author.publicRaw))
    }

    @Test fun `the signature verifies and names the author key`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        assertArrayEquals(author.publicRaw, SealedCode.verifiedAuthorKey(blob))
    }

    @Test fun `another repo, folder or plugin id cannot open it`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        refusal(SealedCode.NOT_FOR_THIS_PLUGIN) { open(blob, binding = "fork/r") }
        refusal(SealedCode.NOT_FOR_THIS_PLUGIN) { open(blob, binding = "o/r/sub") }
        refusal(SealedCode.NOT_FOR_THIS_PLUGIN) { open(blob, id = "other") }
    }

    @Test fun `a script sealed for another recipient key does not open`() {
        val blob = TestSealing.sealCode(script, binding, id, author, recipientPublic = SealedSecrets.KINO_PUBLIC_KEY_V1)
        assertThrows(SealException::class.java) { open(blob) }
    }

    @Test fun `a flipped bit anywhere is refused, by the signature and by the GCM tag`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        val rnd = Random(3)
        // Every header byte, plus a sample of the rest.
        val positions = (0 until SealedCode.HEADER_BYTES) + (0 until 60).map { rnd.nextInt(SealedCode.HEADER_BYTES, blob.size) }
        for (i in positions) {
            val bad = blob.copyOf().also { it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            assertThrows("byte $i (signature)", SealException::class.java) { SealedCode.verifiedAuthorKey(bad) }
            // Only the signature itself is outside what GCM authenticates.
            if (i < blob.size - SealedCode.SIGNATURE_BYTES) assertThrows("byte $i (open)", SealException::class.java) { open(bad) }
        }
    }

    @Test fun `a signature by another key over the same bytes is refused`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        val unsigned = blob.copyOf(blob.size - SealedCode.SIGNATURE_BYTES)
        val forged = unsigned + TestSealing.signCode(unsigned, TestSealing.newAuthorKey())
        refusal(SealedCode.BAD_SIGNATURE) { SealedCode.verifiedAuthorKey(forged) }
    }

    @Test fun `re-signing someone else's ciphertext under your own key fails to open`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        val thief = TestSealing.newAuthorKey()
        val swapped = blob.copyOf(blob.size - SealedCode.SIGNATURE_BYTES).also { thief.publicRaw.copyInto(it, 12) }
        val resigned = swapped + TestSealing.signCode(swapped, thief)
        assertArrayEquals(thief.publicRaw, SealedCode.verifiedAuthorKey(resigned)) // the signature itself is fine...
        refusal(SealedCode.NOT_FOR_THIS_PLUGIN) { open(resigned) } // ...but the author key is in the AAD
    }

    @Test fun `a pinned key that differs refuses before decrypting`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        var agreed = false
        val e = assertThrows(SealException::class.java) {
            SealedCode.open(blob, binding, id, { agreed = true; ByteArray(32) }, TestSealing.TEST_PUBLIC, TestSealing.newAuthorKey().publicRaw)
        }
        assertEquals(SealedCode.OTHER_AUTHOR_KEY, e.message)
        assertTrue(!agreed)
    }

    @Test fun `malformed files are damaged`() {
        val blob = TestSealing.sealCode(script, binding, id, author)
        refusal(SealedCode.DAMAGED) { SealedCode.header(ByteArray(10)) }
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf(SealedCode.MIN_BYTES - 1)) }
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[0] = 'X'.code.toByte() }) }
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[4] = 2 }) } // version
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[5] = 2 }) } // alg (reserved)
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[6] = 7 }) } // compression
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[7] = 0 }) } // unsigned
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[8] = 0x7f }) } // > 4 MiB
        refusal(SealedCode.DAMAGED) { SealedCode.header(blob.copyOf().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0 }) }
        // Unsigned files are refused even when validly sealed.
        refusal(SealedCode.DAMAGED) { open(TestSealing.sealCode(script, binding, id, author, flags = 0)) }
    }

    @Test fun `a length that lies -- shorter or longer than the inflated script -- is damaged (zip-bomb safe)`() {
        val n = script.toByteArray().size
        refusal(SealedCode.DAMAGED) { open(TestSealing.sealCode(script, binding, id, author, declaredLength = n - 1)) }
        refusal(SealedCode.DAMAGED) { open(TestSealing.sealCode(script, binding, id, author, declaredLength = n + 1)) }
        // A bomb: 16 MiB of zeros deflates to ~16 KB; declared as 1 KB it stops at 1 KB and is refused.
        val bomb = ByteArray(16 * 1024 * 1024)
        refusal(SealedCode.DAMAGED) { open(TestSealing.sealCode("", binding, id, author, plainBytes = bomb, declaredLength = 1024)) }
        // Stored: the ciphertext length must be the declared one.
        refusal(SealedCode.DAMAGED) { open(TestSealing.sealCode(script, binding, id, author, compression = 0, declaredLength = n + 3)) }
    }

    @Test fun `up to the 4 MiB cap opens, past it is refused`() {
        val big = buildString { while (length < SealedCode.MAX_PLAIN_BYTES - 64) append("export const a${length} = ${length};\n") }
        val blob = TestSealing.sealCode(big, binding, id, author)
        assertTrue("deflated under the 1 MiB download cap: ${blob.size}", blob.size <= PluginInstaller.MAX_SCRIPT_BYTES)
        assertEquals(big, open(blob))
        val over = ByteArray(SealedCode.MAX_PLAIN_BYTES + 1) { 'a'.code.toByte() }
        refusal(SealedCode.DAMAGED) { open(TestSealing.sealCode("", binding, id, author, plainBytes = over)) }
    }

    @Test fun `the fingerprint is the first 8 bytes of the key's SHA-256`() {
        val key = ByteArray(32) { it.toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(key)
        val hex = sha.copyOf(8).joinToString("") { "%02X".format(it) }
        assertEquals(hex.chunked(4).joinToString("-"), SealedCode.fingerprint(key))
        assertEquals(19, SealedCode.fingerprint(key).length)
    }
}
