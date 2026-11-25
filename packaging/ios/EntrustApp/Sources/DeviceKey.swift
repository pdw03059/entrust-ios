// 표 잠금 열쇠: 아이폰 보안 칩(Secure Enclave) 안에서 만들고 밖으로 꺼낼 수 없다.
//  - ThisDeviceOnly: 백업 · iCloud 로 다른 기기에 옮겨지지 않음(웹 패스키의 한계를 없앰)
//  - biometryCurrentSet: 얼굴 · 지문으로만 사용, 기기 암호로는 못 씀. 얼굴 · 지문을 새로 등록하면 열쇠가 영구 무효 → 다시 받기
import Foundation
import CryptoKit
import Security
import LocalAuthentication

enum DeviceKeyError: LocalizedError {
    case noSecureEnclave, noBiometry, accessControl, missing
    var errorDescription: String? {
        switch self {
        case .noSecureEnclave: return "이 기기에는 보안 칩(Secure Enclave)이 없어요."
        case .noBiometry: return "Face ID(또는 Touch ID)를 켜 주세요. 설정 › Face ID 및 암호."
        case .accessControl: return "보안 설정을 만들 수 없어요."
        case .missing: return "잠금 열쇠가 없어요. 표를 다시 받아 주세요."
        }
    }
}

struct DeviceKey {
    private static let store = "entrust.deviceKey.v1"

    static var exists: Bool { UserDefaults.standard.data(forKey: store) != nil }

    /// 새 열쇠 만들기(같은 폰의 이전 표 열쇠는 지움). 돌려주는 값 = 공개 열쇠(65바이트)
    static func create() throws -> Data {
        guard SecureEnclave.isAvailable else { throw DeviceKeyError.noSecureEnclave }
        var laErr: NSError?
        guard LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &laErr) else { throw DeviceKeyError.noBiometry }
        guard let ac = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, [.privateKeyUsage, .biometryCurrentSet], nil) else {
            throw DeviceKeyError.accessControl
        }
        let key = try SecureEnclave.P256.Signing.PrivateKey(accessControl: ac)
        // dataRepresentation 은 이 폰 보안 칩만 풀 수 있는 '포장된' 값(열쇠 자체가 아님)
        UserDefaults.standard.set(key.dataRepresentation, forKey: store)
        return key.publicKey.x963Representation
    }

    static func publicKey() throws -> Data {
        guard let blob = UserDefaults.standard.data(forKey: store) else { throw DeviceKeyError.missing }
        return try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob).publicKey.x963Representation
    }

    /// 얼굴 확인 1번 → 서명(DER). 생체 정보가 바뀌었으면 여기서 실패한다.
    static func sign(_ message: Data, reason: String) throws -> Data {
        guard let blob = UserDefaults.standard.data(forKey: store) else { throw DeviceKeyError.missing }
        let ctx = LAContext()
        ctx.localizedReason = reason
        ctx.localizedFallbackTitle = "" // '암호 입력' 버튼 숨김(열쇠가 생체 전용)
        let key = try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob, authenticationContext: ctx)
        return try key.signature(for: message).derRepresentation
    }

    static func delete() { UserDefaults.standard.removeObject(forKey: store) }
}
