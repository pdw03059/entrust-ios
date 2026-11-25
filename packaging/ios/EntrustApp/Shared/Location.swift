// 실제 GPS: 공연장 반경 안인지 확인. 위치는 폰 밖으로 보내지 않는다.
import Foundation
import CoreLocation

final class LocationModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var location: CLLocation?
    @Published var status: CLAuthorizationStatus = .notDetermined
    @Published var lastError: String?
    private let manager = CLLocationManager()

    override init() {
        super.init()
        status = manager.authorizationStatus
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
    }

    func start() {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
    }

    func stop() { manager.stopUpdatingLocation() }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        status = manager.authorizationStatus
        if status == .authorizedWhenInUse || status == .authorizedAlways { manager.startUpdatingLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let l = locations.last { location = l; lastError = nil }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        lastError = error.localizedDescription
    }
}

enum PlaceCheck {
    case ok(dist: Int)
    case far(dist: Int)
    case inaccurate(acc: Int)
    case simulated
    case denied
    case waiting
    case stale

    var isOK: Bool { if case .ok = self { return true } else { return false } }

    static func check(_ t: Terms, location: CLLocation?, status: CLAuthorizationStatus) -> PlaceCheck {
        if status == .denied || status == .restricted { return .denied }
        guard let l = location else { return .waiting }
        if Date().timeIntervalSince(l.timestamp) > 60 { return .stale }
        if l.sourceInformation?.isSimulatedBySoftware == true { return .simulated } // 위치 조작 앱 · 개발 도구로 꾸민 위치
        if l.horizontalAccuracy < 0 || l.horizontalAccuracy > Entrust.maxAccuracy { return .inaccurate(acc: Int(max(0, l.horizontalAccuracy))) }
        let dist = l.distance(from: CLLocation(latitude: t.lat, longitude: t.lon))
        if dist - min(l.horizontalAccuracy, 50) > t.r { return .far(dist: Int(dist)) }
        return .ok(dist: Int(dist))
    }
}
