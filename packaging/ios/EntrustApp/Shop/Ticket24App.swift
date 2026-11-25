// 티콧24 — 시험용 가상 예매처 앱. 실제 예매처 앱(공연 · 교통 예매)의 흔한 흐름을 그대로 따른다:
//   홈(배너 · 분류 · 목록) → 상품 상세(공연정보 · 판매정보) → 예매(① 회차 ② 좌석 ③ 결제) → 예매 완료(모바일 티켓) → 예매확인 · 취소
// ENTRUST 를 아는 곳은 EntrustBridge.swift 하나뿐(실제 고객사 앱에 붙일 때 그 파일만 옮김).
// 결제 · 회원 · 개인정보는 없다(가상 결제).
import SwiftUI
import UIKit

extension Color {
    static let t24 = Color(red: 0xEE / 255, green: 0x2E / 255, blue: 0x3A / 255)      // 티콧24 브랜드
    static let t24Soft = Color(red: 0xFD / 255, green: 0xEC / 255, blue: 0xED / 255)
    static let line = Color.black.opacity(0.08)
    static let bg24 = Color(red: 0xF6 / 255, green: 0xF6 / 255, blue: 0xF8 / 255)
}

@main
struct Ticket24App: App {
    @StateObject private var store = ShopStore()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .onOpenURL { store.handle($0) }
        }
    }
}

/* ───── 데이터 ───── */
enum OrderStatus: String, Codable { case paid, sent, cancelled }

struct Order: Codable, Identifiable {
    var id: String            // 예매번호
    var eventId: String
    var show: String
    var venue: String
    var round: Int            // 회차 시작 시각
    var seats: [String]
    var amount: Int
    var method: String
    var at: Int
    var ck: String
    var token: String
    var status: OrderStatus
}

@MainActor
final class ShopStore: ObservableObject {
    @Published var events: [ShowEvent] = []
    @Published var orders: [Order] = []
    @Published var sold: [String: [String]] = [:]
    @Published var toast: String?
    @Published var tab = 0
    private let key = "ticket24.store.v1"

    private struct Saved: Codable { var events: [ShowEvent]; var orders: [Order]; var sold: [String: [String]]; var seq: Int }
    private var seq = 0

    init() {
        if let d = UserDefaults.standard.data(forKey: key), let s = try? JSONDecoder().decode(Saved.self, from: d) {
            events = s.events; orders = s.orders; sold = s.sold; seq = s.seq
        }
        if events.isEmpty { events = [ShowEvent.demo()] }
    }

    private func save() {
        let s = Saved(events: events, orders: orders, sold: sold, seq: seq)
        if let d = try? JSONEncoder().encode(s) { UserDefaults.standard.set(d, forKey: key) }
    }

    /// ENTRUST 관리자 앱이 보낸 공연(ticket24://event#e=…)
    func handle(_ url: URL) {
        guard let e = Entrust.urlParam(url, "e").flatMap(ShowEvent.decode) else { return }
        events.removeAll { $0.id == e.id || $0.id == "ev_demo" }
        events.insert(e, at: 0)
        tab = 0
        toast = "관리자 앱에서 공연을 받았어요: \(e.show)"
        save()
    }

    nonisolated static func grade(_ seat: String) -> String { seat.hasPrefix("A") || seat.hasPrefix("B") ? "R" : "S" }
    nonisolated static func price(_ e: ShowEvent, seat: String) -> Int { grade(seat) == "R" ? e.price + 22000 : e.price }

    func book(_ e: ShowEvent, seats: [String], method: String) throws -> Order {
        seq += 1
        let f = DateFormatter(); f.dateFormat = "yyMMdd"
        let no = "T" + f.string(from: Date()) + String(format: "%04d", seq)
        let (ck, token) = try EntrustBridge.issueTicket(EntrustOrderInput(
            orderNo: no, showTitle: e.show, venueName: e.venue, seats: seats, gate: e.gate,
            venueLat: e.lat, venueLon: e.lon, radiusM: e.r, entryFrom: e.from, entryTo: e.to))
        let amount = seats.reduce(0) { $0 + ShopStore.price(e, seat: $1) }
        let o = Order(id: no, eventId: e.id, show: e.show, venue: e.venue, round: e.from, seats: seats, amount: amount,
                      method: method, at: Entrust.now(), ck: ck, token: token, status: .paid)
        orders.insert(o, at: 0)
        sold[e.id, default: []].append(contentsOf: seats)
        save()
        return o
    }

    func markSent(_ id: String) {
        guard let i = orders.firstIndex(where: { $0.id == id }), orders[i].status == .paid else { return }
        orders[i].status = .sent
        save()
    }

    func cancel(_ id: String) -> URL? {
        guard let i = orders.firstIndex(where: { $0.id == id }), orders[i].status != .cancelled else { return nil }
        orders[i].status = .cancelled
        let seats = orders[i].seats
        sold[orders[i].eventId]?.removeAll { seats.contains($0) }
        save()
        return EntrustBridge.cancelURL(ck: orders[i].ck)
    }
}

/* ───── 공통 조각 ───── */
/// 포스터(사진 대신 공연마다 다른 색 · 큰 글자)
struct Poster: View {
    let e: ShowEvent
    var big = false
    private var colors: [Color] {
        let palettes: [[Color]] = [
            [Color(red: 0.10, green: 0.13, blue: 0.30), Color(red: 0.93, green: 0.18, blue: 0.23)],
            [Color(red: 0.05, green: 0.25, blue: 0.22), Color(red: 0.95, green: 0.70, blue: 0.20)],
            [Color(red: 0.12, green: 0.12, blue: 0.14), Color(red: 0.12, green: 0.27, blue: 0.79)],
            [Color(red: 0.36, green: 0.10, blue: 0.16), Color(red: 0.98, green: 0.55, blue: 0.35)],
        ]
        let h = e.id.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        return palettes[h % palettes.count]
    }
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
            Circle().stroke(Color.white.opacity(0.18), lineWidth: big ? 28 : 14)
                .frame(width: big ? 260 : 120).offset(x: big ? 150 : 60, y: big ? -110 : -50)
            VStack(alignment: .leading, spacing: 4) {
                Text((e.category ?? "공연").uppercased()).font(.system(size: big ? 13 : 9, weight: .bold)).foregroundColor(.white.opacity(0.8))
                Text(e.show).font(.system(size: big ? 26 : 14, weight: .heavy)).foregroundColor(.white).lineLimit(3)
                if big { Text(e.venue).font(.subheadline.weight(.semibold)).foregroundColor(.white.opacity(0.85)) }
            }
            .padding(big ? 20 : 10)
        }
        .clipped()
    }
}

struct Badge: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.caption2.weight(.bold)).padding(.horizontal, 7).padding(.vertical, 3)
            .foregroundColor(color).background(color.opacity(0.12)).clipShape(Capsule())
    }
}

struct BottomBar<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 12) { content }.padding(.horizontal, 16).padding(.vertical, 10)
        }
        .background(Color.white)
    }
}

func period(_ e: ShowEvent) -> String { "\(clock(e.from)) ~ \(clock(e.to))" }

/* ───── 화면 ───── */
struct RootView: View {
    @EnvironmentObject var store: ShopStore
    var body: some View {
        TabView(selection: $store.tab) {
            NavigationStack { HomeView() }
                .tabItem { Label("홈", systemImage: "house") }.tag(0)
            NavigationStack { MyTicketsView() }
                .tabItem { Label("예매확인", systemImage: "ticket") }.tag(1)
            NavigationStack { PartnerView() }
                .tabItem { Label("ENTRUST 연동", systemImage: "link") }.tag(2)
        }
        .tint(.t24)
        .overlay(alignment: .top) {
            if let t = store.toast {
                Text(t).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Color.ink.opacity(0.92)).clipShape(Capsule()).padding(.top, 8)
                    .onTapGesture { store.toast = nil }
                    .task { try? await Task.sleep(nanoseconds: 3_000_000_000); store.toast = nil }
            }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var store: ShopStore
    @State private var query = ""
    @State private var category = "전체"
    private let categories = ["전체", "콘서트", "뮤지컬", "연극", "클래식", "전시"]

    private var list: [ShowEvent] {
        store.events.filter { e in
            (category == "전체" || (e.category ?? "콘서트") == category) &&
            (query.isEmpty || e.show.localizedCaseInsensitiveContains(query) || e.venue.localizedCaseInsensitiveContains(query))
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("티콧24").font(.system(size: 26, weight: .black)).foregroundColor(.t24)
                    Text("TICKET").font(.system(size: 11, weight: .bold)).foregroundColor(.muted)
                    Spacer()
                    Badge(text: "시험용", color: .muted)
                }
                HStack {
                    Image(systemName: "magnifyingglass").foregroundColor(.muted)
                    TextField("공연 · 장소 검색", text: $query)
                }
                .padding(12).background(Color.bg24).clipShape(RoundedRectangle(cornerRadius: 12))

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(categories, id: \.self) { c in
                            Button(c) { category = c }
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .foregroundColor(category == c ? .white : .ink)
                                .background(category == c ? Color.ink : Color.white)
                                .overlay(Capsule().stroke(Color.line))
                                .clipShape(Capsule())
                        }
                    }
                }

                if let top = list.first {
                    NavigationLink { ShowDetailView(e: top) } label: {
                        Poster(e: top, big: true).frame(height: 230).clipShape(RoundedRectangle(cornerRadius: 18))
                    }
                }

                Text("지금 예매 가능").font(.system(size: 20, weight: .heavy))
                if list.isEmpty {
                    Text("조건에 맞는 공연이 없어요.").foregroundColor(.muted).frame(maxWidth: .infinity, minHeight: 80)
                }
                ForEach(Array(list.enumerated()), id: \.element.id) { i, e in
                    NavigationLink { ShowDetailView(e: e) } label: {
                        HStack(alignment: .top, spacing: 14) {
                            Poster(e: e).frame(width: 84, height: 112).clipShape(RoundedRectangle(cornerRadius: 8))
                            VStack(alignment: .leading, spacing: 5) {
                                HStack(spacing: 6) {
                                    Text("\(i + 1)").font(.system(size: 15, weight: .black)).foregroundColor(.t24)
                                    Badge(text: e.category ?? "콘서트", color: .t24)
                                    Badge(text: "모바일티켓", color: .accentBlue)
                                }
                                Text(e.show).font(.system(size: 16, weight: .bold)).foregroundColor(.ink).lineLimit(2).multilineTextAlignment(.leading)
                                Text(e.venue).font(.footnote).foregroundColor(.muted)
                                Text(period(e)).font(.footnote).foregroundColor(.muted).monospacedDigit()
                                Text("\(won(e.price))~").font(.system(size: 15, weight: .heavy)).foregroundColor(.ink)
                            }
                            Spacer(minLength: 0)
                        }
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
                Text("공연은 ENTRUST 관리자 앱에서 만들어 '티콧24로 보내기'를 누르면 여기에 올라와요.")
                    .font(.footnote).foregroundColor(.muted)
            }
            .padding(16)
        }
        .background(Color.white)
        .navigationBarHidden(true)
    }
}

struct ShowDetailView: View {
    let e: ShowEvent
    @State private var section = 0
    @State private var booking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Poster(e: e, big: true).frame(height: 300)
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 6) { Badge(text: e.category ?? "콘서트", color: .t24); Badge(text: "단독판매", color: .muted) }
                    Text(e.show).font(.system(size: 24, weight: .heavy))
                    Picker("", selection: $section) {
                        Text("공연정보").tag(0)
                        Text("판매정보").tag(1)
                    }
                    .pickerStyle(.segmented)
                    if section == 0 {
                        info("장소", e.venue)
                        info("공연기간", period(e))
                        info("관람시간", "120분(인터미션 없음)")
                        info("관람등급", "전체 관람가")
                        Divider()
                        Text("가격").font(.headline)
                        info("R석", won(e.price + 22000))
                        info("S석", won(e.price))
                    } else {
                        info("티켓 수령", "모바일 티켓(ENTRUST)")
                        info("입장 방법", "ENTRUST 앱의 입장 QR — 공연장 반경 \(meters(e.r)) 안 · 입장 시간에만 열림")
                        info("담당 입구", "\(e.gate)번 입구")
                        info("1인 최대", "3매")
                        info("취소", "공연 전까지 '예매확인'에서 취소(체험판: 수수료 없음)")
                    }
                }
                .padding(16)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            BottomBar {
                VStack(alignment: .leading, spacing: 2) {
                    Text("S석").font(.caption).foregroundColor(.muted)
                    Text("\(won(e.price))~").font(.system(size: 18, weight: .heavy))
                }
                PrimaryButton(title: "예매하기", color: .t24) { booking = true }
            }
        }
        .fullScreenCover(isPresented: $booking) { BookingFlow(e: e) }
    }

    private func info(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) {
            Text(k).font(.subheadline).foregroundColor(.muted).frame(width: 76, alignment: .leading)
            Text(v).font(.subheadline)
            Spacer(minLength: 0)
        }
    }
}

/* ───── 예매: ① 회차 ② 좌석 ③ 결제 ④ 완료 ───── */
struct BookingFlow: View {
    let e: ShowEvent
    @EnvironmentObject var store: ShopStore
    @Environment(\.dismiss) private var dismiss
    @State private var step = 1
    @State private var roundPicked = false
    @State private var seats: [String] = []
    @State private var method = "가상 신용카드"
    @State private var agree = false
    @State private var paying = false
    @State private var order: Order?
    @State private var error = ""

    private var total: Int { seats.reduce(0) { $0 + ShopStore.price(e, seat: $1) } }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if step < 4 { steps }
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        switch step {
                        case 1: roundStep
                        case 2: SeatMap(e: e, sold: store.sold[e.id] ?? [], picked: $seats)
                        case 3: payStep
                        default: if let o = order { DoneView(o: o) { dismiss() } }
                        }
                        if !error.isEmpty { Text(error).foregroundColor(.badRed).font(.subheadline.weight(.semibold)) }
                    }
                    .padding(16)
                }
                if step < 4 { bottom }
            }
            .background(Color.white)
            .navigationTitle(step == 4 ? "예매 완료" : e.show)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if step < 4 { Button("닫기") { dismiss() } }
                }
            }
        }
    }

    private var steps: some View {
        HStack(spacing: 0) {
            ForEach(Array(["회차", "좌석", "결제"].enumerated()), id: \.offset) { i, name in
                VStack(spacing: 6) {
                    Text("\(i + 1) \(name)").font(.subheadline.weight(step == i + 1 ? .bold : .regular))
                        .foregroundColor(step >= i + 1 ? .ink : .muted)
                    Rectangle().fill(step >= i + 1 ? Color.t24 : Color.line).frame(height: 3)
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 8)
    }

    private var roundStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("관람일 · 회차").font(.headline)
            Button { roundPicked = true } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("1회차").font(.subheadline.weight(.bold))
                        Text("입장 \(clock(e.from)) ~ \(clock(e.to))").font(.footnote).foregroundColor(.muted).monospacedDigit()
                    }
                    Spacer()
                    Text("잔여 \(96 - (store.sold[e.id]?.count ?? 0))석").font(.footnote.weight(.semibold)).foregroundColor(.t24)
                }
                .padding(14)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(roundPicked ? Color.t24 : Color.line, lineWidth: roundPicked ? 2 : 1))
            }
            .buttonStyle(.plain)
            Text("입장 QR은 위 입장 시간에만, \(e.venue) 반경 \(meters(e.r)) 안에서만 열려요(ENTRUST).").font(.footnote).foregroundColor(.muted)
        }
    }

    private var payStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("예매 확인").font(.headline)
            VStack(alignment: .leading, spacing: 6) {
                Text(e.show).font(.subheadline.weight(.bold))
                Text("\(clock(e.from)) · \(e.venue)").font(.footnote).foregroundColor(.muted)
                ForEach(seats, id: \.self) { s in
                    HStack { Text("\(ShopStore.grade(s))석 \(s)"); Spacer(); Text(won(ShopStore.price(e, seat: s))) }.font(.subheadline)
                }
            }
            .padding(14).background(Color.bg24).clipShape(RoundedRectangle(cornerRadius: 12))

            Text("티켓 수령 방법").font(.headline)
            HStack {
                Image(systemName: "qrcode").foregroundColor(.accentBlue)
                VStack(alignment: .leading, spacing: 2) {
                    Text("모바일 티켓 · ENTRUST").font(.subheadline.weight(.bold))
                    Text("예매 후 ENTRUST 앱으로 받아 Face ID로 잠가요. 캡처 · 양도 불가.").font(.footnote).foregroundColor(.muted)
                }
            }
            .padding(14).overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.line))

            Text("결제 수단").font(.headline)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(["가상 신용카드", "가상 계좌이체", "가상 간편결제", "가상 휴대폰"], id: \.self) { m in
                    Button(m) { method = m }
                        .font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 46)
                        .foregroundColor(method == m ? .t24 : .ink)
                        .background(method == m ? Color.t24Soft : Color.white)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(method == m ? Color.t24 : Color.line))
                }
            }
            Text("시험용 가상 결제예요. 카드 번호 · 개인정보를 받지 않고 돈도 나가지 않아요.").font(.footnote).foregroundColor(.muted)
            Toggle(isOn: $agree) { Text("예매 · 취소 규정 및 ENTRUST 모바일 티켓 이용에 동의").font(.subheadline) }
                .tint(.t24)
        }
    }

    private var bottom: some View {
        BottomBar {
            VStack(alignment: .leading, spacing: 2) {
                Text(seats.isEmpty ? "좌석 미선택" : "\(seats.count)매").font(.caption).foregroundColor(.muted)
                Text(won(total)).font(.system(size: 18, weight: .heavy)).monospacedDigit()
            }
            switch step {
            case 1: PrimaryButton(title: "좌석 선택", disabled: !roundPicked, color: .t24) { step = 2 }
            case 2: PrimaryButton(title: "다음", disabled: seats.isEmpty, color: .t24) { step = 3 }
            default: PrimaryButton(title: paying ? "결제 중…" : "\(won(total)) 결제하기", disabled: !agree || paying, color: .t24) { pay() }
            }
        }
    }

    private func pay() {
        error = ""
        paying = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000) // 결제 대행 응답을 흉내 냄
            do {
                order = try store.book(e, seats: seats, method: method)
                step = 4
            } catch {
                self.error = "결제 처리에 실패했어요. 공연 정보(장소 · 시간)를 관리자 앱에서 다시 보내 주세요."
            }
            paying = false
        }
    }
}

struct SeatMap: View {
    let e: ShowEvent
    let sold: [String]
    @Binding var picked: [String]
    private let rows = ["A", "B", "C", "D", "E", "F", "G", "H"]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("좌석 선택").font(.headline)
                Spacer()
                Text("최대 3매").font(.footnote).foregroundColor(.muted)
            }
            Text("STAGE").font(.caption.weight(.bold)).foregroundColor(.muted)
                .frame(maxWidth: .infinity, minHeight: 30).background(Color.bg24).clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(spacing: 6) {
                ForEach(rows, id: \.self) { r in
                    HStack(spacing: 4) {
                        Text(r).font(.caption2.weight(.bold)).foregroundColor(.muted).frame(width: 14)
                        ForEach(1...12, id: \.self) { c in
                            let id = "\(r)-\(c)"
                            seat(id, grade: ShopStore.grade(id))
                            if c == 6 { Spacer().frame(width: 10) }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity)
            HStack(spacing: 14) {
                legend(Color(red: 0.55, green: 0.36, blue: 0.96), "R석 \(won(e.price + 22000))")
                legend(Color(red: 0.13, green: 0.59, blue: 0.95), "S석 \(won(e.price))")
                legend(Color.gray.opacity(0.35), "선택불가")
            }
            .font(.caption)
            if !picked.isEmpty {
                Text("선택: " + picked.map { "\(ShopStore.grade($0))석 \($0)" }.joined(separator: ", ")).font(.subheadline.weight(.semibold))
            }
        }
    }

    private func seat(_ id: String, grade: String) -> some View {
        let taken = sold.contains(id)
        let on = picked.contains(id)
        let base = grade == "R" ? Color(red: 0.55, green: 0.36, blue: 0.96) : Color(red: 0.13, green: 0.59, blue: 0.95)
        return Button {
            if on { picked.removeAll { $0 == id } } else if picked.count < 3 { picked.append(id) }
        } label: {
            RoundedRectangle(cornerRadius: 4)
                .fill(taken ? Color.gray.opacity(0.35) : on ? Color.t24 : base.opacity(0.85))
                .frame(width: 20, height: 22)
                .overlay {
                    if on { Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).foregroundColor(.white) }
                }
        }
        .disabled(taken)
        .accessibilityLabel("\(grade)석 \(id)\(taken ? " 선택불가" : "")")
    }

    private func legend(_ c: Color, _ t: String) -> some View {
        HStack(spacing: 4) { RoundedRectangle(cornerRadius: 3).fill(c).frame(width: 12, height: 12); Text(t).foregroundColor(.muted) }
    }
}

struct DoneView: View {
    let o: Order
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 48)).foregroundColor(.t24)
            Text("예매가 완료됐어요").font(.system(size: 26, weight: .heavy))
            VStack(alignment: .leading, spacing: 8) {
                row("예매번호", o.id)
                row("공연", o.show)
                row("좌석", o.seats.map { "\(ShopStore.grade($0))석 \($0)" }.joined(separator: ", "))
                row("결제", "\(won(o.amount)) · \(o.method)")
            }
            .padding(14).background(Color.bg24).clipShape(RoundedRectangle(cornerRadius: 12))
            MobileTicketBox(o: o)
            GhostButton(title: "확인") { close() }
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) { Text(k).foregroundColor(.muted).frame(width: 64, alignment: .leading); Text(v).fontWeight(.semibold); Spacer(minLength: 0) }
            .font(.subheadline)
    }
}

/// 모바일 티켓 받기 — 실제 예매처 앱이라면 '예매 완료' · '예매 상세' 화면에 들어갈 상자
struct MobileTicketBox: View {
    let o: Order
    @EnvironmentObject var store: ShopStore
    @Environment(\.openURL) private var openURL
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "qrcode").foregroundColor(.accentBlue)
                Text("모바일 티켓").font(.headline)
                Spacer()
                Badge(text: o.status == .sent ? "ENTRUST로 보냄" : "받기 전", color: o.status == .sent ? .okGreen : .warnOrange)
            }
            Text("ENTRUST 앱으로 받아 Face ID로 잠가야 입장 QR이 열려요.").font(.footnote).foregroundColor(.muted)
            PrimaryButton(title: "ENTRUST로 표 보내기", color: .accentBlue) {
                if let u = EntrustBridge.handoffURL(token: o.token) {
                    openURL(u) { ok in if ok { store.markSent(o.id) } else { store.toast = "ENTRUST 앱이 설치돼 있지 않아요" } }
                }
            }
            Button(copied ? "복사했어요" : "표 코드 복사(다른 폰으로 받을 때)") {
                UIPasteboard.general.string = o.token
                copied = true
            }
            .font(.footnote.weight(.semibold)).foregroundColor(.accentBlue)
        }
        .padding(14)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.accentBlue.opacity(0.35)))
    }
}

struct MyTicketsView: View {
    @EnvironmentObject var store: ShopStore
    var body: some View {
        List {
            if store.orders.isEmpty {
                Text("예매 내역이 없어요.").foregroundColor(.muted)
            }
            ForEach(store.orders) { o in
                NavigationLink { OrderDetailView(id: o.id) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Badge(text: label(o.status), color: color(o.status))
                            Text(o.id).font(.caption).foregroundColor(.muted).monospacedDigit()
                        }
                        Text(o.show).font(.subheadline.weight(.bold))
                        Text("\(clock(o.round)) · \(o.seats.joined(separator: ", ")) · \(won(o.amount))").font(.footnote).foregroundColor(.muted)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("예매확인")
    }

    private func label(_ s: OrderStatus) -> String { s == .paid ? "예매완료" : s == .sent ? "티켓 수령" : "취소" }
    private func color(_ s: OrderStatus) -> Color { s == .paid ? .t24 : s == .sent ? .okGreen : .muted }
}

struct OrderDetailView: View {
    let id: String
    @EnvironmentObject var store: ShopStore
    @Environment(\.openURL) private var openURL
    @State private var confirm = false

    var body: some View {
        ScrollView {
            if let o = store.orders.first(where: { $0.id == id }) {
                VStack(alignment: .leading, spacing: 16) {
                    Text(o.show).font(.system(size: 22, weight: .heavy))
                    VStack(alignment: .leading, spacing: 6) {
                        Text("예매번호 \(o.id)").monospacedDigit()
                        Text("관람 \(clock(o.round)) · \(o.venue)")
                        Text("좌석 \(o.seats.joined(separator: ", ")) · \(won(o.amount))")
                    }
                    .font(.subheadline)
                    if o.status == .cancelled {
                        Text("취소된 예매예요. 검표기에 취소 알림을 보냈다면 입장 QR이 거절돼요.").font(.subheadline).foregroundColor(.muted)
                    } else {
                        MobileTicketBox(o: o)
                        GhostButton(title: "예매 취소") { confirm = true }
                    }
                }
                .padding(16)
            }
        }
        .navigationTitle("예매 상세")
        .confirmationDialog("이 예매를 취소할까요?", isPresented: $confirm, titleVisibility: .visible) {
            Button("예매 취소", role: .destructive) {
                if let u = store.cancel(id) { openURL(u) } // 같은 아이폰의 관리자(검표) 앱에 취소 알림
            }
        } message: {
            Text("취소하면 ENTRUST 관리자(검표) 앱에 취소 알림을 보내요.")
        }
    }
}

struct PartnerView: View {
    @Environment(\.openURL) private var openURL
    @State private var code = EntrustBridge.registrationCode()
    @State private var confirmReset = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("ENTRUST 연동").font(.system(size: 26, weight: .heavy))
                Text("티콧24가 판 표만 검표기에서 통과하도록, 처음 한 번 이 예매처를 ENTRUST 관리자(검표) 앱에 등록해요.")
                    .font(.subheadline).foregroundColor(.muted)
                PrimaryButton(title: "관리자 앱에 등록(같은 아이폰)", color: .t24) {
                    if let u = EntrustBridge.registrationURL() { openURL(u) }
                }
                Card {
                    Text("다른 기기의 검표기라면 이 QR을 찍어요").font(.subheadline.weight(.semibold))
                    QRView(image: qrImage(code))
                    Text("예매처 지문 \(EntrustBridge.partnerId)").font(.footnote).foregroundColor(.muted).monospacedDigit()
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("연동 구조").font(.headline)
                    Text("이 앱에서 ENTRUST 를 아는 코드는 EntrustBridge.swift 하나예요. 실제 예매처 앱에는 그 파일만 넣고 '예매 완료 · 예매 상세 · 예매 취소' 화면에서 부르면 됩니다. 서명은 실제로는 예매처 서버가 해요.")
                        .font(.footnote).foregroundColor(.muted)
                }
                GhostButton(title: "예매처 열쇠 새로 만들기") { confirmReset = true }
            }
            .padding(16)
        }
        .background(Color.white)
        .confirmationDialog("열쇠를 새로 만들면 이미 판 표는 검표기에서 거절돼요(다시 등록 필요).", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("새로 만들기", role: .destructive) {
                EntrustBridge.resetKey()
                code = EntrustBridge.registrationCode()
            }
        }
    }
}
