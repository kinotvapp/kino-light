package com.arkiv.player.data.plugin

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A `kino.crypto` failure; the prelude rethrows it with `code: "crypto_error"`. */
class PluginCryptoException(message: String) : Exception(message)

/**
 * `kino.crypto`: hashes, HMAC, AES/3DES, PBKDF2, random bytes and UUIDs over `javax.crypto` and
 * `MessageDigest`. Pure and synchronous: it runs on the plugin's runtime thread, bounded by
 * [MAX_DATA_BYTES] per input and [PBKDF2_MAX_ITERATIONS]; the call timeout and the crash sentinel
 * are the backstop for anything pathological (same as a plugin's own infinite loop).
 *
 * Strings cross with explicit encodings: `utf8`, `hex`, `base64`. MD5, SHA-1 and DES exist because
 * real sites use them; the sandbox doesn't depend on a plugin's cryptography being strong.
 */
object PluginCrypto {
    const val MAX_DATA_BYTES = 5 * 1024 * 1024
    const val PBKDF2_MAX_ITERATIONS = 100_000
    const val PBKDF2_MAX_KEY_BYTES = 64
    const val RANDOM_MAX_BYTES = 1024
    const val ERROR_CODE = "crypto_error"
    val HASHES = listOf("md5", "sha1", "sha256", "sha512")
    val PBKDF2_HASHES = listOf("sha1", "sha256", "sha512")
    val ENCODINGS = listOf("utf8", "hex", "base64")
    val CIPHERS = listOf(
        "aes-128-cbc", "aes-192-cbc", "aes-256-cbc", "aes-128-ecb", "aes-192-ecb", "aes-256-ecb",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
        "des-ede3-cbc", "des-ede3-ecb",
    )

    private val random = SecureRandom()
    private val HEX = "0123456789abcdef".toCharArray()
    private val DIGESTS = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
    private val MACS = mapOf("md5" to "HmacMD5", "sha1" to "HmacSHA1", "sha256" to "HmacSHA256", "sha512" to "HmacSHA512")

    /**
     * The binding's entry point: [opJson] is what the prelude built, the answer is
     * `{"ok": "<string>"}` or `{"error": "<short Spanish message>"}` — never a thrown exception.
     */
    fun run(opJson: String): String = try {
        JSONObject().put("ok", dispatch(JSONObject(opJson))).toString()
    } catch (e: PluginCryptoException) {
        JSONObject().put("error", e.message.orEmpty().take(200)).toString()
    } catch (e: Exception) {
        // javax.crypto's own messages are English and can be long: a fixed short text instead.
        JSONObject().put("error", "operación criptográfica inválida").toString()
    }

    private fun dispatch(o: JSONObject): String = when (o.optString("op")) {
        "hash" -> hash(o.getString("alg"), bytes(o, "data", "in"), o.optString("out", "hex"))
        "hmac" -> hmac(o.getString("alg"), bytes(o, "key", "keyEnc"), bytes(o, "data", "in"), o.optString("out", "hex"))
        "encrypt" -> cipher(Cipher.ENCRYPT_MODE, o)
        "decrypt" -> cipher(Cipher.DECRYPT_MODE, o)
        "pbkdf2" -> pbkdf2(o)
        "random" -> randomBytes(boundedInt(o, "n", 1, RANDOM_MAX_BYTES, "randomBytes acepta de 1 a $RANDOM_MAX_BYTES bytes"), o.optString("out", "hex"))
        "uuid" -> UUID.randomUUID().toString()
        else -> throw PluginCryptoException("operación desconocida")
    }

    fun hash(alg: String, data: ByteArray, out: String): String {
        val name = DIGESTS[alg] ?: throw PluginCryptoException("algoritmo de hash desconocido: ${alg.take(20)}")
        return encode(MessageDigest.getInstance(name).digest(data), out)
    }

    fun hmac(alg: String, key: ByteArray, data: ByteArray, out: String): String {
        val name = MACS[alg] ?: throw PluginCryptoException("algoritmo de hmac desconocido: ${alg.take(20)}")
        val mac = Mac.getInstance(name)
        // Node's createHmac(alg, '') accepts an empty key; SecretKeySpec refuses a zero-length one.
        // A single zero byte is the same HMAC key an empty one becomes once padded to block size.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, name))
        return encode(mac.doFinal(data), out)
    }

    /**
     * Reads [field] as a JSON number without Kotlin's `Int` wraparound: `JSONObject.getInt` on a
     * value outside `Int` range truncates silently (4294967297 became 1, passing the "in range"
     * check with a value the plugin never sent). Read as a `Long` and range-check before narrowing.
     */
    private fun boundedInt(o: JSONObject, field: String, min: Int, max: Int, message: String): Int {
        val v = o.getLong(field)
        if (v < min || v > max) throw PluginCryptoException(message)
        return v.toInt()
    }

    private fun cipher(mode: Int, o: JSONObject): String {
        val alg = o.getString("alg")
        if (alg !in CIPHERS) throw PluginCryptoException("cifrado desconocido: ${alg.take(20)}")
        val key = bytes(o, "key", "keyEnc")
        // Ciphertext usually travels as base64 (what encrypt gives by default); plaintext as text.
        val data = bytes(o, "data", "in", if (mode == Cipher.DECRYPT_MODE) "base64" else "utf8")
        val padding = o.optString("padding", "pkcs7")
        if (padding != "pkcs7" && padding != "none") throw PluginCryptoException("relleno desconocido: ${padding.take(20)}")
        val (family, bits, blockMode) = alg.split('-').let { p -> if (p[0] == "des") Triple("des", 192, p[2]) else Triple("aes", p[1].toInt(), p[2]) }
        if (key.size * 8 != bits) throw PluginCryptoException("la clave de $alg debe tener ${bits / 8} bytes, tiene ${key.size}")
        val blockSize = if (family == "aes") 16 else 8
        val jcaAlg = if (family == "aes") "AES" else "DESede"
        val keySpec = SecretKeySpec(key, jcaAlg)
        val c: Cipher
        when (blockMode) {
            "gcm" -> {
                val iv = bytes(o, "iv", "ivEnc")
                if (iv.isEmpty()) throw PluginCryptoException("$alg necesita un iv")
                if (mode == Cipher.DECRYPT_MODE && data.size < 16) throw PluginCryptoException("al texto cifrado le falta la etiqueta de 16 bytes")
                c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(mode, keySpec, GCMParameterSpec(128, iv))
                if (o.has("aad")) c.updateAAD(bytes(o, "aad", "aadEnc"))
            }
            "ctr" -> {
                val iv = bytes(o, "iv", "ivEnc")
                if (iv.size != 16) throw PluginCryptoException("el iv de $alg debe tener 16 bytes, tiene ${iv.size}")
                c = Cipher.getInstance("AES/CTR/NoPadding")
                c.init(mode, keySpec, IvParameterSpec(iv))
            }
            "cbc" -> {
                val iv = bytes(o, "iv", "ivEnc")
                if (iv.size != blockSize) throw PluginCryptoException("el iv de $alg debe tener $blockSize bytes, tiene ${iv.size}")
                c = Cipher.getInstance("$jcaAlg/CBC/${jcaPadding(padding)}")
                c.init(mode, keySpec, IvParameterSpec(iv))
            }
            else -> { // ecb
                c = Cipher.getInstance("$jcaAlg/ECB/${jcaPadding(padding)}")
                c.init(mode, keySpec)
            }
        }
        if (padding == "none" && blockMode in setOf("cbc", "ecb") && data.size % blockSize != 0) {
            throw PluginCryptoException("sin relleno, los datos deben medir un múltiplo de $blockSize bytes")
        }
        val result = try {
            c.doFinal(data)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw PluginCryptoException("la etiqueta GCM no coincide: clave, iv o datos equivocados")
        } catch (e: javax.crypto.BadPaddingException) {
            throw PluginCryptoException("no se pudo descifrar: clave o iv equivocados")
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            throw PluginCryptoException("los datos cifrados no miden un múltiplo de $blockSize bytes")
        }
        val defaultOut = if (mode == Cipher.ENCRYPT_MODE) "base64" else "utf8"
        return encode(result, o.optString("out", defaultOut))
    }

    /** PKCS#7 is what JCA calls PKCS5Padding for 8- and 16-byte blocks. */
    private fun jcaPadding(p: String) = if (p == "none") "NoPadding" else "PKCS5Padding"

    private fun pbkdf2(o: JSONObject): String {
        val hash = o.getString("hash")
        if (hash !in PBKDF2_HASHES) throw PluginCryptoException("hash de pbkdf2 desconocido: ${hash.take(20)}")
        val iterations = boundedInt(o, "iterations", 1, PBKDF2_MAX_ITERATIONS, "iteraciones de pbkdf2 entre 1 y $PBKDF2_MAX_ITERATIONS")
        val length = boundedInt(o, "keyLength", 1, PBKDF2_MAX_KEY_BYTES, "longitud de clave de pbkdf2 entre 1 y $PBKDF2_MAX_KEY_BYTES bytes")
        val password = bytes(o, "password", "keyEnc")
        val salt = bytes(o, "salt", "in")
        return encode(pbkdf2(MACS.getValue(hash), password, salt, iterations, length), o.optString("out", "hex"))
    }

    /**
     * RFC 8018 PBKDF2 over [Mac] directly: JCA's `PBEKeySpec` takes a `char[]` password, and the
     * JDK and Android providers turn it back into bytes differently, so a password given as hex or
     * base64 bytes would not survive it.
     */
    fun pbkdf2(macName: String, password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance(macName)
        // An empty key is legal in PBKDF2 but SecretKeySpec refuses it; a single zero byte is the
        // same HMAC key (HMAC pads keys with zeros to the block size).
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, macName))
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }

    fun randomBytes(n: Int, out: String): String {
        if (n !in 1..RANDOM_MAX_BYTES) throw PluginCryptoException("randomBytes acepta de 1 a $RANDOM_MAX_BYTES bytes")
        return encode(ByteArray(n).also(random::nextBytes), out)
    }

    private fun bytes(o: JSONObject, field: String, encodingField: String, defaultEncoding: String = "utf8"): ByteArray {
        val value = o.opt(field) as? String ?: throw PluginCryptoException("falta \"$field\"")
        val bytes = decode(value, o.optString(encodingField, defaultEncoding), field)
        if (bytes.size > MAX_DATA_BYTES) throw PluginCryptoException("\"$field\" pasa de 5 MB")
        return bytes
    }

    fun decode(value: String, encoding: String, field: String = "data"): ByteArray = when (encoding) {
        "utf8" -> value.toByteArray(Charsets.UTF_8)
        "hex" -> {
            if (value.length % 2 != 0 || !value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw PluginCryptoException("\"$field\" no es hexadecimal válido")
            }
            ByteArray(value.length / 2) { i -> ((Character.digit(value[i * 2], 16) shl 4) or Character.digit(value[i * 2 + 1], 16)).toByte() }
        }
        "base64" -> try {
            // Standard or URL-safe alphabet, padding optional, whitespace ignored; anything else fails.
            val clean = value.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }.replace('-', '+').replace('_', '/')
            Base64.getDecoder().decode(clean.padEnd((clean.length + 3) / 4 * 4, '='))
        } catch (e: IllegalArgumentException) {
            throw PluginCryptoException("\"$field\" no es base64 válido")
        }
        else -> throw PluginCryptoException("codificación desconocida: ${encoding.take(20)}")
    }

    fun encode(bytes: ByteArray, encoding: String): String = when (encoding) {
        "hex" -> {
            val chars = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b -> chars[i * 2] = HEX[(b.toInt() shr 4) and 15]; chars[i * 2 + 1] = HEX[b.toInt() and 15] }
            String(chars)
        }
        "base64" -> Base64.getEncoder().encodeToString(bytes)
        "utf8" -> String(bytes, Charsets.UTF_8)
        else -> throw PluginCryptoException("codificación desconocida: ${encoding.take(20)}")
    }
}
