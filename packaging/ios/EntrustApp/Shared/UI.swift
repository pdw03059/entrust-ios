// 세 앱(ENTRUST · 티켓24 · 관리자)이 함께 쓰는 색 · 글자 · 버튼 · 카드 · QR 그림.
// 색은 디자인 기준(탑승권): 종이색 바탕 · 파랑 한 가지 · 초록(통과) · 빨강(거절).
import SwiftUI
import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

extension Color {
    static let paper = Color(red: 0xF4 / 255, green: 0xF2 / 255, blue: 0xEC / 255)
    static let ink = Color(red: 0x14 / 255, green: 0x17 / 255, blue: 0x1C / 255)
    static let muted = Color(red: 0x5F / 255, green: 0x66 / 255, blue: 0x70 / 255)
    static let accentBlue = Color(red: 0x1F / 255, green: 0x45 / 255, blue: 0xC9 / 255)
    static let okGreen = Color(red: 0x0E / 255, green: 0x7A / 255, blue: 0x55 / 255)
    static let badRed = Color(red: 0xC2 / 255, green: 0x35 / 255, blue: 0x2B / 255)
    static let warnOrange = Color(red: 0xA8 / 255, green: 0x59 / 255, blue: 0x0F / 255)
}

func qrImage(_ text: String) -> UIImage? {
    let f = CIFilter.qrCodeGenerator()
    f.message = Data(text.utf8)
    f.correctionLevel = "L"
    guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else { return nil }
    guard let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
    return UIImage(cgImage: cg)
}

func meters(_ m: Int) -> String { m >= 1000 ? String(format: "%.1fkm", Double(m) / 1000) : "\(m)m" }

func dur(_ s: Int) -> String {
    if s >= 3600 { return "\(s / 3600)시간 \((s % 3600) / 60)분" }
    if s >= 60 { return "\(s / 60)분 \(s % 60)초" }
    return "\(s)초"
}

func clock(_ sec: Int) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ko_KR")
    f.dateFormat = "M/d HH:mm"
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(sec)))
}

func won(_ n: Int) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    return (f.string(from: NSNumber(value: n)) ?? "\(n)") + "원"
}

struct PrimaryButton: View {
    let title: String
    var disabled = false
    var color: Color = .accentBlue
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 17, weight: .bold)).frame(maxWidth: .infinity, minHeight: 54)
        }
        .foregroundColor(.white)
        .background(color.opacity(disabled ? 0.45 : 1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .disabled(disabled)
    }
}

struct GhostButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 16, weight: .semibold)).frame(maxWidth: .infinity, minHeight: 50)
        }
        .foregroundColor(.ink)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.black.opacity(0.18)))
    }
}

struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .shadow(color: .black.opacity(0.08), radius: 16, x: 0, y: 8)
    }
}

struct InfoCell: View {
    let k: String
    let v: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(k).font(.caption.weight(.semibold)).foregroundColor(.muted)
            Text(v).font(.system(size: 16, weight: .bold)).monospacedDigit()
        }
    }
}

struct TicketCard: View {
    let t: Terms
    var body: some View {
        Card {
            Text(t.show).font(.system(size: 20, weight: .heavy))
            Text("예매번호 \(t.no) · \(t.venue)").font(.footnote).foregroundColor(.muted)
            Divider()
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .leading), count: 3), alignment: .leading, spacing: 10) {
                InfoCell(k: "입구", v: t.gate)
                InfoCell(k: "좌석", v: t.seat)
                InfoCell(k: "인원", v: "\(t.qty)명")
                InfoCell(k: "입장 시작", v: clock(t.from))
                InfoCell(k: "입장 마감", v: clock(t.to))
                InfoCell(k: "반경", v: meters(Int(t.r)))
            }
        }
    }
}

/// 큰 QR 그림(흰 여백 포함)
struct QRView: View {
    let image: UIImage?
    var body: some View {
        Group {
            if let img = image {
                Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 200)
            }
        }
        .padding(14)
        .background(Color.white)
    }
}
