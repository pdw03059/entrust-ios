// 검표(관리자 앱): 아이폰 Shared/GateVerify.swift · 웹 live-core.js(readLockQr · verifyEntryQr)와 같은 검사.
// 예매처 등록(ES1) → 표 등록(EL2, 예매처 서명 · 다른 폰 중복 잠금) → 입장 QR(EQ2: 10초 · 지문 확인 30초 · 서명 · 입장 시간 · 이미 입장)
package kr.co.synapseai.entrust.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable data class ShopReg(val id: String, val name: String, val pk: String)
@Serializable data class GateLock(val terms: Terms, val pk: String, val rp: String, val org: String, val shopName: String)
@Serializable data class EnteredRec(val at: Long)

enum class GateCode(val message: String) {
    MALFORMED("읽을 수 없는 QR이에요"),
    UNKNOWN_TICKET("이 검표기에 등록되지 않은 표예요"),
    EXPIRED("QR이 10초를 넘었어요(캡처 · 사진은 통하지 않아요)"),
    FUTURE("폰 시계가 너무 빨라요"),
    BIO_STALE("지문 · 얼굴 확인이 30초를 넘었어요 — 관객 폰에서 다시 열어 주세요"),
    BAD_ORIGIN("다른 앱 · 사이트에서 만든 QR이에요"),
    NO_UV("지문 · 얼굴 확인 없이 만든 QR이에요"),
    BAD_BIO("폰 잠금 열쇠 서명이 맞지 않아요"),
    BAD_SIG("QR 서명이 맞지 않아요(내용이 바뀜)"),
    ALREADY_ENTERED("이미 입장한 표예요"),
    WRONG_GATE("담당 입구가 아니에요"),
    NOT_YET("아직 입장 시간이 아니에요"),
    CLOSED("입장 시간이 끝났어요"),
    UNKNOWN_SHOP("등록되지 않은 예매처의 표예요"),
    BAD_SHOP_SIG("예매처 서명이 맞지 않아요(표 조건이 바뀜)"),
    LOCKED_ELSEWHERE("이미 다른 폰에 잠긴 표예요"),
    CANCELLED("예매처에서 취소된 표예요"),
}

/** 성공(value) 또는 실패(code) */
sealed class GateResult<out T> {
    data class Ok<T>(val value: T) : GateResult<T>()
    data class Fail(val code: GateCode) : GateResult<Nothing>()
}

data class GatePass(val lock: GateLock, val synced: Boolean)

private class ByteReader(val b: ByteArray) {
    var i = 0
    fun take(n: Int): ByteArray? { if (n < 0 || i + n > b.size) return null; val o = b.copyOfRange(i, i + n); i += n; return o }
    fun u8(): Int? = take(1)?.let { it[0].toInt() and 0xFF }
    fun u16(): Int? = take(2)?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
    fun u32(): Long? = take(4)?.let {
        ((it[0].toLong() and 0xFF) shl 24) or ((it[1].toLong() and 0xFF) shl 16) or ((it[2].toLong() and 0xFF) shl 8) or (it[3].toLong() and 0xFF)
    }
}

object Gate {
    const val FUTURE_SKEW = 5

    private fun obj(text: String, prefix: String, max: Int): JsonObject? {
        if (!text.startsWith(prefix) || text.length > max) return null
        val d = B45.decode(text.substring(4)) ?: return null
        return runCatching { json.parseToJsonElement(String(d, Charsets.UTF_8)) as JsonObject }.getOrNull()
    }

    fun parseShopQr(text: String): ShopReg? {
        val o = obj(text, "ES1.", 600) ?: return null
        if (o.intOrNull("v") != 1) return null
        val pk = o.str("pk") ?: return null
        val raw = B64U.decode(pk) ?: return null
        if (EcP256.publicFromRaw(raw) == null) return null
        val name = (o.str("name") ?: "예매처").let { if (it.length > 40) it.substring(0, 40) else it }
        return ShopReg(Entrust.shopId(raw), name, pk)
    }

    fun readLockQr(text: String, shops: Map<String, ShopReg>, locks: Map<String, GateLock>): GateResult<GateLock> {
        val o = obj(text, "EL2.", 4000) ?: return GateResult.Fail(GateCode.MALFORMED)
        val t = o.str("t"); val s = o.str("s"); val pk = o.str("pk"); val org = o.str("org"); val rp = o.str("rp")
        if (o.intOrNull("v") != 2 || t == null || s == null || pk == null || org == null || rp == null || B64U.decode(pk) == null)
            return GateResult.Fail(GateCode.MALFORMED)
        val terms = Entrust.parseTerms(t) ?: return GateResult.Fail(GateCode.MALFORMED)
        val shop = shops[terms.shop] ?: return GateResult.Fail(GateCode.UNKNOWN_SHOP)
        if (!Entrust.verifyShopSig(t, s, shop.pk)) return GateResult.Fail(GateCode.BAD_SHOP_SIG)
        val prev = locks[terms.ck]
        if (prev != null && prev.pk != pk) return GateResult.Fail(GateCode.LOCKED_ELSEWHERE) // 먼저 잠근 폰만 인정
        return GateResult.Ok(GateLock(terms, pk, rp, org, shop.name))
    }

    /** 예매 취소 알림(EC1) 읽기: 등록된 예매처 서명이 맞으면 취소된 표 번호 */
    fun readCancel(text: String, shops: Map<String, ShopReg>): GateResult<String> {
        val o = obj(text, "EC1.", 600) ?: return GateResult.Fail(GateCode.MALFORMED)
        val ck = o.str("ck"); val at = o.longOrNull("at"); val shopId = o.str("shop"); val s = o.str("s")
        if (o.intOrNull("v") != 1 || ck == null || at == null || shopId == null || s == null) return GateResult.Fail(GateCode.MALFORMED)
        val shop = shops[shopId] ?: return GateResult.Fail(GateCode.UNKNOWN_SHOP)
        val kRaw = B64U.decode(shop.pk); val sRaw = B64U.decode(s)
        if (kRaw == null || sRaw == null || !EcP256.verifyRaw(kRaw, sRaw, "${Entrust.CANCEL_DOMAIN}$ck|$at".toByteArray(Charsets.UTF_8)))
            return GateResult.Fail(GateCode.BAD_SHOP_SIG)
        return GateResult.Ok(ck)
    }

    fun verifyEntry(
        text: String, locks: Map<String, GateLock>, entered: Map<String, EnteredRec>, cancelled: Set<String> = emptySet(),
        gate: String, now: Long = Entrust.now(),
    ): GateResult<GatePass> {
        fun fail(c: GateCode) = GateResult.Fail(c)
        if (!text.startsWith("EQ2.") || text.length > 4000) return fail(GateCode.MALFORMED)
        val data = B45.decode(text.substring(4)) ?: return fail(GateCode.MALFORMED)
        val r = ByteReader(data)
        if (r.u8() != 2) return fail(GateCode.MALFORMED)
        val ckLen = r.u8() ?: return fail(GateCode.MALFORMED)
        val ckB = r.take(ckLen) ?: return fail(GateCode.MALFORMED)
        val ts = r.u32() ?: return fail(GateCode.MALFORMED)
        val dt = r.u8() ?: return fail(GateCode.MALFORMED)
        val nB = r.take(9) ?: return fail(GateCode.MALFORMED)
        val sxB = r.take(65) ?: return fail(GateCode.MALFORMED)
        val adLen = r.u8() ?: return fail(GateCode.MALFORMED)
        val adB = r.take(adLen) ?: return fail(GateCode.MALFORMED)
        val cdLen = r.u16() ?: return fail(GateCode.MALFORMED)
        val cdB = r.take(cdLen) ?: return fail(GateCode.MALFORMED)
        val bsLen = r.u8() ?: return fail(GateCode.MALFORMED)
        val bsB = r.take(bsLen) ?: return fail(GateCode.MALFORMED)
        val qsB = r.take(64) ?: return fail(GateCode.MALFORMED)
        if (r.i != data.size) return fail(GateCode.MALFORMED)

        val ck = String(ckB, Charsets.UTF_8)
        val bts = ts - dt
        val lock = locks[ck] ?: return fail(GateCode.UNKNOWN_TICKET)
        if (ts > now + FUTURE_SKEW) return fail(GateCode.FUTURE)
        if (now - ts > Entrust.ENTRY_TTL) return fail(GateCode.EXPIRED)
        if (bts > ts + 1 || ts - bts > Entrust.BIO_TTL) return fail(GateCode.BIO_STALE)

        // 1) 지문 · 얼굴 확인 승인(웹 WebAuthn · 아이폰 보안 칩 · 안드로이드 키스토어 같은 모양)
        val sx = B64U.encode(sxB)
        val cdObj = runCatching { json.parseToJsonElement(String(cdB, Charsets.UTF_8)) as JsonObject }.getOrNull()
        if (cdObj == null || cdObj.str("type") != "webauthn.get") return fail(GateCode.BAD_BIO)
        val crossOrigin = (cdObj["crossOrigin"] as? JsonPrimitive)?.booleanOrNull
        if (cdObj.str("origin") != lock.org || crossOrigin == true) return fail(GateCode.BAD_ORIGIN)
        val chal = B64U.encode(EcP256.sha256("ENTRUST-live-bio|v1|$ck|$sx|$bts".toByteArray(Charsets.UTF_8)))
        if (cdObj.str("challenge") != chal) return fail(GateCode.BAD_BIO)
        if (adB.size < 37 || !adB.copyOfRange(0, 32).contentEquals(EcP256.sha256(lock.rp.toByteArray(Charsets.UTF_8)))) return fail(GateCode.BAD_ORIGIN)
        val flags = adB[32].toInt() and 0xFF
        if (flags and 0x01 == 0 || flags and 0x04 == 0) return fail(GateCode.NO_UV)
        val devKey = B64U.decode(lock.pk)?.let { EcP256.publicFromSpki(it) } ?: return fail(GateCode.BAD_BIO)
        if (!EcP256.verifyDer(devKey, bsB, adB + EcP256.sha256(cdB))) return fail(GateCode.BAD_BIO)

        // 2) 세션 열쇠 서명(10초마다 새 QR)
        val n = B64U.encode(nB)
        val input = "ENTRUST-live-qr|v1|$ck|$ts|$n|$sx|$bts"
        if (!EcP256.verifyRaw(sxB, qsB, input.toByteArray(Charsets.UTF_8))) return fail(GateCode.BAD_SIG)

        if (ck in cancelled) return fail(GateCode.CANCELLED)
        if (lock.terms.gate != gate) return fail(GateCode.WRONG_GATE)
        if (now < lock.terms.from - FUTURE_SKEW) return fail(GateCode.NOT_YET)
        if (now > lock.terms.to) return fail(GateCode.CLOSED)
        if (entered[ck] != null) return fail(GateCode.ALREADY_ENTERED)
        return GateResult.Ok(GatePass(lock, synced = flags and 0x10 != 0))
    }
}
