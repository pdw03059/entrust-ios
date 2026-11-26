package kr.co.synapseai.entrust.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val shop = EcP256.SoftSigner(EcP256.generate())
    private val device = EcP256.SoftSigner(EcP256.generate())
    private val now = Entrust.now()

    private fun terms(ck: String = "tk_" + Entrust.randomB64(16), gate: String = "1", from: Long = now - 60, to: Long = now + 3600) =
        Terms(ck, "T24-0001", "체험 공연 · ENTRUST 시연", "서울보증보험 본사", "A-3,A-4", gate, 2, 37.5714, 127.0017, 200.0, from, to, Entrust.shopId(shop.publicRaw65))

    private fun shopMap() = Gate.parseShopQr(Entrust.shopQr("티콧24", shop.publicRaw65))!!.let { mapOf(it.id to it) }

    @Test fun base45RoundTrip() {
        for (n in 0..40) {
            val d = ByteArray(n) { (it * 37 + n).toByte() }
            assertTrue(d.contentEquals(B45.decode(B45.encode(d))))
        }
        assertEquals("BB8", B45.encode("AB".toByteArray())) // RFC 9285 예시
        assertEquals("%69 VD92EX0", B45.encode("Hello!!".toByteArray()))
        assertNull(B45.decode("GGW")) // 65535 초과
    }

    @Test fun derRawRoundTrip() {
        repeat(30) {
            val der = EcP256.signDer(shop.pair.private, "m$it".toByteArray())
            val raw = EcP256.derToRaw(der)
            assertEquals(64, raw.size)
            assertTrue(EcP256.verifyRaw(shop.publicRaw65, raw, "m$it".toByteArray()))
        }
    }

    @Test fun spkiMatchesJavaEncoding() {
        assertTrue(Entrust.spki(device.publicRaw65).contentEquals(device.pair.public.encoded))
    }

    @Test fun fullFlowPasses() {
        val t = terms()
        val et1 = Entrust.signTicket(t, shop)
        val tk = Entrust.openTicket("받은 글자: $et1 (끝)")
        assertEquals(t, tk.terms)
        val shops = shopMap()
        val lock = (Gate.readLockQr(Entrust.lockQr(tk, device.publicRaw65), shops, emptyMap()) as GateResult.Ok).value
        val locks = mapOf(t.ck to lock)
        val sess = EntrySession.open(t.ck, device, now - 3)
        val qr = sess.entryQr(t.ck, now)
        val r = Gate.verifyEntry(qr, locks, emptyMap(), gate = "1", now = now)
        assertTrue(r is GateResult.Ok, "$r")
        // 같은 QR 두 번째: 이미 입장
        assertEquals(GateResult.Fail(GateCode.ALREADY_ENTERED), Gate.verifyEntry(qr, locks, mapOf(t.ck to EnteredRec(now)), gate = "1", now = now))
        assertEquals(GateResult.Fail(GateCode.EXPIRED), Gate.verifyEntry(qr, locks, emptyMap(), gate = "1", now = now + 11))
        assertEquals(GateResult.Fail(GateCode.WRONG_GATE), Gate.verifyEntry(qr, locks, emptyMap(), gate = "2", now = now))
        assertEquals(GateResult.Fail(GateCode.UNKNOWN_TICKET), Gate.verifyEntry(qr, emptyMap(), emptyMap(), gate = "1", now = now))
        assertEquals(GateResult.Fail(GateCode.CANCELLED), Gate.verifyEntry(qr, locks, emptyMap(), setOf(t.ck), "1", now))
    }

    @Test fun staleBioRejected() {
        val t = terms()
        val lock = (Gate.readLockQr(Entrust.lockQr(Entrust.openTicket(Entrust.signTicket(t, shop)), device.publicRaw65), shopMap(), emptyMap()) as GateResult.Ok).value
        val sess = EntrySession.open(t.ck, device, now - 40)
        assertEquals(GateResult.Fail(GateCode.BIO_STALE), Gate.verifyEntry(sess.entryQr(t.ck, now), mapOf(t.ck to lock), emptyMap(), gate = "1", now = now))
    }

    @Test fun otherDeviceKeyRejected() {
        val t = terms()
        val tk = Entrust.openTicket(Entrust.signTicket(t, shop))
        val lock = (Gate.readLockQr(Entrust.lockQr(tk, device.publicRaw65), shopMap(), emptyMap()) as GateResult.Ok).value
        val thief = EcP256.SoftSigner(EcP256.generate())
        val qr = EntrySession.open(t.ck, thief, now).entryQr(t.ck, now)
        assertEquals(GateResult.Fail(GateCode.BAD_BIO), Gate.verifyEntry(qr, mapOf(t.ck to lock), emptyMap(), gate = "1", now = now))
        // 다른 폰이 같은 표를 다시 잠그려 하면 거절
        assertEquals(GateResult.Fail(GateCode.LOCKED_ELSEWHERE), Gate.readLockQr(Entrust.lockQr(tk, thief.publicRaw65), shopMap(), mapOf(t.ck to lock)))
    }

    @Test fun tamperedQrRejected() {
        val t = terms()
        val lock = (Gate.readLockQr(Entrust.lockQr(Entrust.openTicket(Entrust.signTicket(t, shop)), device.publicRaw65), shopMap(), emptyMap()) as GateResult.Ok).value
        val qr = EntrySession.open(t.ck, device, now).entryQr(t.ck, now)
        val bytes = B45.decode(qr.substring(4))!!
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertEquals(GateResult.Fail(GateCode.BAD_SIG), Gate.verifyEntry("EQ2." + B45.encode(bytes), mapOf(t.ck to lock), emptyMap(), gate = "1", now = now))
    }

    @Test fun shopSignatureEnforced() {
        val t = terms()
        val et1 = Entrust.signTicket(t, shop)
        // 다른 예매처 열쇠로 같은 조건 서명 → 예매처 이름(shop)이 맞지 않아 거절
        val fake = EcP256.SoftSigner(EcP256.generate())
        assertFailsWith<EntrustException> { Entrust.openTicket(Entrust.signTicket(t, fake)) }
        // 미등록 예매처
        assertEquals(GateResult.Fail(GateCode.UNKNOWN_SHOP), Gate.readLockQr(Entrust.lockQr(Entrust.openTicket(et1), device.publicRaw65), emptyMap(), emptyMap()))
        assertFailsWith<EntrustException> { Entrust.openTicket("아무 글자") }
    }

    @Test fun cancelNoticeVerified() {
        val ck = "tk_" + Entrust.randomB64(16)
        val ec1 = Entrust.cancelNotice(ck, shop)
        assertEquals(GateResult.Ok(ck), Gate.readCancel(ec1, shopMap()))
        assertEquals(GateResult.Fail(GateCode.UNKNOWN_SHOP), Gate.readCancel(ec1, emptyMap()))
    }

    @Test fun showEventAndUrl() {
        val e = ShowEvent.demo()
        assertEquals(e, ShowEvent.decode(e.encoded()))
        val et1 = Entrust.signTicket(terms(), shop)
        val url = "entrust://open#t=" + Entrust.urlValue(et1)
        assertEquals(et1, Entrust.urlParam(url, "t"))
        assertEquals("x y+", Entrust.urlParam("ticket24://event?e=x%20y%2B", "e"))
        // 아이폰 JSONEncoder 가 만든 공연(category 없음)도 읽는다
        val swiftJson = """{"id":"ev_1","show":"쇼","venue":"장소","lat":37.5,"lon":127,"r":200,"from":1000,"to":5000,"gate":"1","price":55000}"""
        assertNotNull(ShowEvent.decode(B64U.encode(swiftJson.toByteArray())))
    }

    @Test fun termsValidation() {
        assertTrue(Entrust.validTerms(terms()))
        assertTrue(!Entrust.validTerms(terms().copy(qty = 4)))
        assertTrue(!Entrust.validTerms(terms().copy(r = 10.0)))
        assertTrue(!Entrust.validTerms(terms(from = now, to = now - 1)))
    }

    @Test fun placeCheck() {
        val nowMs = System.currentTimeMillis()
        val fix = Fix(37.5714, 127.0017, 20.0, nowMs, false)
        assertTrue(PlaceCheck.check(37.5714, 127.0017, 200.0, fix, true, nowMs).isOK)
        assertEquals(PlaceCheck.Simulated, PlaceCheck.check(37.5714, 127.0017, 200.0, fix.copy(mock = true), true, nowMs))
        assertEquals(PlaceCheck.Denied, PlaceCheck.check(37.5714, 127.0017, 200.0, fix, false, nowMs))
        assertEquals(PlaceCheck.Stale, PlaceCheck.check(37.5714, 127.0017, 200.0, fix.copy(timeMs = nowMs - 61_000), true, nowMs))
        assertTrue(PlaceCheck.check(37.5665, 126.978, 200.0, fix, true, nowMs) is PlaceCheck.Far) // 시청까지 약 2km
        val d = PlaceCheck.distance(37.5665, 126.978, 37.5714, 127.0017)
        assertTrue(d in 2000.0..2300.0, "$d")
    }
}
