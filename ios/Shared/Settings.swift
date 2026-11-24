import Foundation

enum Settings {
    static let api = URL(string: "https://www.artsdomus.com/alborgo/stradioltre.ashx")!
    static let portal = URL(string: "http://192.168.1.34/ilborghetto/")!
    static let tunnelID = "it.benazzi.smarthub.ios.tunnel"
    static let group = "group.it.benazzi.smarthub.ios"

    static func acceptsPortal(_ url: URL) -> Bool {
        url.scheme == portal.scheme && url.host == portal.host &&
        (url.port ?? 80) == 80 && url.user == nil && url.password == nil &&
        url.standardized.path.hasPrefix("/ilborghetto/")
    }
}

struct AppFailure: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

struct ActivationQR: Decodable {
    let u: String
    let t: String

    static func parse(_ value: String) throws -> ActivationQR {
        let qr = try JSONDecoder().decode(Self.self, from: Data(value.utf8))
        guard let url = URL(string: qr.u), url.scheme == "https",
              ["artsdomus.com", "www.artsdomus.com"].contains(url.host?.lowercased() ?? ""),
              url.path == Settings.api.path, url.port == nil || url.port == 443,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              !qr.t.isEmpty, qr.t.count <= 256 else {
            throw AppFailure("Usa il QR generato dalla pagina Dispositivi di SIB.")
        }
        return qr
    }
}
