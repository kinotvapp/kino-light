package com.arkiv.player.data.plugin

import kotlin.system.measureTimeMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `kino.crypto` against published vectors: RFC 1321 (MD5), FIPS 180 examples (SHA), RFC 2202 and
 * RFC 4231 (HMAC), NIST SP 800-38A (AES ECB/CBC/CTR), the GCM spec's test cases 2 and 4, NIST SP
 * 800-67 (3DES), RFC 6070 and RFC 7914 §11 (PBKDF2). Values marked "node" were computed with
 * Node's `crypto` (OpenSSL) where no published vector covers the case.
 */
class PluginCryptoTest {
    private fun run(vararg pairs: Pair<String, Any>): JSONObject = JSONObject(PluginCrypto.run(JSONObject(pairs.toMap()).toString()))
    private fun ok(vararg pairs: Pair<String, Any>): String = run(*pairs).let { assertTrue(it.toString(), it.has("ok")); it.getString("ok") }
    private fun error(vararg pairs: Pair<String, Any>): String = run(*pairs).let { assertTrue(it.toString(), it.has("error")); it.getString("error") }

    private val nistKey = "2b7e151628aed2a6abf7158809cf4f3c"
    private val nistIv = "000102030405060708090a0b0c0d0e0f"
    private val nistBlock = "6bc1bee22e409f96e93d7e117393172a"

    @Test fun `hashes`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ok("op" to "hash", "alg" to "md5", "data" to ""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ok("op" to "hash", "alg" to "md5", "data" to "abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ok("op" to "hash", "alg" to "sha1", "data" to "abc"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ok("op" to "hash", "alg" to "sha256", "data" to "abc"))
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            ok("op" to "hash", "alg" to "sha512", "data" to "abc"),
        )
        // Same digest from hex and base64 input, base64 output.
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", ok("op" to "hash", "alg" to "md5", "data" to "616263", "in" to "hex", "out" to "base64"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ok("op" to "hash", "alg" to "md5", "data" to "YWJj", "in" to "base64"))
    }

    @Test fun `hmac`() {
        val data = "what do ya want for nothing?"
        assertEquals("750c783e6ab0b503eaa86e310a5db738", ok("op" to "hmac", "alg" to "md5", "key" to "Jefe", "data" to data))
        assertEquals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79", ok("op" to "hmac", "alg" to "sha1", "key" to "Jefe", "data" to data))
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", ok("op" to "hmac", "alg" to "sha256", "key" to "Jefe", "data" to data))
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea2505549758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            ok("op" to "hmac", "alg" to "sha512", "key" to "Jefe", "data" to data),
        )
        // RFC 4231 case 1: a 20-byte 0x0b key given as hex.
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            ok("op" to "hmac", "alg" to "sha256", "key" to "0b".repeat(20), "keyEnc" to "hex", "data" to "Hi There"),
        )
        // node: createHmac('sha256', '').update('hola').digest('hex') -- Node accepts an empty key.
        assertEquals(
            "ad56d1d3cef70fdf5ae0ecd769c8912415f195a7adf7fb5e4f523fed5aca05f4",
            ok("op" to "hmac", "alg" to "sha256", "key" to "", "data" to "hola"),
        )
    }

    @Test fun `aes ecb cbc ctr against SP 800-38A`() {
        assertEquals("3ad77bb40d7a3660a89ecaf32466ef97", ok("op" to "encrypt", "alg" to "aes-128-ecb", "key" to nistKey, "keyEnc" to "hex", "data" to nistBlock, "in" to "hex", "padding" to "none", "out" to "hex"))
        assertEquals(
            "bd334f1d6e45f25ff712a214571fa5cc",
            ok("op" to "encrypt", "alg" to "aes-192-ecb", "key" to "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b", "keyEnc" to "hex", "data" to nistBlock, "in" to "hex", "padding" to "none", "out" to "hex"),
        )
        assertEquals("7649abac8119b246cee98e9b12e9197d", ok("op" to "encrypt", "alg" to "aes-128-cbc", "key" to nistKey, "keyEnc" to "hex", "iv" to nistIv, "ivEnc" to "hex", "data" to nistBlock, "in" to "hex", "padding" to "none", "out" to "hex"))
        assertEquals(
            "f58c4c04d6e5f1ba779eabfb5f7bfbd6",
            ok("op" to "encrypt", "alg" to "aes-256-cbc", "key" to "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4", "keyEnc" to "hex", "iv" to nistIv, "ivEnc" to "hex", "data" to nistBlock, "in" to "hex", "padding" to "none", "out" to "hex"),
        )
        assertEquals("874d6191b620e3261bef6864990db6ce", ok("op" to "encrypt", "alg" to "aes-128-ctr", "key" to nistKey, "keyEnc" to "hex", "iv" to "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff", "ivEnc" to "hex", "data" to nistBlock, "in" to "hex", "out" to "hex"))
        // Decrypt goes back; default decrypt output is utf8, default input base64.
        assertEquals(nistBlock, ok("op" to "decrypt", "alg" to "aes-128-cbc", "key" to nistKey, "keyEnc" to "hex", "iv" to nistIv, "ivEnc" to "hex", "data" to "7649abac8119b246cee98e9b12e9197d", "in" to "hex", "padding" to "none", "out" to "hex"))
    }

    @Test fun `pkcs7 padding round trip matches node`() {
        // node: createCipheriv('aes-128-cbc', key, iv) over "hola mundo"
        val encrypted = ok("op" to "encrypt", "alg" to "aes-128-cbc", "key" to nistKey, "keyEnc" to "hex", "iv" to nistIv, "ivEnc" to "hex", "data" to "hola mundo", "out" to "hex")
        assertEquals("91d3f1bb5aa666718bdcd8514571632a", encrypted)
        assertEquals("hola mundo", ok("op" to "decrypt", "alg" to "aes-128-cbc", "key" to nistKey, "keyEnc" to "hex", "iv" to nistIv, "ivEnc" to "hex", "data" to encrypted, "in" to "hex"))
    }

    @Test fun `gcm test cases 2 and 4, tag appended`() {
        val tc2 = ok("op" to "encrypt", "alg" to "aes-128-gcm", "key" to "00".repeat(16), "keyEnc" to "hex", "iv" to "00".repeat(12), "ivEnc" to "hex", "data" to "00".repeat(16), "in" to "hex", "out" to "hex")
        assertEquals("0388dace60b6a392f328c2b971b2fe78" + "ab6e47d42cec13bdf53a67b21257bddf", tc2)
        val pt = "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
        val ct = "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091"
        val tag = "5bc94fbc3221a5db94fae95ae7121a47"
        val common = arrayOf("alg" to "aes-128-gcm", "key" to "feffe9928665731c6d6a8f9467308308", "keyEnc" to "hex", "iv" to "cafebabefacedbaddecaf888", "ivEnc" to "hex", "aad" to "feedfacedeadbeeffeedfacedeadbeefabaddad2", "aadEnc" to "hex")
        assertEquals(ct + tag, ok("op" to "encrypt", *common, "data" to pt, "in" to "hex", "out" to "hex"))
        assertEquals(pt, ok("op" to "decrypt", *common, "data" to ct + tag, "in" to "hex", "out" to "hex"))
        assertTrue(error("op" to "decrypt", *common, "data" to ct + "00".repeat(16), "in" to "hex").contains("etiqueta"))
    }

    @Test fun `3des against SP 800-67 and node`() {
        val key = "0123456789abcdef23456789abcdef01456789abcdef0123"
        assertEquals("a826fd8ce53b855f", ok("op" to "encrypt", "alg" to "des-ede3-ecb", "key" to key, "keyEnc" to "hex", "data" to "5468652071756663", "in" to "hex", "padding" to "none", "out" to "hex"))
        assertEquals("09a16a16c76bc3068d486f51ef871104", ok("op" to "encrypt", "alg" to "des-ede3-cbc", "key" to key, "keyEnc" to "hex", "iv" to "0001020304050607", "ivEnc" to "hex", "data" to "Kino plugin", "out" to "hex"))
    }

    @Test fun `pbkdf2 against RFC 6070 and RFC 7914`() {
        fun p(hash: String, pw: String, salt: String, c: Int, len: Int, vararg extra: Pair<String, Any>) =
            ok("op" to "pbkdf2", "hash" to hash, "password" to pw, "salt" to salt, "iterations" to c, "keyLength" to len, *extra)
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", p("sha1", "password", "salt", 1, 20))
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", p("sha1", "password", "salt", 2, 20))
        assertEquals("4b007901b765489abead49d926f721d065a429c1", p("sha1", "password", "salt", 4096, 20))
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            p("sha256", "passwd", "salt", 1, 64),
        )
        // node: pbkdf2Sync('password','salt',1,64,'sha512') and raw (non-UTF-8) password bytes.
        assertEquals(
            "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            p("sha512", "password", "salt", 1, 64),
        )
        assertEquals("da2f11155f0c7d4b5a2529531ab46743", p("sha256", "ff00fe", "0102", 10, 16, "keyEnc" to "hex", "in" to "hex"))
        // node: pbkdf2Sync('', 'salt', 1, 20, 'sha1') -- the empty-password case.
        assertEquals("a33dddc30478185515311f8752895d36ea4363a2", p("sha1", "", "salt", 1, 20))
    }

    @Test fun `pbkdf2's worst case (sha1, 100000 iterations, 64-byte key) finishes well inside its budget`() {
        val elapsed = measureTimeMillis {
            ok(
                "op" to "pbkdf2", "hash" to "sha1", "password" to "password", "salt" to "salt",
                "iterations" to PluginCrypto.PBKDF2_MAX_ITERATIONS, "keyLength" to PluginCrypto.PBKDF2_MAX_KEY_BYTES,
            )
        }
        assertTrue("pbkdf2's worst case took ${elapsed}ms", elapsed < 8_000)
    }

    @Test fun `random bytes and uuids`() {
        val a = ok("op" to "random", "n" to 16)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(a))
        assertNotEquals(a, ok("op" to "random", "n" to 16))
        assertEquals(1024, java.util.Base64.getDecoder().decode(ok("op" to "random", "n" to 1024, "out" to "base64")).size)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(ok("op" to "uuid")))
    }

    @Test fun `every refusal is a short Spanish error, never an exception`() {
        assertTrue(error("op" to "hash", "alg" to "sha3", "data" to "x").contains("desconocido"))
        assertTrue(error("op" to "encrypt", "alg" to "aes-128-cbc", "key" to "short", "iv" to "x".repeat(16), "data" to "x").contains("16 bytes"))
        assertTrue(error("op" to "encrypt", "alg" to "aes-128-cbc", "key" to "k".repeat(16), "iv" to "short", "data" to "x").contains("iv"))
        assertTrue(error("op" to "encrypt", "alg" to "aes-128-ecb", "key" to "k".repeat(16), "data" to "abc", "padding" to "none").contains("múltiplo"))
        // node: this block decrypts to ...5a, not valid PKCS#7 padding.
        assertTrue(error("op" to "decrypt", "alg" to "aes-128-cbc", "key" to "k".repeat(16), "iv" to "i".repeat(16), "data" to "AAAAAAAAAAAAAAAAAAAAAA==").contains("descifrar"))
        assertTrue(error("op" to "decrypt", "alg" to "aes-128-cbc", "key" to "k".repeat(16), "iv" to "i".repeat(16), "data" to "AAAA").contains("múltiplo"))
        assertTrue(error("op" to "hash", "alg" to "md5", "data" to "zz", "in" to "hex").contains("hexadecimal"))
        assertTrue(error("op" to "hash", "alg" to "md5", "data" to "a", "in" to "hex").contains("hexadecimal"))
        assertTrue(error("op" to "hash", "alg" to "md5", "data" to "***", "in" to "base64").contains("base64"))
        assertTrue(error("op" to "hash", "alg" to "md5", "data" to "x", "in" to "latin1").contains("codificación"))
        assertTrue(error("op" to "pbkdf2", "hash" to "sha1", "password" to "p", "salt" to "s", "iterations" to 100_001, "keyLength" to 20).contains("100000"))
        assertTrue(error("op" to "pbkdf2", "hash" to "sha1", "password" to "p", "salt" to "s", "iterations" to 1, "keyLength" to 65).contains("64"))
        assertTrue(error("op" to "pbkdf2", "hash" to "md5", "password" to "p", "salt" to "s", "iterations" to 1, "keyLength" to 16).contains("desconocido"))
        assertTrue(error("op" to "random", "n" to 1025).contains("1024"))
        assertTrue(error("op" to "random", "n" to 0).contains("1024"))
        assertTrue(error("op" to "nope").contains("desconocida"))
        assertEquals("operación criptográfica inválida", error("op" to "hash"))
        assertEquals("{\"error\":\"operación criptográfica inválida\"}", PluginCrypto.run("not json"))
    }

    @Test fun `an out-of-range iterations, keyLength or n is refused, never silently wrapped`() {
        // 4294967297 = 2^32 + 1: Kotlin's getInt would silently truncate this to 1, passing every
        // range check with a value the caller never sent.
        val huge = 4_294_967_297L
        assertTrue(error("op" to "pbkdf2", "hash" to "sha1", "password" to "p", "salt" to "s", "iterations" to huge, "keyLength" to 20).contains("100000"))
        assertTrue(error("op" to "pbkdf2", "hash" to "sha1", "password" to "p", "salt" to "s", "iterations" to 1, "keyLength" to huge).contains("64"))
        assertTrue(error("op" to "random", "n" to huge).contains("1024"))
    }

    @Test fun `inputs over 5 MB are refused after decoding`() {
        val big = "a".repeat(PluginCrypto.MAX_DATA_BYTES + 1)
        assertTrue(error("op" to "hash", "alg" to "md5", "data" to big).contains("5 MB"))
        assertTrue(ok("op" to "hash", "alg" to "md5", "data" to big.substring(1)).length == 32)
    }

    @Test fun `url-safe base64 and missing padding are accepted`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ok("op" to "hash", "alg" to "md5", "data" to "YWJj", "in" to "base64"))
        assertEquals(PluginCrypto.encode(byteArrayOf(-5, -1), "hex"), PluginCrypto.encode(PluginCrypto.decode("-_8", "base64"), "hex"))
    }
}
