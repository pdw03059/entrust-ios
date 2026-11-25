// 검표(관리자 앱): 웹 검표(assets/js/live-core.js 의 readLockQr · verifyEntryQr)와 같은 검사를 앱에서 한다.
// 예매처 등록(ES1) → 표 등록(EL2, 예매처 서명 · 다른 폰 중복 잠금) → 입장 QR(EQ2: 10초 · 얼굴 확인 30초 · 서명 · 입장 시간 · 이미 입장)
import Foundation
import CryptoKit

struct ShopReg: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var pk: String
}

struct GateLock: Codable, Equatable {
    var terms: Terms
    var pk: String
    var rp: String
    var org: String
    var shopName: String
}

struct EnteredRec: Codable, Equatable {
    var at: Int
}

enum GateCode: String, Error {
    case malformed, unknownTicket, expired, future, bioStale, badOrigin, noUV, badBio, badSig
    case alreadyEntered, wrongGate, notYet, closed, unknownShop, badShopSig, lockedElsewhere, cancelled

    var message: String {
        switch self {
        case .malformed: return "읽을 수 없는 QR이에요"
        case .unknownTicket: return "이 검표기에 등록되지 않은 표예요"
        case .expired: return "QR이 10초를 넘었어요(캡처 · 사진은 통하지 않아요)"
        case .future: return "폰 시계가 너무 빨라요"
        case .bioStale: return "얼굴 확인이 30초를 넘었어요 — 관객 폰에서 다시 열어 주세요"
        case .badOrigin: return "다른 앱 · 사이트에서 만든 QR이에요"
        case .noUV: return "얼굴 · 지문 확인 없이 만든 QR이에요"
        case .badBio: return "폰 잠금 열쇠 서명이 맞지 않아요"
        case .badSig: return "QR 서명이 맞지 않아요(내용이 바뀜)"
        case .alreadyEntered: return "이미 입장한 표예요"
        case .wrongGate: return "담당 입구가 아니에요"
        case .notYet: return "아직 입장 시간이 아니에요"
        case .closed: return "입장 시간이 끝났어요"
        case .unknownShop: return "등록되지 않은 예매처의 표예요"
        case .badShopSig: return "예매처 서명이 맞지 않아요(표 조건이 바뀜)"
        case .lockedElsewhere: return "이미 다른 폰에 잠긴 표예요"
        case .cancelled: return "예매처에서 취소된 표예요"
        }
    }
}

struct GatePass {
    var lock: GateLock
    var synced: Bool
}

private struct ByteReader {
    let b: [UInt8]
    var i = 0
    mutating func take(_ n: Int) -> [UInt8]? {
        guard n >= 0, i + n <= b.count else { return nil }
        let out = Array(b[i..<(i + n)])
        i += n
        return out
    }
    mutating func u8() -> Int? { take(1).map { Int($0[0]) } }
    mutating func u16() -> Int? { take(2).map { Int($0[0]) * 256 + Int($0[1]) } }
    mutating func u32() -> Int? { take(4).map { (Int($0[0]) << 24) + (Int($0[1]) << 16) + (Int($0[2]) << 8) + Int($0[3]) } }
}

enum Gate {
    static let futureSkew = 5

    static func parseShopQr(_ text: String) -> ShopReg? {
        guard text.hasPrefix("ES1."), text.count <= 600,
              let data = B45.decode(String(text.dropFirst(4))),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (o["v"] as? Int) == 1, let pk = o["pk"] as? String,
              let raw = B64U.decode(pk), raw.count == 65,
              (try? P256.Signing.PublicKey(x963Representation: raw)) != nil else { return nil }
        let name = String((o["name"] as? String ?? "예매처").prefix(40))
        return ShopReg(id: Entrust.shopId(raw), name: name, pk: pk)
    }

    static func readLockQr(_ text: String, shops: [String: ShopReg], locks: [String: GateLock]) -> Result<GateLock, GateCode> {
        guard text.hasPrefix("EL2."), text.count <= 4000,
              let data = B45.decode(String(text.dropFirst(4))),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (o["v"] as? Int) == 2, let t = o["t"] as? String, let s = o["s"] as? String,
              let pk = o["pk"] as? String, let org = o["org"] as? String, let rp = o["rp"] as? String,
              let terms = try? JSONDecoder().decode(Terms.self, from: Data(t.utf8)), Entrust.validTerms(terms),
              B64U.decode(pk) != nil else { return .failure(.malformed) }
        guard let shop = shops[terms.shop] else { return .failure(.unknownShop) }
        guard Entrust.verifyShopSig(t: t, s: s, pk: shop.pk) else { return .failure(.badShopSig) }
        if let prev = locks[terms.ck], prev.pk != pk { return .failure(.lockedElsewhere) } // 먼저 잠근 폰만 인정
        return .success(GateLock(terms: terms, pk: pk, rp: rp, org: org, shopName: shop.name))
    }

    /// 예매 취소 알림(EC1) 읽기: 등록된 예매처 서명이 맞으면 취소된 표 번호
    static func readCancel(_ text: String, shops: [String: ShopReg]) -> Result<String, GateCode> {
        guard text.hasPrefix("EC1."), text.count <= 600,
              let data = B45.decode(String(text.dropFirst(4))),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (o["v"] as? Int) == 1, let ck = o["ck"] as? String, let at = o["at"] as? Int,
              let shopId = o["shop"] as? String, let s = o["s"] as? String else { return .failure(.malformed) }
        guard let shop = shops[shopId] else { return .failure(.unknownShop) }
        guard let kRaw = B64U.decode(shop.pk), let sRaw = B64U.decode(s),
              let pub = try? P256.Signing.PublicKey(x963Representation: kRaw),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: sRaw),
              pub.isValidSignature(sig, for: Data("\(Entrust.cancelDomain)\(ck)|\(at)".utf8)) else { return .failure(.badShopSig) }
        return .success(ck)
    }

    static func verifyEntry(_ text: String, locks: [String: GateLock], entered: [String: EnteredRec], cancelled: Set<String> = [], gate: String, now: Int) -> Result<GatePass, GateCode> {
        guard text.hasPrefix("EQ2."), text.count <= 4000, let data = B45.decode(String(text.dropFirst(4))) else { return .failure(.malformed) }
        var r = ByteReader(b: [UInt8](data))
        guard r.u8() == 2,
              let ckLen = r.u8(), let ckB = r.take(ckLen), let ts = r.u32(), let dt = r.u8(),
              let nB = r.take(9), let sxB = r.take(65),
              let adLen = r.u8(), let adB = r.take(adLen),
              let cdLen = r.u16(), let cdB = r.take(cdLen),
              let bsLen = r.u8(), let bsB = r.take(bsLen), let qsB = r.take(64),
              r.i == r.b.count else { return .failure(.malformed) }
        let ck = String(decoding: ckB, as: UTF8.self)
        let bts = ts - dt
        guard let lock = locks[ck] else { return .failure(.unknownTicket) }
        if ts > now + futureSkew { return .failure(.future) }
        if now - ts > Entrust.entryTTL { return .failure(.expired) }
        if bts > ts + 1 || ts - bts > Entrust.bioTTL { return .failure(.bioStale) }

        // 1) 얼굴 확인 승인(웹 WebAuthn · 앱 보안 칩 같은 모양)
        let sx = B64U.encode(Data(sxB))
        let ad = Data(adB), cd = Data(cdB)
        guard let cdObj = try? JSONSerialization.jsonObject(with: cd) as? [String: Any],
              (cdObj["type"] as? String) == "webauthn.get" else { return .failure(.badBio) }
        guard (cdObj["origin"] as? String) == lock.org, (cdObj["crossOrigin"] as? Bool) != true else { return .failure(.badOrigin) }
        let chal = B64U.encode(Data(SHA256.hash(data: Data("ENTRUST-live-bio|v1|\(ck)|\(sx)|\(bts)".utf8))))
        guard (cdObj["challenge"] as? String) == chal else { return .failure(.badBio) }
        guard ad.count >= 37, ad.prefix(32) == Data(SHA256.hash(data: Data(lock.rp.utf8))) else { return .failure(.badOrigin) }
        let flags = adB[32]
        guard flags & 0x01 != 0, flags & 0x04 != 0 else { return .failure(.noUV) }
        guard let pkDer = B64U.decode(lock.pk),
              let devKey = try? P256.Signing.PublicKey(derRepresentation: pkDer),
              let bioSig = try? P256.Signing.ECDSASignature(derRepresentation: Data(bsB)),
              devKey.isValidSignature(bioSig, for: ad + Data(SHA256.hash(data: cd))) else { return .failure(.badBio) }

        // 2) 세션 열쇠 서명(10초마다 새 QR)
        let n = B64U.encode(Data(nB))
        let input = "ENTRUST-live-qr|v1|\(ck)|\(ts)|\(n)|\(sx)|\(bts)"
        guard let sessKey = try? P256.Signing.PublicKey(x963Representation: Data(sxB)),
              let qSig = try? P256.Signing.ECDSASignature(rawRepresentation: Data(qsB)),
              sessKey.isValidSignature(qSig, for: Data(input.utf8)) else { return .failure(.badSig) }

        if cancelled.contains(ck) { return .failure(.cancelled) }
        if lock.terms.gate != gate { return .failure(.wrongGate) }
        if now < lock.terms.from - futureSkew { return .failure(.notYet) }
        if now > lock.terms.to { return .failure(.closed) }
        if entered[ck] != nil { return .failure(.alreadyEntered) }
        return .success(GatePass(lock: lock, synced: flags & 0x10 != 0))
    }
}
