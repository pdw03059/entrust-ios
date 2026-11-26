// ─────────────────────────────────────────────────────────────────────────────
// ENTRUST 연동 어댑터 — 예매처 앱에서 ENTRUST 를 아는 곳은 이 파일 하나뿐이다.
//
// 실제 고객사(예매처) 앱에 붙일 때:
//   1) 이 파일만 고객사 앱에 넣고, 아래 4개 함수를 고객사의 '예매 완료' · '예매 취소' · '모바일 티켓' 화면에서 부른다.
//   2) 체험판은 서명 열쇠를 폰에 두지만, 실제로는 서명을 고객사 서버가 한다(issueTicket · cancelNotice 를 서버 호출로 바꾸면 끝).
//   3) 표 형식(ET1 · EC1 · ES1)과 넘김 주소(entrust://open#t=…)는 바뀌지 않는다.
// 고객사 앱의 다른 부분(공연 목록 · 좌석 · 결제 · 회원)은 손대지 않는다.
//
// 안드로이드: 체험판 서명 열쇠는 안드로이드 키스토어(AndroidKeyStore) 안에서 만들고 밖으로 꺼낼 수 없다
// (아이폰 체험판은 열쇠 원본을 UserDefaults 에 둠 — 키스토어가 더 안전한 같은 역할).
// 예매처 서명은 사람 확인 없이 하는 일이라 지문 인증은 걸지 않는다.
// ─────────────────────────────────────────────────────────────────────────────
package kr.co.synapseai.entrust.ticket24

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kr.co.synapseai.entrust.core.EcP256
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.EntrustException
import kr.co.synapseai.entrust.core.Signer
import kr.co.synapseai.entrust.core.Terms
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** 고객사 주문 정보 중 ENTRUST 가 필요로 하는 칸만(이름 · 전화번호 같은 개인정보는 받지 않음) */
data class EntrustOrderInput(
    val orderNo: String,        // 고객사 예매번호(24자 이내)
    val showTitle: String,
    val venueName: String,
    val seats: List<String>,    // 예: ["A-3", "A-4"] (최대 3석)
    val gate: String,           // 담당 입구
    val venueLat: Double,       // 공연장 위치 · 반경 · 입장 시간 = ENTRUST 관리자 앱에서 정한 값
    val venueLon: Double,
    val radiusM: Int,
    val entryFrom: Long,        // 유닉스 초
    val entryTo: Long,
)

/** 발급한 표: 표 번호(ck) · 넘김 글자(ET1) */
data class IssuedTicket(val ck: String, val token: String)

object EntrustBridge {
    private const val KEY_STORE = "ticket24.entrustSigningKey.v1"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** 예매처 서명 열쇠(체험판: 폰 안 키스토어. 실제: 고객사 서버) */
    @Synchronized
    fun signingKey(): Signer {
        val ks = keyStore()
        if (!ks.containsAlias(KEY_STORE)) {
            val spec = KeyGenParameterSpec.Builder(KEY_STORE, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }
                .generateKeyPair()
        }
        val cert = ks.getCertificate(KEY_STORE) ?: throw EntrustException("noKey")
        val priv = ks.getKey(KEY_STORE, null) as? PrivateKey ?: throw EntrustException("noKey")
        val raw = EcP256.raw65(cert.publicKey)
        return object : Signer {
            override val publicRaw65: ByteArray = raw
            override fun signDer(message: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(message); sign() }
        }
    }

    @Synchronized
    fun resetKey() {
        runCatching { keyStore().deleteEntry(KEY_STORE) }
    }

    val partnerId: String get() = Entrust.shopId(signingKey().publicRaw65)

    /** ① 예매 완료 → 모바일 티켓 발급: (표 번호, 넘김 글자 ET1) */
    fun issueTicket(o: EntrustOrderInput): IssuedTicket {
        val key = signingKey()
        val ck = "tk_" + Entrust.randomB64(16)
        val terms = Terms(
            ck = ck, no = o.orderNo.prefixChars(24), show = o.showTitle.prefixChars(60), venue = o.venueName.prefixChars(40),
            seat = o.seats.joinToString(",").prefixChars(20), gate = o.gate, qty = o.seats.size.coerceIn(1, 3),
            lat = o.venueLat, lon = o.venueLon, r = o.radiusM.toDouble(), from = o.entryFrom, to = o.entryTo,
            shop = Entrust.shopId(key.publicRaw65),
        )
        if (!Entrust.validTerms(terms)) throw EntrustException("malformed")
        return IssuedTicket(ck, Entrust.signTicket(terms, key))
    }

    /** ② 'ENTRUST 로 표 받기' 버튼이 여는 주소(ENTRUST 앱이 없으면 열리지 않음 → 고객사는 앱 설치 안내) */
    fun handoffURL(token: String): String = "entrust://open#t=" + Entrust.urlValue(token)

    /** ③ 예매 취소 → 검표기에 '취소된 표' 알림(EC1). 체험판은 같은 폰의 관리자 앱으로 넘긴다. */
    fun cancelURL(ck: String): String? {
        val notice = runCatching { Entrust.cancelNotice(ck, signingKey()) }.getOrNull() ?: return null
        return "entrustadmin://cancel#c=" + Entrust.urlValue(notice)
    }

    /** ④ 처음 한 번: 검표기(ENTRUST 관리자)에 이 예매처의 공개 열쇠 등록(ES1) */
    fun registrationCode(): String = Entrust.shopQr("티켓24", signingKey().publicRaw65)
    fun registrationURL(): String = "entrustadmin://shop#k=" + Entrust.urlValue(registrationCode())

    /** 앞에서 n 글자(문자 단위 — 아이폰 String.prefix 처럼 글자 중간을 자르지 않음) */
    private fun String.prefixChars(n: Int): String =
        if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))
}
