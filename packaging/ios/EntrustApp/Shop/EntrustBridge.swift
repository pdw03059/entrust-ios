// ─────────────────────────────────────────────────────────────────────────────
// ENTRUST 연동 어댑터 — 예매처 앱에서 ENTRUST 를 아는 곳은 이 파일 하나뿐이다.
//
// 실제 고객사(예매처) 앱에 붙일 때:
//   1) 이 파일만 고객사 앱에 넣고, 아래 4개 함수를 고객사의 '예매 완료' · '예매 취소' · '모바일 티켓' 화면에서 부른다.
//   2) 체험판은 서명 열쇠를 폰에 두지만, 실제로는 서명을 고객사 서버가 한다(issueTicket · cancelNotice 를 서버 호출로 바꾸면 끝).
//   3) 표 형식(ET1 · EC1 · ES1)과 넘김 주소(entrust://open#t=…)는 바뀌지 않는다.
// 고객사 앱의 다른 부분(공연 목록 · 좌석 · 결제 · 회원)은 손대지 않는다.
// ─────────────────────────────────────────────────────────────────────────────
import Foundation
import CryptoKit

/// 고객사 주문 정보 중 ENTRUST 가 필요로 하는 칸만(이름 · 전화번호 같은 개인정보는 받지 않음)
struct EntrustOrderInput {
    var orderNo: String        // 고객사 예매번호(24자 이내)
    var showTitle: String
    var venueName: String
    var seats: [String]        // 예: ["A-3", "A-4"] (최대 3석)
    var gate: String           // 담당 입구
    var venueLat: Double       // 공연장 위치 · 반경 · 입장 시간 = ENTRUST 관리자 앱에서 정한 값
    var venueLon: Double
    var radiusM: Int
    var entryFrom: Int         // 유닉스 초
    var entryTo: Int
}

enum EntrustBridge {
    private static let keyStore = "ticket24.entrustSigningKey.v1"

    /// 예매처 서명 열쇠(체험판: 폰 안. 실제: 고객사 서버)
    static func signingKey() -> P256.Signing.PrivateKey {
        if let raw = UserDefaults.standard.data(forKey: keyStore), let k = try? P256.Signing.PrivateKey(rawRepresentation: raw) { return k }
        let k = P256.Signing.PrivateKey()
        UserDefaults.standard.set(k.rawRepresentation, forKey: keyStore)
        return k
    }

    static func resetKey() { UserDefaults.standard.removeObject(forKey: keyStore) }

    static var partnerId: String { Entrust.shopId(signingKey().publicKey.x963Representation) }

    /// ① 예매 완료 → 모바일 티켓 발급: (표 번호, 넘김 글자 ET1)
    static func issueTicket(_ o: EntrustOrderInput) throws -> (ck: String, token: String) {
        let ck = "tk_" + Entrust.randomB64(16)
        let terms = Terms(ck: ck, no: String(o.orderNo.prefix(24)), show: String(o.showTitle.prefix(60)), venue: String(o.venueName.prefix(40)),
                          seat: String(o.seats.joined(separator: ",").prefix(20)), gate: o.gate, qty: max(1, min(3, o.seats.count)),
                          lat: o.venueLat, lon: o.venueLon, r: Double(o.radiusM), from: o.entryFrom, to: o.entryTo, shop: partnerId)
        guard Entrust.validTerms(terms) else { throw Entrust.OpenError.malformed }
        return (ck, try Entrust.signTicket(terms, key: signingKey()))
    }

    /// ② 'ENTRUST 로 표 받기' 버튼이 여는 주소(ENTRUST 앱이 없으면 열리지 않음 → 고객사는 앱 설치 안내)
    static func handoffURL(token: String) -> URL? { URL(string: "entrust://open#t=" + Entrust.urlValue(token)) }

    /// ③ 예매 취소 → 검표기에 '취소된 표' 알림(EC1). 체험판은 같은 아이폰의 관리자 앱으로 넘긴다.
    static func cancelURL(ck: String) -> URL? {
        guard let notice = try? Entrust.cancelNotice(ck: ck, key: signingKey()) else { return nil }
        return URL(string: "entrustadmin://cancel#c=" + Entrust.urlValue(notice))
    }

    /// ④ 처음 한 번: 검표기(ENTRUST 관리자)에 이 예매처의 공개 열쇠 등록(ES1)
    static func registrationCode() -> String { Entrust.shopQr(name: "티켓24", pk: signingKey().publicKey.x963Representation) }
    static func registrationURL() -> URL? { URL(string: "entrustadmin://shop#k=" + Entrust.urlValue(registrationCode())) }
}
