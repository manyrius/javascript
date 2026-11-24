import Foundation
import UIKit

final class Provisioning: NSObject, URLSessionTaskDelegate {
    private lazy var session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
    enum Credential { case pending, revoked, approved(Envelope) }

    // Credentials and enrollment tokens must never follow a redirect to another host.
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }

    func request(_ action: String, fields: [String: String], post: Bool = false) async throws -> (Int, Data) {
        var url = URLComponents(url: Settings.api, resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "action", value: action)]
        let items = fields.map { URLQueryItem(name: $0.key, value: $0.value) }
        if !post { url.queryItems! += items }
        var request = URLRequest(url: url.url!)
        request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if post {
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            var body = URLComponents(); body.queryItems = items
            request.httpBody = Data((body.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppFailure("Risposta Aruba non valida.") }
        return (http.statusCode, data)
    }

    func enroll(token: String, name: String) async throws -> String {
        let (modulus, exponent) = try DeviceIdentity.publicParameters()
        let model = await MainActor.run { UIDevice.current.model }
        let (status, data) = try await request("enroll", fields: ["token": token, "modulus": modulus,
            "exponent": exponent, "model": model, "brand": "Apple", "phoneName": String(name.prefix(100))], post: true)
        guard status == 200 else { throw AppFailure("Attivazione rifiutata (HTTP \(status)). Verifica il QR e riprova.") }
        struct Reply: Decodable { let deviceId: String }
        let id = try JSONDecoder().decode(Reply.self, from: data).deviceId
        guard id.count == 32, id.allSatisfy({ $0.isHexDigit }) else { throw AppFailure("Identificativo dispositivo non valido.") }
        try DeviceIdentity.saveID(id)
        return id
    }

    func credential(_ id: String) async throws -> Credential {
        let (status, data) = try await request("credential", fields: ["id": id])
        let reply = try? JSONSerialization.jsonObject(with: data) as? [String: String]
        switch status {
        case 200: return .approved(try JSONDecoder().decode(Envelope.self, from: data))
        case 202 where reply?["status"] == "pending": return .pending
        case 403 where reply?["status"] == "revoked": return .revoked
        default: throw AppFailure("Servizio di attivazione non disponibile (HTTP \(status)).")
        }
    }

    func approved(_ id: String) async throws -> Bool {
        let (status, data) = try await request("heartbeat", fields: ["id": id])
        guard status == 200 else { throw AppFailure("Verifica autorizzazione non disponibile.") }
        struct Reply: Decodable { let status: String }
        return try JSONDecoder().decode(Reply.self, from: data).status == "Approved"
    }
}
