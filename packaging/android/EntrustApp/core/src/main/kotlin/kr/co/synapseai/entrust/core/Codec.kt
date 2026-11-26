// 아이폰 Shared/Codec.swift 와 체험 웹(assets/js/live-core.js)과 똑같은 형식:
// base64url · base45 · 예매처 표(ET1) · 표 등록(EL2) · 입장 QR(EQ2) · 예매처 등록(ES1) · 예매 취소(EC1).
// 같은 검표기(아이폰 관리자 앱 · 웹 live-gate.html · 이 안드로이드 관리자 앱)가 서로의 QR을 그대로 검사한다.
package kr.co.synapseai.entrust.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom

internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
internal val rng = SecureRandom()

object B64U {
    private val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    private val dec = java.util.Base64.getUrlDecoder()
    fun encode(d: ByteArray): String = enc.encodeToString(d)
    fun decode(s: String): ByteArray? = try {
        // 웹 · 아이폰과 같이 + / = 가 섞여 와도 받는다
        dec.decode(s.replace('+', '-').replace('/', '_').trimEnd('='))
    } catch (e: IllegalArgumentException) { null }
}

object B45 {
    const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    fun encode(d: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < d.size) {
            if (i + 1 < d.size) {
                var x = (d[i].toInt() and 0xFF) * 256 + (d[i + 1].toInt() and 0xFF)
                val c = x % 45; x /= 45
                val dd = x % 45; val e = x / 45
                sb.append(CHARS[c]).append(CHARS[dd]).append(CHARS[e])
            } else {
                val x = d[i].toInt() and 0xFF
                sb.append(CHARS[x % 45]).append(CHARS[x / 45])
            }
            i += 2
        }
        return sb.toString()
    }
    fun decode(s: String): ByteArray? {
        val idx = IntArray(s.length)
        for ((n, ch) in s.withIndex()) { val k = CHARS.indexOf(ch); if (k < 0) return null; idx[n] = k }
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < idx.size) {
            val rem = idx.size - i
            if (rem >= 3) {
                val x = idx[i] + idx[i + 1] * 45 + idx[i + 2] * 2025
                if (x > 0xFFFF) return null
                out.write(x shr 8); out.write(x and 0xFF); i += 3
            } else if (rem == 2) {
                val x = idx[i] + idx[i + 1] * 45
                if (x > 0xFF) return null
                out.write(x); i += 2
            } else return null
        }
        return out.toByteArray()
    }
}

/** 예매처가 서명한 표 조건(웹 · 아이폰과 같은 칸) */
@Serializable
data class Terms(
    val ck: String, val no: String, val show: String, val venue: String, val seat: String, val gate: String,
    val qty: Int, val lat: Double, val lon: Double, val r: Double, val from: Long, val to: Long, val shop: String,
)

/** 이 폰에 저장하는 표(예매처 원문 t · 서명 s · 예매처 공개 열쇠 k 를 그대로 보관 — 검표기에 그대로 넘김) */
@Serializable
data class StoredTicket(
    val terms: Terms, val t: String, val s: String, val k: String,
    val locked: Boolean = false, val registered: Boolean = false,
)

/** 서명하는 쪽(예매처 열쇠 · 기기 열쇠). sign 은 DER 서명을 돌려준다. */
interface Signer {
    val publicRaw65: ByteArray
    fun signDer(message: ByteArray): ByteArray
}

class EntrustException(val code: String, msg: String = code) : Exception(msg)

object Entrust {
    /** 체험 사이트 주소(검표기가 '어느 사이트 표인가'를 볼 때 쓰는 이름) — 웹 · 아이폰과 같은 값 */
    const val RP = "entrust--pdw03059.synapse-ai.co.kr"
    /** 이 앱이 만든 승인이라는 표시(웹은 https://… 주소, 아이폰은 ios-app:…, 안드로이드는 이 이름) */
    const val ORIGIN = "android-app:kr.co.synapseai.entrust.demo"
    const val SHOP_DOMAIN = "ENTRUST-shop-ticket|v1|"
    const val CANCEL_DOMAIN = "ENTRUST-shop-cancel|v1|"
    const val ENTRY_TTL = 10
    const val BIO_TTL = 30
    const val MAX_ACCURACY = 150.0

    fun now(): Long = System.currentTimeMillis() / 1000

    /** 예매처 표 코드(ET1)를 열고 예매처 서명을 확인한다. 앞뒤에 다른 글자가 붙어 있어도 찾는다. */
    fun openTicket(raw: String): StoredTicket {
        val at = raw.indexOf("ET1.")
        if (at < 0) throw EntrustException("malformed")
        val body = StringBuilder()
        for (ch in raw.substring(at + 4)) { if (B45.CHARS.indexOf(ch) >= 0) body.append(ch) else break }
        var cand = body.toString()
        var data: ByteArray? = null
        repeat(3) {
            if (data == null) {
                val d = B45.decode(cand)
                if (d != null) data = d else if (cand.endsWith(" ")) cand = cand.dropLast(1)
            }
        }
        val obj = data?.let { runCatching { json.parseToJsonElement(String(it, Charsets.UTF_8)) as JsonObject }.getOrNull() }
            ?: throw EntrustException("malformed")
        val t = obj.str("t"); val s = obj.str("s"); val k = obj.str("k")
        if (t == null || s == null || k == null || t.length > 1200) throw EntrustException("malformed")
        val terms = parseTerms(t) ?: throw EntrustException("malformed")
        val kRaw = B64U.decode(k)
        if (kRaw == null || shopId(kRaw) != terms.shop || !verifyShopSig(t, s, k)) throw EntrustException("badShopSig")
        return StoredTicket(terms, t, s, k)
    }

    fun parseTerms(t: String): Terms? =
        runCatching { json.decodeFromString(Terms.serializer(), t) }.getOrNull()?.takeIf { validTerms(it) }

    /** P-256 공개 열쇠(65바이트) → 웹이 읽는 SPKI(DER) 모양 */
    fun spki(raw65: ByteArray): ByteArray = EcP256.SPKI_PREFIX + raw65

    /** 표 등록 QR(EL2): 예매처 원문 · 서명 + 이 폰 기기 열쇠의 공개 열쇠 */
    fun lockQr(tk: StoredTicket, devicePublicRaw65: ByteArray): String {
        val o = buildJsonObject {
            put("v", 2); put("t", tk.t); put("s", tk.s); put("cid", "")
            put("pk", B64U.encode(spki(devicePublicRaw65))); put("rp", RP); put("org", ORIGIN)
        }
        return "EL2." + B45.encode(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** 예매처 공개 열쇠(65바이트) → 짧은 이름(지문 8바이트) — 웹 shopId 와 같음 */
    fun shopId(raw65: ByteArray): String = B64U.encode(EcP256.sha256(raw65).copyOf(8))

    /** 표 조건 범위 검사(웹 checkTerms 와 같은 기준). 글자 수는 아이폰(Character)과 같게 문자 단위로 센다. */
    fun validTerms(t: Terms): Boolean {
        fun len(s: String) = s.codePointCount(0, s.length)
        // 표 번호 · 입구 글자 규칙은 웹 검표(live-core.js CK_RE · GATE_RE)와 같게 — 웹 검표기에서도 통과하도록
        val ckOK = Regex("^[A-Za-z0-9_-]{16,64}$").matches(t.ck)
        val gateOK = Regex("^[A-Za-z0-9-]{1,16}$").matches(t.gate)
        val shopOK = Regex("^[A-Za-z0-9_-]{6,16}$").matches(t.shop)
        val placeOK = t.lat in -90.0..90.0 && t.lon in -180.0..180.0 && t.r in 20.0..5000.0
        val timeOK = t.to > t.from && t.to - t.from <= 7 * 24 * 3600
        val textOK = len(t.show) <= 60 && len(t.venue) <= 40 && len(t.seat) <= 20 && len(t.no) <= 24 
        return ckOK && gateOK && shopOK && placeOK && timeOK && textOK && t.qty in 1..3
    }

    fun verifyShopSig(t: String, s: String, pk: String): Boolean {
        val kRaw = B64U.decode(pk) ?: return false
        val sRaw = B64U.decode(s) ?: return false
        return EcP256.verifyRaw(kRaw, sRaw, (SHOP_DOMAIN + t).toByteArray(Charsets.UTF_8))
    }

    /** 예매처: 표 조건에 서명해 넘김 글자(ET1)를 만든다 */
    fun signTicket(t: Terms, key: Signer): String {
        val o = buildJsonObject {
            put("v", 1); put("ck", t.ck); put("no", t.no); put("show", t.show); put("venue", t.venue); put("seat", t.seat)
            put("gate", t.gate); put("qty", t.qty); put("lat", t.lat); put("lon", t.lon); put("r", t.r.toInt())
            put("from", t.from); put("to", t.to); put("shop", t.shop)
        }
        val tText = o.toString()
        val sig = EcP256.derToRaw(key.signDer((SHOP_DOMAIN + tText).toByteArray(Charsets.UTF_8)))
        val wrap = buildJsonObject { put("t", tText); put("s", B64U.encode(sig)); put("k", B64U.encode(key.publicRaw65)) }
        return "ET1." + B45.encode(wrap.toString().toByteArray(Charsets.UTF_8))
    }

    /** 예매처 등록 QR(ES1) — 검표기가 이 예매처의 표만 받게 함 */
    fun shopQr(name: String, pkRaw65: ByteArray): String {
        val o = buildJsonObject { put("v", 1); put("name", name); put("pk", B64U.encode(pkRaw65)) }
        return "ES1." + B45.encode(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** 예매 취소 알림(EC1): 예매처가 서명 → 검표기가 '취소된 표'로 거절 */
    fun cancelNotice(ck: String, key: Signer): String {
        val at = now()
        val sig = EcP256.derToRaw(key.signDer("$CANCEL_DOMAIN$ck|$at".toByteArray(Charsets.UTF_8)))
        val o = buildJsonObject {
            put("v", 1); put("ck", ck); put("at", at); put("shop", shopId(key.publicRaw65)); put("s", B64U.encode(sig))
        }
        return "EC1." + B45.encode(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** 앱 사이 주소에 넣을 값(영문 · 숫자 말고는 모두 %로 — 아이폰 urlValue 와 같음) */
    fun urlValue(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code)) sb.append(c.toChar())
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }

    /** 앱 주소(예: entrust://open#t=…)에서 값 꺼내기 — #이름=값 또는 ?이름=값 (값은 % 풀어서) */
    fun urlParam(url: String, name: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val frag = u.rawFragment
        if (frag != null && frag.startsWith("$name=")) return pctDecode(frag.substring(name.length + 1))
        val q = u.rawQuery ?: return null
        for (part in q.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && pctDecode(part.substring(0, eq)) == name) return pctDecode(part.substring(eq + 1))
        }
        return null
    }

    private fun pctDecode(s: String): String? = runCatching {
        // + 는 글자 그대로(base45 에 + 가 있음) — URLDecoder 는 + 를 공백으로 바꾸므로 먼저 %2B 로 감싼다
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }.getOrNull()

    fun randomB64(n: Int): String = B64U.encode(ByteArray(n).also { rng.nextBytes(it) })

    @Suppress("unused")
    private fun formEncode(s: String) = URLEncoder.encode(s, "UTF-8")
}

internal fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.intOrNull(k: String): Int? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
internal fun JsonObject.longOrNull(k: String): Long? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** 얼굴 · 지문 확인 1번으로 승인한 '세션 열쇠'(30초). 이 열쇠가 10초마다 새 입장 QR에 서명한다. */
class EntrySession private constructor(
    private val key: java.security.KeyPair,
    val sx: String,
    val bts: Long,
    val ad: ByteArray,
    val cd: ByteArray,
    val bs: ByteArray,
) {
    companion object {
        /** 승인할 메시지(ad ‖ SHA256(cd))를 먼저 만들고, 기기 열쇠 서명(DER)을 받아 세션을 연다. */
        class Pending internal constructor(val key: java.security.KeyPair, val sx: String, val bts: Long, val ad: ByteArray, val cd: ByteArray) {
            /** 기기 열쇠(지문)로 서명할 바이트 */
            val message: ByteArray get() = ad + EcP256.sha256(cd)
            fun finish(bioSigDer: ByteArray) = EntrySession(key, sx, bts, ad, cd, bioSigDer)
        }

        fun begin(ck: String, now: Long = Entrust.now()): Pending {
            val key = EcP256.generate()
            val sx = B64U.encode(EcP256.raw65(key.public))
            val chal = EcP256.sha256("ENTRUST-live-bio|v1|$ck|$sx|$now".toByteArray(Charsets.UTF_8))
            val cdText = "{\"type\":\"webauthn.get\",\"challenge\":\"${B64U.encode(chal)}\",\"origin\":\"${Entrust.ORIGIN}\",\"crossOrigin\":false}"
            val cd = cdText.toByteArray(Charsets.UTF_8)
            // 사용자 있음(UP) + 지문 · 얼굴 확인함(UV), 기기 밖 복사 표시 없음
            val ad = EcP256.sha256(Entrust.RP.toByteArray(Charsets.UTF_8)) + byteArrayOf(0x05, 0, 0, 0, 0)
            return Pending(key, sx, now, ad, cd)
        }

        /** 시험 · 소프트웨어 열쇠용: 한 번에 연다 */
        fun open(ck: String, signer: Signer, now: Long = Entrust.now()): EntrySession {
            val p = begin(ck, now)
            return p.finish(signer.signDer(p.message))
        }
    }

    /** 입장 QR(EQ2) 한 장 */
    fun entryQr(ck: String, now: Long = Entrust.now()): String {
        val nonce = ByteArray(9).also { rng.nextBytes(it) }
        val n = B64U.encode(nonce)
        val input = "ENTRUST-live-qr|v1|$ck|$now|$n|$sx|$bts"
        val qs = EcP256.derToRaw(EcP256.signDer(key.private, input.toByteArray(Charsets.UTF_8)))
        val sxRaw = B64U.decode(sx) ?: throw EntrustException("malformed")
        val ckData = ck.toByteArray(Charsets.UTF_8)
        val dt = now - bts
        if (dt < 0 || dt > 255 || ckData.size > 255 || ad.size > 255 || bs.size > 255 || cd.size > 65535) throw EntrustException("malformed")
        val p = java.io.ByteArrayOutputStream()
        p.write(2)
        p.write(ckData.size); p.write(ckData)
        val ts = now and 0xFFFFFFFFL
        p.write(((ts shr 24) and 0xFF).toInt()); p.write(((ts shr 16) and 0xFF).toInt()); p.write(((ts shr 8) and 0xFF).toInt()); p.write((ts and 0xFF).toInt())
        p.write(dt.toInt())
        p.write(nonce)
        p.write(sxRaw)
        p.write(ad.size); p.write(ad)
        p.write((cd.size shr 8) and 0xFF); p.write(cd.size and 0xFF); p.write(cd)
        p.write(bs.size); p.write(bs)
        p.write(qs)
        return "EQ2." + B45.encode(p.toByteArray())
    }

    /** 세션(지문 확인)이 아직 유효한가 */
    fun alive(now: Long = Entrust.now()) = now - bts in 0..Entrust.BIO_TTL.toLong()
}

/** 관리자 앱이 정하는 공연(장소 · 반경 · 입장 시간 · 입구) — 티콧24로 넘겨 표에 그대로 들어감 */
@Serializable
data class ShowEvent(
    val id: String, val show: String, val venue: String,
    val lat: Double, val lon: Double, val r: Int, val from: Long, val to: Long,
    val gate: String, val price: Int, val category: String? = "콘서트",
) {
    /** 앱 사이 주소에 싣는 글자(base64url JSON) */
    fun encoded(): String = B64U.encode(json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))

    companion object {
        fun demo(): ShowEvent {
            val start = (Entrust.now() / 60) * 60
            return ShowEvent("ev_demo", "체험 공연 · ENTRUST 시연", "서울시청 앞(기본 위치)", 37.5665, 126.978, 200, start, start + 3 * 3600, "1", 55000)
        }

        fun decode(s: String): ShowEvent? {
            val d = B64U.decode(s) ?: return null
            val e = runCatching { json.decodeFromString(serializer(), String(d, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (e.r < 20 || e.r > 5000 || e.to <= e.from || e.to - e.from > 7 * 24 * 3600 || e.id.isEmpty()) return null
            return e
        }
    }
}

@Suppress("unused") private fun JsonObject.intField(k: String) = this[k]?.jsonPrimitive?.int
