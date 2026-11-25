// ENTRUST 관리자 앱(주최 · 운영자): ① 공연 만들기 — 입장 QR이 열릴 장소(실제 GPS) · 반경 · 시간 · 입구 → 티콧24로 보내기
// ② 검표 — 카메라로 예매처 등록(ES1) · 표 등록(EL2) · 입장 QR(EQ2) · 취소 알림(EC1)을 읽고 판정
// ③ 현황 — 등록된 예매처 · 표 · 입장 · 취소
import SwiftUI
import UIKit
import MapKit
import CoreLocation

@main
struct EntrustAdminApp: App {
    @StateObject private var store = AdminStore()
    @StateObject private var loc = LocationModel()
    var body: some Scene {
        WindowGroup {
            AdminRoot()
                .environmentObject(store)
                .environmentObject(loc)
                .onOpenURL { store.handleURL($0) }
        }
    }
}

struct LogLine: Codable, Identifiable {
    var id = UUID()
    var at: Int
    var ok: Bool
    var text: String
}

struct Verdict: Equatable {
    var kind: Int        // 0 통과 · 1 거절 · 2 안내
    var title: String
    var text: String
}

@MainActor
final class AdminStore: ObservableObject {
    @Published var events: [ShowEvent] = []
    @Published var shops: [String: ShopReg] = [:]
    @Published var locks: [String: GateLock] = [:]
    @Published var entered: [String: EnteredRec] = [:]
    @Published var cancelled: [String] = []
    @Published var log: [LogLine] = []
    @Published var gate = "1"
    @Published var verdict: Verdict?
    @Published var tab = 0
    private let key = "entrustadmin.store.v1"

    private struct Saved: Codable {
        var events: [ShowEvent]; var shops: [String: ShopReg]; var locks: [String: GateLock]
        var entered: [String: EnteredRec]; var cancelled: [String]; var log: [LogLine]; var gate: String
    }

    init() {
        if let d = UserDefaults.standard.data(forKey: key), let s = try? JSONDecoder().decode(Saved.self, from: d) {
            events = s.events; shops = s.shops; locks = s.locks; entered = s.entered; cancelled = s.cancelled; log = s.log; gate = s.gate
        }
    }

    func save() {
        let s = Saved(events: events, shops: shops, locks: locks, entered: entered, cancelled: cancelled, log: Array(log.suffix(200)), gate: gate)
        if let d = try? JSONEncoder().encode(s) { UserDefaults.standard.set(d, forKey: key) }
    }

    func upsert(_ e: ShowEvent) {
        if let i = events.firstIndex(where: { $0.id == e.id }) { events[i] = e } else { events.insert(e, at: 0) }
        save()
    }

    func delete(_ e: ShowEvent) {
        events.removeAll { $0.id == e.id }
        save()
    }

    nonisolated static func shopURL(_ e: ShowEvent) -> URL? { URL(string: "ticket24://event#e=" + e.encoded()) }

    private func show(_ kind: Int, _ title: String, _ text: String) {
        verdict = Verdict(kind: kind, title: title, text: text)
        log.append(LogLine(at: Entrust.now(), ok: kind == 0, text: "\(title) · \(text)"))
        UINotificationFeedbackGenerator().notificationOccurred(kind == 0 ? .success : kind == 1 ? .error : .warning)
        save()
    }

    /// 다른 앱에서 넘어온 것(같은 아이폰): entrustadmin://shop#k= · lock#l= · cancel#c=
    func handleURL(_ url: URL) {
        tab = 1
        if let k = Entrust.urlParam(url, "k") { handle(k) }
        else if let l = Entrust.urlParam(url, "l") { handle(l) }
        else if let c = Entrust.urlParam(url, "c") { handle(c) }
    }

    /// QR 글자 하나를 판정(카메라 · 앱 넘김 공통)
    func handle(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let now = Entrust.now()
        if text.hasPrefix("ES1.") {
            guard let s = Gate.parseShopQr(text) else { show(1, "등록 실패", "예매처 등록 QR을 읽을 수 없어요"); return }
            shops[s.id] = s
            show(0, "예매처 등록됨", "\(s.name) · 지문 \(s.id)")
        } else if text.hasPrefix("EL2.") {
            switch Gate.readLockQr(text, shops: shops, locks: locks) {
            case .success(let l):
                locks[l.terms.ck] = l
                show(0, "표 등록됨", "\(l.terms.show) · \(l.terms.seat) · \(l.terms.gate)번 입구")
            case .failure(let c):
                show(1, "등록 거절", c.message)
            }
        } else if text.hasPrefix("EC1.") {
            switch Gate.readCancel(text, shops: shops) {
            case .success(let ck):
                if !cancelled.contains(ck) { cancelled.append(ck) }
                show(2, "취소 알림 받음", "이 표는 이제 입장이 거절돼요")
            case .failure(let c):
                show(1, "취소 알림 거절", c.message)
            }
        } else if text.hasPrefix("EQ2.") {
            switch Gate.verifyEntry(text, locks: locks, entered: entered, cancelled: Set(cancelled), gate: gate, now: now) {
            case .success(let p):
                entered[p.lock.terms.ck] = EnteredRec(at: now)
                show(0, "통과", "\(p.lock.terms.qty)명 · 좌석 \(p.lock.terms.seat)\(p.synced ? " · iCloud 동기화 열쇠" : "")")
            case .failure(let c):
                if c == .wrongGate, let ck = ckOf(text), let l = locks[ck] {
                    show(2, "\(l.terms.gate)번 입구", "담당 입구로 안내해 주세요")
                } else if c == .notYet {
                    show(2, "아직 입장 전", c.message)
                } else {
                    show(1, c == .alreadyEntered ? "이미 입장" : "거절", c.message)
                }
            }
        } else {
            show(1, "모르는 QR", "ENTRUST QR이 아니에요")
        }
    }

    private func ckOf(_ text: String) -> String? {
        guard let d = B45.decode(String(text.dropFirst(4))), d.count > 2 else { return nil }
        let b = [UInt8](d)
        let n = Int(b[1])
        guard b.count >= 2 + n else { return nil }
        return String(decoding: b[2..<(2 + n)], as: UTF8.self)
    }

    func resetGate() {
        shops = [:]; locks = [:]; entered = [:]; cancelled = []; log = []; verdict = nil
        save()
    }
}

/* ───── 화면 ───── */
struct AdminRoot: View {
    @EnvironmentObject var store: AdminStore
    var body: some View {
        TabView(selection: $store.tab) {
            NavigationStack { EventsView() }
                .tabItem { Label("공연 관리", systemImage: "mappin.and.ellipse") }.tag(0)
            NavigationStack { GateView() }
                .tabItem { Label("검표", systemImage: "qrcode.viewfinder") }.tag(1)
            NavigationStack { StatusView() }
                .tabItem { Label("현황", systemImage: "list.bullet.rectangle") }.tag(2)
        }
        .tint(.accentBlue)
    }
}

struct EventsView: View {
    @EnvironmentObject var store: AdminStore
    @Environment(\.openURL) private var openURL
    @State private var editing: ShowEvent?

    var body: some View {
        List {
            Section {
                Text("입장 QR이 열릴 장소(GPS) · 반경 · 시간을 정하고 티콧24로 보내요. 티콧24에서 산 표에 이 조건이 서명되어 들어가요.")
                    .font(.footnote).foregroundColor(.muted)
            }
            if store.events.isEmpty {
                Text("아직 공연이 없어요. 오른쪽 위 '+'로 만들어 주세요.").foregroundColor(.muted)
            }
            ForEach(store.events) { e in
                VStack(alignment: .leading, spacing: 6) {
                    Text(e.show).font(.headline)
                    Text("\(e.venue) · 반경 \(meters(e.r)) · \(e.gate)번 입구").font(.footnote).foregroundColor(.muted)
                    Text("입장 \(clock(e.from)) ~ \(clock(e.to))").font(.footnote).foregroundColor(.muted).monospacedDigit()
                    HStack {
                        Button("수정") { editing = e }.buttonStyle(.bordered)
                        Button("티콧24로 보내기") { if let u = AdminStore.shopURL(e) { openURL(u) } }.buttonStyle(.borderedProminent)
                    }
                    .font(.subheadline)
                }
                .padding(.vertical, 4)
                .swipeActions { Button("삭제", role: .destructive) { store.delete(e) } }
            }
        }
        .navigationTitle("공연 관리")
        .toolbar {
            Button { editing = newEvent() } label: { Image(systemName: "plus") }
        }
        .sheet(item: $editing) { e in EventEditor(original: e) }
    }

    private func newEvent() -> ShowEvent {
        let start = ((Entrust.now() + 120) / 60) * 60
        return ShowEvent(id: "ev_" + Entrust.randomB64(6), show: "새 공연", venue: "시험 공연장", lat: 37.5665, lon: 126.978,
                         r: 200, from: start, to: start + 3 * 3600, gate: "1", price: 55000, category: "콘서트")
    }
}

struct VenuePin: Identifiable {
    let id = 0
    let c: CLLocationCoordinate2D
}

struct EventEditor: View {
    let original: ShowEvent
    @EnvironmentObject var store: AdminStore
    @EnvironmentObject var loc: LocationModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var show = ""
    @State private var venue = ""
    @State private var category = "콘서트"
    @State private var lat = ""
    @State private var lon = ""
    @State private var r = 200
    @State private var from = Date()
    @State private var to = Date()
    @State private var gate = "1"
    @State private var price = 55000
    @State private var waitingHere = false
    @State private var error = ""

    private var coord: CLLocationCoordinate2D? {
        guard let a = Double(lat), let b = Double(lon), a >= -90, a <= 90, b >= -180, b <= 180 else { return nil }
        return CLLocationCoordinate2D(latitude: a, longitude: b)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("공연") {
                    TextField("공연 이름", text: $show)
                    Picker("분류", selection: $category) {
                        ForEach(["콘서트", "뮤지컬", "연극", "클래식", "전시"], id: \.self) { Text($0) }
                    }
                    Picker("기본 가격(S석)", selection: $price) {
                        ForEach([33000, 55000, 77000, 99000], id: \.self) { Text(won($0)).tag($0) }
                    }
                }
                Section {
                    TextField("장소 이름", text: $venue)
                    Button {
                        waitingHere = true
                        loc.start()
                        useHere()
                    } label: { Label(waitingHere ? "위치 찾는 중…" : "지금 내 위치를 공연장으로", systemImage: "location.fill") }
                    HStack {
                        TextField("위도", text: $lat).keyboardType(.numbersAndPunctuation)
                        TextField("경도", text: $lon).keyboardType(.numbersAndPunctuation)
                    }
                    .font(.body.monospacedDigit())
                    if let c = coord {
                        Map(coordinateRegion: .constant(MKCoordinateRegion(center: c, latitudinalMeters: Double(r) * 5, longitudinalMeters: Double(r) * 5)),
                            annotationItems: [VenuePin(c: c)]) { p in MapMarker(coordinate: p.c, tint: .red) }
                            .frame(height: 180)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    Picker("입장 가능 반경", selection: $r) {
                        ForEach([50, 100, 200, 500, 1000, 3000], id: \.self) { Text(meters($0)).tag($0) }
                    }
                } header: { Text("입장 QR이 열리는 장소") } footer: {
                    Text("관객 폰이 이 지점에서 반경 안에 있을 때만 입장 QR이 열려요(GPS 오차 50m까지 봐줌).")
                }
                Section {
                    DatePicker("열림", selection: $from)
                    DatePicker("닫힘", selection: $to)
                    HStack {
                        quick("지금", 0)
                        quick("2분 뒤", 2)
                        quick("10분 뒤", 10)
                    }
                    Picker("담당 입구", selection: $gate) {
                        ForEach(["1", "2", "3"], id: \.self) { Text("\($0)번").tag($0) }
                    }
                } header: { Text("입장 QR이 열리는 시간") } footer: {
                    Text("이미 팔린 표에는 산 때의 조건이 서명되어 있어요. 바꾼 조건은 이후에 파는 표부터 들어가요.")
                }
                if !error.isEmpty { Text(error).foregroundColor(.badRed) }
                Section {
                    Button("저장") { if let e = build() { store.upsert(e); dismiss() } }
                    Button("저장하고 티콧24로 보내기") {
                        if let e = build() {
                            store.upsert(e)
                            if let u = AdminStore.shopURL(e) { openURL(u) }
                            dismiss()
                        }
                    }
                    .fontWeight(.bold)
                }
            }
            .navigationTitle(original.show == "새 공연" ? "새 공연" : "공연 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("닫기") { dismiss() } } }
            .onAppear { load() }
            .onChange(of: loc.location) { _ in if waitingHere { useHere() } }
            .onDisappear { loc.stop() }
        }
    }

    private func quick(_ title: String, _ minutes: Int) -> some View {
        Button(title) {
            let start = Date(timeIntervalSince1970: TimeInterval(((Entrust.now() + minutes * 60) / 60) * 60))
            from = start
            to = start.addingTimeInterval(3 * 3600)
        }
        .buttonStyle(.bordered)
        .font(.footnote)
    }

    private func useHere() {
        guard let l = loc.location, Date().timeIntervalSince(l.timestamp) < 30, l.horizontalAccuracy > 0 else { return }
        lat = String(format: "%.6f", l.coordinate.latitude)
        lon = String(format: "%.6f", l.coordinate.longitude)
        if venue == "시험 공연장" || venue.isEmpty { venue = "내 위치(시험)" }
        waitingHere = false
    }

    private func load() {
        show = original.show; venue = original.venue; category = original.category ?? "콘서트"
        lat = String(format: "%.6f", original.lat); lon = String(format: "%.6f", original.lon)
        r = original.r; gate = original.gate; price = original.price
        from = Date(timeIntervalSince1970: TimeInterval(original.from))
        to = Date(timeIntervalSince1970: TimeInterval(original.to))
    }

    private func build() -> ShowEvent? {
        error = ""
        guard let c = coord else { error = "위치(위도 · 경도)를 확인해 주세요."; return nil }
        let f = Int(from.timeIntervalSince1970), t = Int(to.timeIntervalSince1970)
        guard t > f else { error = "닫힘은 열림보다 뒤여야 해요."; return nil }
        guard t - f <= 7 * 24 * 3600 else { error = "입장 시간은 7일 안으로 정해 주세요."; return nil }
        let name = show.trimmingCharacters(in: .whitespaces)
        return ShowEvent(id: original.id, show: String((name.isEmpty ? "새 공연" : name).prefix(60)),
                         venue: String((venue.isEmpty ? "시험 공연장" : venue).prefix(40)),
                         lat: c.latitude, lon: c.longitude, r: r, from: f, to: t, gate: gate, price: price, category: category)
    }
}

struct GateView: View {
    @EnvironmentObject var store: AdminStore
    @State private var scanning = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("입구", selection: $store.gate) {
                    ForEach(["1", "2", "3"], id: \.self) { Text("\($0)번 입구").tag($0) }
                }
                .pickerStyle(.segmented)
                .onChange(of: store.gate) { _ in store.save() }

                if let v = store.verdict {
                    VStack(spacing: 6) {
                        Text(v.title).font(.system(size: 38, weight: .heavy))
                        Text(v.text).font(.system(size: 17, weight: .semibold)).multilineTextAlignment(.center)
                    }
                    .foregroundColor(v.kind == 2 ? .ink : .white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 22).padding(.horizontal, 14)
                    .background(v.kind == 0 ? Color.okGreen : v.kind == 1 ? Color.badRed : Color(red: 0.94, green: 0.64, blue: 0.29))
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .onTapGesture { store.verdict = nil }
                }

                if scanning {
                    QRScannerView { store.handle($0) }
                        .frame(height: 340)
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.8), lineWidth: 3).padding(70))
                }
                Button(scanning ? "카메라 끄기" : "카메라 켜기") { scanning.toggle() }.font(.subheadline.weight(.semibold))

                HStack {
                    stat("예매처", store.shops.count)
                    stat("등록된 표", store.locks.count)
                    stat("입장", store.entered.count)
                    stat("취소", store.cancelled.count)
                }
                Text("읽는 것: 예매처 등록(티콧24 '연동') · 표 등록(ENTRUST) · 입장 QR · 취소 알림. 같은 아이폰의 앱에서 넘긴 것도 여기서 판정해요.")
                    .font(.footnote).foregroundColor(.muted)
                ForEach(Array(store.log.suffix(10).reversed())) { l in
                    HStack(alignment: .top) {
                        Text(clockSec(l.at)).font(.caption).foregroundColor(.muted).monospacedDigit()
                        Text(l.text).font(.caption).foregroundColor(l.ok ? .ink : .badRed)
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("검표")
        .onDisappear { scanning = false }
        .onAppear { scanning = true }
    }

    private func stat(_ k: String, _ n: Int) -> some View {
        VStack(spacing: 2) {
            Text("\(n)").font(.system(size: 22, weight: .heavy)).monospacedDigit()
            Text(k).font(.caption).foregroundColor(.muted)
        }
        .frame(maxWidth: .infinity)
    }
}

func clockSec(_ sec: Int) -> String {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(sec)))
}

struct StatusView: View {
    @EnvironmentObject var store: AdminStore
    @State private var confirm = false

    var body: some View {
        List {
            Section("등록된 예매처") {
                if store.shops.isEmpty { Text("없음 — 티콧24 › ENTRUST 연동 › '관리자 앱에 등록'").foregroundColor(.muted) }
                ForEach(Array(store.shops.values)) { s in
                    VStack(alignment: .leading) { Text(s.name).font(.headline); Text("지문 \(s.id)").font(.caption).foregroundColor(.muted) }
                }
            }
            Section("등록된 표") {
                if store.locks.isEmpty { Text("없음").foregroundColor(.muted) }
                ForEach(Array(store.locks.values), id: \.terms.ck) { l in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(l.terms.show) · \(l.terms.seat)").font(.subheadline.weight(.semibold))
                            Text("\(l.terms.no) · \(l.terms.gate)번 입구 · \(l.org.hasPrefix("ios-app") ? "아이폰 앱(보안 칩)" : "웹")").font(.caption).foregroundColor(.muted)
                        }
                        Spacer()
                        if store.cancelled.contains(l.terms.ck) { Badge2(text: "취소", color: .muted) }
                        else if store.entered[l.terms.ck] != nil { Badge2(text: "입장", color: .okGreen) }
                        else { Badge2(text: "대기", color: .accentBlue) }
                    }
                }
            }
            Section {
                Button("검표 기록 모두 지우기", role: .destructive) { confirm = true }
            } footer: {
                Text("기록은 이 아이폰에만 저장돼요. 한 입구에는 검표 기기 한 대를 권해요.")
            }
        }
        .navigationTitle("현황")
        .confirmationDialog("등록된 예매처 · 표 · 입장 기록을 모두 지울까요?", isPresented: $confirm, titleVisibility: .visible) {
            Button("모두 지우기", role: .destructive) { store.resetGate() }
        }
    }
}

struct Badge2: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.caption.weight(.bold)).padding(.horizontal, 8).padding(.vertical, 3)
            .foregroundColor(color).background(color.opacity(0.12)).clipShape(Capsule())
    }
}
