package com.arkiv.player.data.plugin

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature VERIFICATION only (RFC 8032 §5.1.7), in plain Kotlin: the platform has Ed25519
 * only from API 33 (minSdk is 24) and the native mbedTLS has none. Everything it touches is public
 * (key, message, signature), so it needs no constant-time arithmetic. It is used at install/update
 * time only (author-signed plugins, [SignedEntry]), never on a plugin runtime's load path.
 *
 * Strict where it matters: a signature whose `S` is not below the group order is refused (no
 * malleability), and so is a key or `R` that is not a valid point encoding. The check is
 * RFC 8032's cofactorless `[S]B == R + [k]A`.
 */
object Ed25519 {
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val TWO = BigInteger.valueOf(2)
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2: BigInteger = D.multiply(TWO).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val P38: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

    /** A point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0) ?: error("Ed25519 base point")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        val a = decode(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decode(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = littleEndian(
            MessageDigest.getInstance("SHA-512").run { update(rBytes); update(publicKey); update(message); digest() },
        ).mod(L)
        return equal(mul(s, BASE), add(r, mul(k, a)))
    }

    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(scalar: BigInteger, point: Point): Point {
        var q = IDENTITY
        for (i in scalar.bitLength() - 1 downTo 0) {
            q = add(q, q)
            if (scalar.testBit(i)) q = add(q, point)
        }
        return q
    }

    private fun equal(p: Point, q: Point): Boolean =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y)
        val x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow(P38, P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun decode(bytes: ByteArray): Point? {
        val v = littleEndian(bytes)
        val sign = if (v.testBit(255)) 1 else 0
        val y = v.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
}
