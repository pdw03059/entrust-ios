// 체험 웹(assets/js/live-core.js)과 똑같은 형식: base64url · base45 · 예매처 표(ET1) · 표 등록(EL2) · 입장 QR(EQ2).
// 같은 검표 화면(live-gate.html)이 이 앱의 QR도 그대로 검사한다.
import Foundation
import CryptoKit
import Security

enum B64U {
    static func encode(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    static func decode(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}

enum B45 {
    static let chars: [Character] = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:")
    static func encode(_ d: Data) -> String {
        let b = [UInt8](d)
        var out = ""
        var i = 0
        while i < b.count {
            if i + 1 < b.count {
                var x = Int(b[i]) * 256 + Int(b[i + 1])
                let c = x % 45
                x /= 45
                let dd = x % 45
                let e = x / 45
                out.append(chars[c]); out.append(chars[dd]); out.append(chars[e])
            } else {
                let x = Int(b[i])
                out.append(chars[x % 45]); out.append(chars[x / 45])
            }
            i += 2
        }
        return out
    }
    static func decode(_ s: String) -> Data? {
        var idx: [Int] = []
        for ch in s {
            guard let k = chars.firstIndex(of: ch) else { return nil }
            idx.append(k)
        }
        var out: [UInt8] = []
        var i = 0
        while i < idx.count {
            let rem = idx.count - i
            if rem >= 3 {
                let x = idx[i] + idx[i + 1] * 45 + idx[i + 2] * 2025
                if x > 0xFFFF { return nil }
                out.append(UInt8(x >> 8)); out.append(UInt8(x & 0xFF))
                i += 3
            } else if rem == 2 {
                let x = idx[i] + idx[i + 1] * 45
                if x > 0xFF { return nil }
                out.append(UInt8(x))
                i += 2
            } else {
                return nil
            }
        }
        return Data(out)
    }
}

/// 예매처가 서명한 표 조건(웹과 같은 칸)
struct Terms: Codable, Equatable {
    var ck: String
    var no: String
    var show: String
    var venue: String
    var seat: String
    var gate: String
    var qty: Int
    var lat: Double
    var lon: Double
    var r: Double
    var from: Int
    var to: Int
    var shop: String
}

/// 이 폰에 저장하는 표(예매처 원문 t · 서명 s · 예매처 공개 열쇠 k 를 그대로 보관 — 검표기에 그대로 넘김)
struct StoredTicket: Codable {
    var terms: Terms
    var t: String
    var s: String
    var k: String
    var locked: Bool = false
    var registered: Bool = false
}

enum Entrust {
    /// 체험 사이트 주소(검표기가 '어느 사이트 표인가'를 볼 때 쓰는 이름) — 웹 체험판과 같은 값
    static let rp = "entrust--pdw03059.synapse-ai.co.kr"
    /// 이 앱이 만든 승인이라는 표시(웹은 https://… 주소, 앱은 이 이름)
    static let origin = "ios-app:kr.co.synapseai.entrust.demo"
    static let shopDomain = "ENTRUST-shop-ticket|v1|"
    static let entryTTL = 10
    static let bioTTL = 30
    static let maxAccuracy = 150.0

    static func now() -> Int { Int(Date().timeIntervalSince1970) }

    enum OpenError: Error { case malformed, badShopSig }

    /// 예매처 표 코드(ET1)를 열고 예매처 서명을 확인한다. 앞뒤에 다른 글자가 붙어 있어도 찾는다.
    static func openTicket(_ raw: String) throws -> StoredTicket {
        guard let r = raw.range(of: "ET1.") else { throw OpenError.malformed }
        let allowed = Set(B45.chars)
        var body = ""
        for ch in raw[r.upperBound...] {
            if allowed.contains(ch) { body.append(ch) } else { break }
        }
        var decoded: Data? = nil
        var cand = body
        for _ in 0..<3 {
            if let d = B45.decode(cand) { decoded = d; break }
            if cand.hasSuffix(" ") { cand.removeLast() } else { break }
        }
        guard let data = decoded,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let t = obj["t"] as? String, let s = obj["s"] as? String, let k = obj["k"] as? String,
              t.count <= 1200 else { throw OpenError.malformed }
        guard let terms = try? JSONDecoder().decode(Terms.self, from: Data(t.utf8)), validTerms(terms) else { throw OpenError.malformed }
        guard let kRaw = B64U.decode(k), shopId(kRaw) == terms.shop, verifyShopSig(t: t, s: s, pk: k) else { throw OpenError.badShopSig }
        return StoredTicket(terms: terms, t: t, s: s, k: k)
    }

    /// P-256 공개 열쇠(65바이트) → 웹이 읽는 SPKI(DER) 모양
    static func spki(_ raw65: Data) -> Data {
        let prefix: [UInt8] = [0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
                               0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00]
        return Data(prefix) + raw65
    }

    /// 표 등록 QR(EL2): 예매처 원문 · 서명 + 이 폰 보안 칩 열쇠의 공개 열쇠
    static func lockQr(_ tk: StoredTicket, devicePublicKey raw65: Data) -> String {
        let o: [String: Any] = ["v": 2, "t": tk.t, "s": tk.s, "cid": "", "pk": B64U.encode(spki(raw65)), "rp": rp, "org": origin]
        let data = (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
        return "EL2." + B45.encode(data)
    }
}

/// 얼굴 확인 1번으로 승인한 '세션 열쇠'(30초). 이 열쇠가 10초마다 새 입장 QR에 서명한다.
struct EntrySession {
    let key: P256.Signing.PrivateKey
    let sx: String
    let bts: Int
    let ad: Data
    let cd: Data
    let bs: Data

    /// 세션 열쇠를 만들고, 보안 칩 열쇠(Face ID)로 승인 서명을 받는다 — 웹의 WebAuthn 승인과 같은 모양
    static func open(ck: String, sign: (Data) throws -> Data) throws -> EntrySession {
        let key = P256.Signing.PrivateKey()
        let sx = B64U.encode(key.publicKey.x963Representation)
        let bts = Entrust.now()
        let chal = Data(SHA256.hash(data: Data("ENTRUST-live-bio|v1|\(ck)|\(sx)|\(bts)".utf8)))
        let cdText = "{\"type\":\"webauthn.get\",\"challenge\":\"\(B64U.encode(chal))\",\"origin\":\"\(Entrust.origin)\",\"crossOrigin\":false}"
        let cd = Data(cdText.utf8)
        var ad = Data(SHA256.hash(data: Data(Entrust.rp.utf8)))
        ad.append(contentsOf: [0x05, 0, 0, 0, 0]) // 사용자 있음 + 얼굴 확인함(UV), 기기 밖 복사 표시 없음
        let msg = ad + Data(SHA256.hash(data: cd))
        let bs = try sign(msg)
        return EntrySession(key: key, sx: sx, bts: bts, ad: ad, cd: cd, bs: bs)
    }

    /// 입장 QR(EQ2) 한 장
    func entryQr(ck: String, now: Int) throws -> String {
        var nonce = Data(count: 9)
        let rc = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 9, $0.baseAddress!) }
        if rc != errSecSuccess { throw Entrust.OpenError.malformed }
        let n = B64U.encode(nonce)
        let input = "ENTRUST-live-qr|v1|\(ck)|\(now)|\(n)|\(sx)|\(bts)"
        let qs = try key.signature(for: Data(input.utf8)).rawRepresentation
        guard let sxRaw = B64U.decode(sx) else { throw Entrust.OpenError.malformed }
        let ckData = Data(ck.utf8)
        let dt = now - bts
        guard dt >= 0, dt <= 255, ckData.count <= 255, ad.count <= 255, bs.count <= 255, cd.count <= 65535 else { throw Entrust.OpenError.malformed }
        var p = Data()
        p.append(2)
        p.append(UInt8(ckData.count)); p.append(ckData)
        let ts = UInt32(now)
        p.append(contentsOf: [UInt8((ts >> 24) & 0xFF), UInt8((ts >> 16) & 0xFF), UInt8((ts >> 8) & 0xFF), UInt8(ts & 0xFF)])
        p.append(UInt8(dt))
        p.append(nonce)
        p.append(sxRaw)
        p.append(UInt8(ad.count)); p.append(ad)
        p.append(contentsOf: [UInt8((cd.count >> 8) & 0xFF), UInt8(cd.count & 0xFF)]); p.append(cd)
        p.append(UInt8(bs.count)); p.append(bs)
        p.append(qs)
        return "EQ2." + B45.encode(p)
    }
}

/* ───── 예매처(티콧24) · 관리자 앱이 함께 쓰는 것 ───── */
extension Entrust {
    /// 예매처 공개 열쇠(65바이트) → 짧은 이름(지문 8바이트) — 웹 shopId 와 같음
    static func shopId(_ raw65: Data) -> String { B64U.encode(Data(SHA256.hash(data: raw65)).prefix(8)) }

    /// 표 조건 범위 검사(웹 checkTerms 와 같은 기준)
    static func validTerms(_ t: Terms) -> Bool {
        let ckOK = t.ck.count >= 16 && t.ck.count <= 64
        let placeOK = t.lat >= -90 && t.lat <= 90 && t.lon >= -180 && t.lon <= 180 && t.r >= 20 && t.r <= 5000
        let timeOK = t.to > t.from && t.to - t.from <= 7 * 24 * 3600
        let textOK = t.show.count <= 60 && t.venue.count <= 40 && t.seat.count <= 20 && t.no.count <= 24 && !t.gate.isEmpty && t.gate.count <= 16
        return ckOK && placeOK && timeOK && textOK && (1...3).contains(t.qty)
    }

    static func verifyShopSig(t: String, s: String, pk: String) -> Bool {
        guard let kRaw = B64U.decode(pk), let sRaw = B64U.decode(s),
              let pub = try? P256.Signing.PublicKey(x963Representation: kRaw),
              let sig = try? P256.Signing.ECDSASignature(rawRepresentation: sRaw) else { return false }
        return pub.isValidSignature(sig, for: Data((shopDomain + t).utf8))
    }

    /// 예매처: 표 조건에 서명해 넘김 글자(ET1)를 만든다
    static func signTicket(_ t: Terms, key: P256.Signing.PrivateKey) throws -> String {
        let o: [String: Any] = [
            "v": 1, "ck": t.ck, "no": t.no, "show": t.show, "venue": t.venue, "seat": t.seat, "gate": t.gate, "qty": t.qty,
            "lat": t.lat, "lon": t.lon, "r": Int(t.r), "from": t.from, "to": t.to, "shop": t.shop,
        ]
        let tData = try JSONSerialization.data(withJSONObject: o)
        let tText = String(decoding: tData, as: UTF8.self)
        let sig = try key.signature(for: Data((shopDomain + tText).utf8)).rawRepresentation
        let k = B64U.encode(key.publicKey.x963Representation)
        let wrap: [String: Any] = ["t": tText, "s": B64U.encode(sig), "k": k]
        return "ET1." + B45.encode(try JSONSerialization.data(withJSONObject: wrap))
    }

    /// 예매처 등록 QR(ES1) — 검표기(관리자 앱 · 웹 검표)가 이 예매처의 표만 받게 함
    static func shopQr(name: String, pk raw65: Data) -> String {
        let o: [String: Any] = ["v": 1, "name": name, "pk": B64U.encode(raw65)]
        let data = (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
        return "ES1." + B45.encode(data)
    }

    /// 앱 사이 주소에 넣을 값(모든 특수 글자를 %로)
    static func urlValue(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
    }

    /// 앱 주소(예: entrust://open#t=…)에서 값 꺼내기 — #이름=값 또는 ?이름=값
    static func urlParam(_ url: URL, _ name: String) -> String? {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        if let f = c.fragment, f.hasPrefix(name + "=") { return String(f.dropFirst(name.count + 1)) }
        return c.queryItems?.first(where: { $0.name == name })?.value
    }

    static func randomB64(_ n: Int) -> String {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return B64U.encode(d)
    }
}

/// 관리자 앱이 정하는 공연(장소 · 반경 · 입장 시간 · 입구) — 티콧24로 넘겨 표에 그대로 들어감
struct ShowEvent: Codable, Identifiable, Equatable {
    var id: String
    var show: String
    var venue: String
    var lat: Double
    var lon: Double
    var r: Int
    var from: Int
    var to: Int
    var gate: String
    var price: Int
    var category: String? = "콘서트"

    static func demo() -> ShowEvent {
        let start = (Entrust.now() / 60) * 60
        return ShowEvent(id: "ev_demo", show: "체험 공연 · ENTRUST 시연", venue: "서울시청 앞(기본 위치)", lat: 37.5665, lon: 126.978,
                         r: 200, from: start, to: start + 3 * 3600, gate: "1", price: 55000)
    }

    /// 앱 사이 주소에 싣는 글자(base64url JSON)
    func encoded() -> String { B64U.encode((try? JSONEncoder().encode(self)) ?? Data()) }
    static func decode(_ s: String) -> ShowEvent? {
        guard let d = B64U.decode(s), let e = try? JSONDecoder().decode(ShowEvent.self, from: d) else { return nil }
        guard e.r >= 20, e.r <= 5000, e.to > e.from, e.to - e.from <= 7 * 24 * 3600, !e.id.isEmpty else { return nil }
        return e
    }
}

/* ───── 예매 취소 알림(EC1): 예매처가 서명 → 검표기가 '취소된 표'로 거절 ───── */
extension Entrust {
    static let cancelDomain = "ENTRUST-shop-cancel|v1|"

    static func cancelNotice(ck: String, key: P256.Signing.PrivateKey) throws -> String {
        let at = now()
        let sig = try key.signature(for: Data("\(cancelDomain)\(ck)|\(at)".utf8)).rawRepresentation
        let o: [String: Any] = ["v": 1, "ck": ck, "at": at, "shop": shopId(key.publicKey.x963Representation), "s": B64U.encode(sig)]
        return "EC1." + B45.encode(try JSONSerialization.data(withJSONObject: o))
    }
}
