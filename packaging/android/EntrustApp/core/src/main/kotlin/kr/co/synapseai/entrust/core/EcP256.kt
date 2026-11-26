// P-256(secp256r1) 서명 도구 — 자바 표준 암호(java.security)만 씀. 안드로이드 · JVM 공통.
// 형식: 공개 열쇠 65바이트(04‖X‖Y) · 서명 raw 64바이트(r‖s) 또는 DER — 아이폰 CryptoKit 과 같은 표현.
package kr.co.synapseai.entrust.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object EcP256 {
    val SPKI_PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )

    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rng) }.generateKeyPair()

    private fun fixed32(b: BigInteger): ByteArray {
        val a = b.toByteArray()
        return when {
            a.size == 32 -> a
            a.size > 32 -> a.copyOfRange(a.size - 32, a.size)
            else -> ByteArray(32 - a.size) + a
        }
    }

    /** 공개 열쇠 → 65바이트(04‖X‖Y) */
    fun raw65(k: PublicKey): ByteArray {
        val w = (k as ECPublicKey).w
        return byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    /** 65바이트 → 공개 열쇠(곡선 위의 점인지도 확인). 틀리면 null */
    fun publicFromRaw(raw: ByteArray): PublicKey? {
        if (raw.size != 65 || raw[0].toInt() != 4) return null
        return runCatching {
            val x = BigInteger(1, raw.copyOfRange(1, 33)); val y = BigInteger(1, raw.copyOfRange(33, 65))
            if (!onCurve(x, y)) return null
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        }.getOrNull()
    }

    /** SPKI(DER) → 공개 열쇠 */
    fun publicFromSpki(der: ByteArray): PublicKey? = runCatching {
        val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
        if (k.params.curve != params.curve || !onCurve(k.w.affineX, k.w.affineY)) null else k
    }.getOrNull()

    fun privateFromPkcs8(der: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))

    private fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        val c = params.curve
        val p = (c.field as java.security.spec.ECFieldFp).p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(c.a.multiply(x)).add(c.b).mod(p)
        return lhs == rhs
    }

    fun signDer(k: PrivateKey, msg: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(k, rng); update(msg); sign() }

    fun verifyDer(k: PublicKey, sigDer: ByteArray, msg: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run { initVerify(k); update(msg); verify(sigDer) }
    }.getOrDefault(false)

    fun verifyRaw(pubRaw65: ByteArray, sigRaw: ByteArray, msg: ByteArray): Boolean {
        val k = publicFromRaw(pubRaw65) ?: return false
        val der = rawToDer(sigRaw) ?: return false
        return verifyDer(k, der, msg)
    }

    /** DER 서명 → raw 64바이트(r‖s) */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        fun len(): Int {
            var l = der[i++].toInt() and 0xFF
            if (l and 0x80 != 0) { val n = l and 0x7F; l = 0; repeat(n) { l = (l shl 8) or (der[i++].toInt() and 0xFF) } }
            return l
        }
        require(der[i++].toInt() == 0x30) { "DER" }
        len()
        require(der[i++].toInt() == 0x02) { "DER r" }
        val rl = len(); val r = BigInteger(1, der.copyOfRange(i, i + rl)); i += rl
        require(der[i++].toInt() == 0x02) { "DER s" }
        val sl = len(); val s = BigInteger(1, der.copyOfRange(i, i + sl))
        return fixed32(r) + fixed32(s)
    }

    /** raw 64바이트(r‖s) → DER 서명. 길이가 틀리면 null */
    fun rawToDer(raw: ByteArray): ByteArray? {
        if (raw.size != 64) return null
        fun intDer(b: ByteArray): ByteArray {
            var v = b.dropWhile { it.toInt() == 0 }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = intDer(raw.copyOfRange(0, 32)) + intDer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** 소프트웨어 열쇠로 만든 서명자(예매처 체험 열쇠 · 시험용) */
    class SoftSigner(val pair: KeyPair) : Signer {
        override val publicRaw65: ByteArray = raw65(pair.public)
        override fun signDer(message: ByteArray): ByteArray = signDer(pair.private, message)
    }
}
