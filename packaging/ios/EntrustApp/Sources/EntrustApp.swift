// ENTRUST 아이폰 앱(관객): 티켓24에서 산 표 받기 → 보안 칩 열쇠로 Face ID 잠금 → 검표기(관리자 앱) 등록
// → 공연장 반경 안(실제 GPS · 조작 위치 거절) · 입장 시간 안에서만 10초마다 바뀌는 입장 QR.
import SwiftUI
import UIKit
import CoreLocation
import LocalAuthentication

@main
struct EntrustDemoApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var loc = LocationModel()
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .environmentObject(loc)
                .onOpenURL { url in model.receiveURL(url) }
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var ticket: StoredTicket?
    @Published var notice = ""
    @Published var busy = false
    @Published var lockText: String?
    @Published var lockQrImage: UIImage?
    @Published var entryImage: UIImage?
    @Published var qrLeft = Entrust.entryTTL
    @Published var bioLeft = Entrust.bioTTL
    @Published var now = Entrust.now()
    @Published private(set) var session: EntrySession?
    private var lastQrAt = 0
    private let storeKey = "entrust.ticket.v1"

    init() {
        if let d = UserDefaults.standard.data(forKey: storeKey), let t = try? JSONDecoder().decode(StoredTicket.self, from: d) {
            ticket = t
        }
        if ticket?.locked == true && !DeviceKey.exists {
            ticket?.locked = false
            ticket?.registered = false
        }
    }

    private func save() {
        if let t = ticket, let d = try? JSONEncoder().encode(t) {
            UserDefaults.standard.set(d, forKey: storeKey)
        } else {
            UserDefaults.standard.removeObject(forKey: storeKey)
        }
    }

    nonisolated static func describe(_ e: Error) -> String {
        if let la = e as? LAError, la.code == .userCancel || la.code == .systemCancel || la.code == .appCancel {
            return "취소했어요. 다시 눌러 주세요."
        }
        if let de = e as? DeviceKeyError, let d = de.errorDescription { return d }
        let ns = e as NSError
        return "얼굴 확인 · 열쇠 사용에 실패했어요(\(ns.code)). 얼굴 · 지문을 새로 등록했다면 표를 다시 받아 주세요."
    }

    /* 1. 예매처 표 받기 */
    func receive(_ text: String) {
        notice = ""
        do {
            let t = try Entrust.openTicket(text)
            if let cur = ticket, cur.terms.ck == t.terms.ck, cur.locked {
                notice = "이미 받아서 잠근 표예요."
                return
            }
            closeQr()
            DeviceKey.delete()
            lockQrImage = nil
            lockText = nil
            ticket = t
            save()
        } catch Entrust.OpenError.badShopSig {
            notice = "예매처 서명이 맞지 않아요(표 내용이 바뀌었어요)."
        } catch {
            notice = "예매처 표 코드를 읽을 수 없어요. 티켓24에서 다시 보내 주세요."
        }
    }

    func receiveURL(_ url: URL) {
        if let t = Entrust.urlParam(url, "t"), !t.isEmpty { receive(t) }
    }

    /* 2. 보안 칩 열쇠로 잠그기 */
    func lock() {
        guard ticket != nil else { return }
        notice = ""
        busy = true
        Task.detached {
            do {
                let pub = try DeviceKey.create()
                _ = try DeviceKey.sign(Data("ENTRUST-lock-check".utf8), reason: "표를 이 아이폰에 잠글게요")
                await MainActor.run { self.finishLock(pub) }
            } catch {
                let msg = AppModel.describe(error)
                DeviceKey.delete()
                await MainActor.run {
                    self.notice = msg
                    self.busy = false
                }
            }
        }
    }

    private func finishLock(_ pub: Data) {
        busy = false
        guard var t = ticket else { return }
        t.locked = true
        ticket = t
        save()
        setLockQr(t, pub)
    }

    private func setLockQr(_ t: StoredTicket, _ pub: Data) {
        let text = Entrust.lockQr(t, devicePublicKey: pub)
        lockText = text
        lockQrImage = qrImage(text)
    }

    /* 3. 검표기 등록 */
    func refreshLockQr() {
        guard let t = ticket, t.locked, lockText == nil, let pub = try? DeviceKey.publicKey() else { return }
        setLockQr(t, pub)
    }

    var adminURL: URL? {
        guard let text = lockText else { return nil }
        return URL(string: "entrustadmin://lock#l=" + Entrust.urlValue(text))
    }

    func markRegistered() {
        guard var t = ticket else { return }
        t.registered = true
        ticket = t
        save()
    }

    /* 4. 입장 QR */
    func timeState() -> (ok: Bool, text: String) {
        guard let t = ticket?.terms else { return (false, "") }
        if now < t.from { return (false, "\(dur(t.from - now)) 뒤 열림") }
        if now > t.to { return (false, "끝났어요") }
        return (true, "열림 · \(dur(t.to - now)) 남음")
    }

    func openQr(placeOK: Bool) {
        guard let t = ticket, placeOK, timeState().ok, !busy else { return }
        if UIScreen.main.isCaptured { notice = "화면 녹화 · 화면 공유 · 미러링 중에는 입장 QR을 열 수 없어요."; return }
        notice = ""
        busy = true
        let ck = t.terms.ck
        Task.detached {
            do {
                let s = try EntrySession.open(ck: ck) { msg in
                    try DeviceKey.sign(msg, reason: "입장 QR을 열려면 얼굴을 확인해 주세요")
                }
                await MainActor.run {
                    self.session = s
                    self.busy = false
                    self.tick(placeOK: true, force: true)
                }
            } catch {
                let msg = AppModel.describe(error)
                await MainActor.run {
                    self.notice = msg
                    self.busy = false
                }
            }
        }
    }

    func tick(placeOK: Bool, force: Bool = false) {
        now = Entrust.now()
        guard let t = ticket, let s = session else { return }
        let bl = Entrust.bioTTL - (now - s.bts)
        if bl <= 0 { closeQr(); return } // 30초가 지나면 다시 얼굴 확인
        if UIScreen.main.isCaptured { closeQr("화면 녹화 · 화면 공유가 감지돼 QR을 닫았어요."); return }
        if !timeState().ok { closeQr("입장 시간이 끝나 QR을 닫았어요."); return }
        if !placeOK { closeQr("공연장 반경을 벗어나 QR을 닫았어요."); return }
        if force || now - lastQrAt >= Entrust.entryTTL {
            lastQrAt = now
            if let text = try? s.entryQr(ck: t.terms.ck, now: now) { entryImage = qrImage(text) }
        }
        qrLeft = Entrust.entryTTL - (now - lastQrAt)
        bioLeft = bl
    }

    func closeQr(_ msg: String = "") {
        session = nil
        entryImage = nil
        if !msg.isEmpty { notice = msg }
    }

    func reset() {
        closeQr()
        DeviceKey.delete()
        ticket = nil
        lockQrImage = nil
        lockText = nil
        notice = ""
        save()
    }
}

/* ───── 화면 ───── */
struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var phase

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let t = model.ticket {
                        if !t.locked {
                            LockView(t: t)
                        } else if !t.registered {
                            RegisterView(t: t)
                        } else {
                            EntryView(t: t)
                        }
                    } else {
                        ReceiveView()
                    }
                    if !model.notice.isEmpty {
                        Text(model.notice).font(.subheadline.weight(.semibold)).foregroundColor(.badRed)
                    }
                }
                .padding(16)
            }
            .background(Color.paper.ignoresSafeArea())
            .navigationTitle("ENTRUST")
            .toolbar {
                if model.ticket != nil {
                    Button("처음부터") { model.reset() }
                }
            }
        }
        .tint(.accentBlue)
        .onChange(of: phase) { p in
            if p != .active { model.closeQr() } // 화면을 떠나면 QR을 닫고 다시 얼굴 확인
        }
        // 아이폰은 캡처 자체를 막을 수 없어 감지 즉시 QR을 닫는다(캡처한 QR은 10초 뒤 검표에서 거절)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            if model.session != nil { model.closeQr("화면 캡처가 감지돼 QR을 닫았어요. 캡처한 QR은 10초가 지나면 검표에서 거절돼요.") }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            if UIScreen.main.isCaptured && model.session != nil { model.closeQr("화면 녹화 · 화면 공유가 감지돼 QR을 닫았어요.") }
        }
    }
}

struct ReceiveView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    @State private var manual = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("티켓24에서 산 표를 받아요").font(.system(size: 28, weight: .heavy))
            Text("예매처가 서명한 표만 받아요. 공연 장소 · 입장 시간은 관리자가 정하고, 이 앱에서는 바꿀 수 없어요.")
                .font(.subheadline).foregroundColor(.muted)
            PrimaryButton(title: "티켓24 열기") {
                if let u = URL(string: "ticket24://home") { openURL(u) }
            }
            Text("티켓24에서 예매 후 'ENTRUST로 표 보내기'를 누르면 이 앱으로 바로 들어와요.").font(.footnote).foregroundColor(.muted)
            GhostButton(title: "표 코드 붙여 넣기") { model.receive(UIPasteboard.general.string ?? "") }
            TextField("ET1.… 직접 넣기", text: $manual, axis: .vertical)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 12))
            if !manual.isEmpty {
                Button("받기") { model.receive(manual) }.font(.body.weight(.semibold))
            }
        }
    }
}

struct LockView: View {
    @EnvironmentObject var model: AppModel
    let t: StoredTicket
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("이 표를 Face ID로 잠글게요").font(.system(size: 28, weight: .heavy))
            Text("아이폰 보안 칩 안에 이 표의 열쇠를 만들어요. 열쇠는 폰 밖으로 나가지 않고(백업 · iCloud 복사 안 됨), 얼굴 · 지문으로만 쓸 수 있어요.")
                .font(.subheadline).foregroundColor(.muted)
            TicketCard(t: t.terms)
            PrimaryButton(title: model.busy ? "잠그는 중…" : "Face ID로 잠그기", disabled: model.busy) { model.lock() }
        }
    }
}

struct RegisterView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openURL) private var openURL
    let t: StoredTicket
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("검표기에 표를 등록해요").font(.system(size: 28, weight: .heavy))
            Text("공연 전날 명단을 보내는 일을 흉내 내요. 관리자 앱이 같은 아이폰에 있으면 아래 버튼, 다른 기기의 검표기면 이 QR을 찍어 주세요.")
                .font(.subheadline).foregroundColor(.muted)
            PrimaryButton(title: "관리자 앱에 등록(같은 아이폰)", disabled: model.adminURL == nil) {
                if let u = model.adminURL { openURL(u) }
            }
            Card {
                QRView(image: model.lockQrImage)
                Text("예매번호 \(t.terms.no) · \(t.terms.seat) · 입구 \(t.terms.gate)").font(.footnote).foregroundColor(.muted)
            }
            GhostButton(title: "등록했어요 · 입장 QR로") { model.markRegistered() }
        }
        .onAppear { model.refreshLockQr() }
    }
}

struct EntryView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var loc: LocationModel
    let t: StoredTicket
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        let place = PlaceCheck.check(t.terms, location: loc.location, status: loc.status)
        let time = model.timeState()
        VStack(alignment: .leading, spacing: 12) {
            Text("입장 QR").font(.system(size: 28, weight: .heavy))
            Card {
                if model.entryImage != nil {
                    QRView(image: model.entryImage)
                    ProgressView(value: Double(model.qrLeft), total: Double(Entrust.entryTTL)).tint(.accentBlue)
                    HStack {
                        Text("QR \(model.qrLeft)초마다 바뀜")
                        Spacer()
                        Text("얼굴 확인 \(model.bioLeft)초 남음")
                    }
                    .font(.footnote.monospacedDigit()).foregroundColor(.muted)
                } else {
                    Text(title(time.ok, place.isOK)).font(.system(size: 19, weight: .bold))
                    row("입장 시간", time.text, time.ok)
                    row("공연장까지", placeText(place), place.isOK)
                    PrimaryButton(title: model.busy ? "확인 중…" : "Face ID로 열기", disabled: !(time.ok && place.isOK) || model.busy) {
                        model.openQr(placeOK: place.isOK)
                    }
                }
                VStack(spacing: 0) {
                    Text("담당 입구").font(.footnote.weight(.semibold)).foregroundColor(.muted)
                    Text(t.terms.gate).font(.system(size: 56, weight: .heavy)).monospacedDigit()
                }
                .frame(maxWidth: .infinity)
            }
            TicketCard(t: t.terms)
            Text("이 앱의 열쇠는 아이폰 보안 칩 안에만 있어 다른 기기로 복사되지 않고, 기기 암호로는 열리지 않아요. 위치 조작 앱으로 꾸민 위치는 거절하고, 화면 캡처 · 녹화 · 공유가 감지되면 QR을 바로 닫아요.")
                .font(.footnote).foregroundColor(.muted)
        }
        .onAppear { loc.start() }
        .onDisappear { loc.stop() }
        .onReceive(timer) { _ in
            let ok = PlaceCheck.check(t.terms, location: loc.location, status: loc.status).isOK
            model.tick(placeOK: ok)
        }
    }

    private func title(_ timeOK: Bool, _ placeOK: Bool) -> String {
        if !timeOK { return model.now < t.terms.from ? "입장은 \(clock(t.terms.from))부터" : "입장 시간이 끝났어요" }
        return placeOK ? "얼굴로 QR 열기" : "\(t.terms.venue)에 오면 열려요"
    }

    private func row(_ k: String, _ v: String, _ ok: Bool) -> some View {
        HStack {
            Text(k).font(.subheadline)
            Spacer()
            Text(v).font(.subheadline.weight(.bold)).foregroundColor(ok ? .okGreen : .badRed)
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.black.opacity(0.1)))
    }

    private func placeText(_ p: PlaceCheck) -> String {
        switch p {
        case .ok(let d): return "\(meters(d)) · 반경 안"
        case .far(let d): return "\(meters(d)) · 반경 \(meters(Int(t.terms.r))) 밖"
        case .inaccurate(let a): return "위치가 흐려요(오차 \(meters(a)))"
        case .simulated: return "꾸민 위치예요(거절)"
        case .denied: return "위치 권한 꺼짐 · 설정 › ENTRUST"
        case .waiting: return "위치 확인 중…"
        case .stale: return "위치가 오래됐어요"
        }
    }
}
