// 카메라 QR 읽기(아이폰 기본 기능 AVFoundation — 외부 라이브러리 없음)
import SwiftUI
import UIKit
import AVFoundation

struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let vc = ScannerController()
        vc.onCode = onCode
        return vc
    }

    func updateUIViewController(_ vc: ScannerController, context: Context) {
        vc.onCode = onCode
    }
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var last = ""
    private var lastAt = Date.distantPast
    private let note = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        note.textColor = .white
        note.numberOfLines = 0
        note.textAlignment = .center
        note.font = .systemFont(ofSize: 15, weight: .semibold)
        view.addSubview(note)
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: setup()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { if ok { self.setup(); self.start() } else { self.denied() } }
            }
        default: denied()
        }
    }

    private func denied() { note.text = "카메라 권한이 꺼져 있어요.\n설정 › ENTRUST 관리자 › 카메라를 켜 주세요." }

    private func setup() {
        guard preview == nil,
              let dev = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: dev),
              session.canAddInput(input) else { note.text = "카메라를 열 수 없어요."; return }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        guard session.canAddOutput(out) else { return }
        session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        out.metadataObjectTypes = [.qr]
        let p = AVCaptureVideoPreviewLayer(session: session)
        p.videoGravity = .resizeAspectFill
        view.layer.insertSublayer(p, at: 0)
        preview = p
    }

    private func start() {
        let s = session
        DispatchQueue.global(qos: .userInitiated).async { if !s.isRunning { s.startRunning() } }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        note.frame = view.bounds.insetBy(dx: 20, dy: 20)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        start()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let s = session
        DispatchQueue.global(qos: .userInitiated).async { if s.isRunning { s.stopRunning() } }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let o = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let s = o.stringValue else { return }
        if s == last && Date().timeIntervalSince(lastAt) < 3 { return } // 같은 QR 연달아 읽기 무시
        last = s
        lastAt = Date()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onCode?(s)
    }
}
