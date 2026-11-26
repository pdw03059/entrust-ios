package kr.co.synapseai.entrust.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 웹 검표 코드(live-core.js)와의 호환 시험용 자료.
 * ENTRUST_VECTORS_OUT 이 있으면 안드로이드 core 가 만든 QR 들을 파일로 쓰고(→ node 가 웹 코드로 검사),
 * ENTRUST_WEB_ET1 이 있으면 웹이 만든 예매처 표(ET1)를 안드로이드 core 로 연다.
 */
class WebVectorsTest {
    @Test fun writeVectors() {
        val out = System.getenv("ENTRUST_VECTORS_OUT") ?: return
        val shop = EcP256.SoftSigner(EcP256.generate())
        val device = EcP256.SoftSigner(EcP256.generate())
        val now = Entrust.now()
        val t = Terms("tk_" + Entrust.randomB64(16), "T24-0001", "체험 공연 · ENTRUST 시연", "서울보증보험 본사", "A-3,A-4", "1", 2,
            37.5714, 127.0017, 200.0, now - 60, now + 3600, Entrust.shopId(shop.publicRaw65))
        val et1 = Entrust.signTicket(t, shop)
        val tk = Entrust.openTicket(et1)
        val el2 = Entrust.lockQr(tk, device.publicRaw65)
        val eq2 = EntrySession.open(t.ck, device, now - 2).entryQr(t.ck, now)
        val o = buildJsonObject {
            put("now", now); put("ck", t.ck); put("es1", Entrust.shopQr("티켓24", shop.publicRaw65)); put("et1", et1); put("el2", el2); put("eq2", eq2)
            put("ec1", Entrust.cancelNotice(t.ck, shop)); put("shopPk", B64U.encode(shop.publicRaw65))
        }
        File(out).writeText(o.toString())
    }

    @Test fun openWebTicket() {
        val et1 = System.getenv("ENTRUST_WEB_ET1") ?: return
        val tk = Entrust.openTicket(et1)
        val reg = Gate.parseShopQr(Entrust.shopQr("웹 예매처", B64U.decode(tk.k)!!))!!
        assertEquals(reg.id, tk.terms.shop)
        val device = EcP256.SoftSigner(EcP256.generate())
        val r = Gate.readLockQr(Entrust.lockQr(tk, device.publicRaw65), mapOf(reg.id to reg), emptyMap())
        assertTrue(r is GateResult.Ok, "$r")
        println("WEB_ET1_OK ${tk.terms.ck}")
    }
}
