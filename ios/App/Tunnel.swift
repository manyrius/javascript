import Foundation
import NetworkExtension

@MainActor
final class Tunnel {
    private var manager: NETunnelProviderManager?

    func connect(profile: Data) async throws {
        let managers = try await NETunnelProviderManager.loadAllFromPreferences()
        try Task.checkCancellation()
        let selected = managers.first {
            ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == Settings.tunnelID
        } ?? NETunnelProviderManager()
        manager = selected
        selected.connection.stopVPNTunnel()
        for _ in 0..<30 {
            if [.disconnected, .invalid].contains(selected.connection.status) { break }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        try Task.checkCancellation()
        let configuration = NETunnelProviderProtocol()
        configuration.providerBundleIdentifier = Settings.tunnelID
        configuration.serverAddress = "SIB SmartHub"
        configuration.disconnectOnSleep = true
        selected.protocolConfiguration = configuration
        selected.localizedDescription = "SIB SmartHub"
        selected.isOnDemandEnabled = false
        selected.isEnabled = true
        try await selected.saveToPreferences()
        try await selected.loadFromPreferences()
        try Task.checkCancellation()
        // The decrypted profile travels only in the transient start message.
        try selected.connection.startVPNTunnel(options: ["ovpn": profile as NSData])
        for _ in 0..<60 {
            try Task.checkCancellation()
            if selected.connection.status == .connected { return }
            if [.invalid, .disconnected].contains(selected.connection.status) {
                throw AppFailure("Collegamento VPN fallito. Verifica profilo e server VPN.")
            }
            try ping()
            try await Task.sleep(nanoseconds: 1_000_000_000)
        }
        throw AppFailure("Il server VPN non ha risposto entro un minuto.")
    }

    func ping() throws {
        guard let connection = manager?.connection as? NETunnelProviderSession else {
            throw AppFailure("Sessione VPN non disponibile.")
        }
        try connection.sendProviderMessage(Data("foreground".utf8))
    }

    var connected: Bool { manager?.connection.status == .connected }
    func stop() { manager?.connection.stopVPNTunnel() }
}
