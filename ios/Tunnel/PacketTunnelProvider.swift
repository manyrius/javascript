import Foundation
import NetworkExtension
import TunnelKitCore
import TunnelKitOpenVPNCore
import TunnelKitOpenVPNManager
import TunnelKitOpenVPNAppExtension

final class PacketTunnelProvider: OpenVPNTunnelProvider {
    private var inMemoryProtocol: NETunnelProviderProtocol?
    private let leaseQueue = DispatchQueue(label: "SIB.foreground-lease")
    private var leaseTimer: DispatchSourceTimer?
    private var lastForeground = ProcessInfo.processInfo.systemUptime

    override var protocolConfiguration: NEVPNProtocol {
        inMemoryProtocol ?? super.protocolConfiguration
    }

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        do {
            guard let data = options?["ovpn"] as? Data,
                  let profile = String(data: data, encoding: .utf8) else {
                throw AppFailure("Apri SIB per autorizzare il collegamento.")
            }
            let parsed = try OpenVPN.ConfigurationParser.parsed(fromContents: profile)
            guard parsed.warning == nil else { throw AppFailure("Profilo VPN con opzioni non supportate su iPhone.") }
            var builder = parsed.configuration.builder()
            guard builder.authUserPass != true else {
                throw AppFailure("Il profilo VPN deve usare il certificato client senza password interattiva.")
            }
            builder.noPullMask = [.routes, .dns, .proxy]
            builder.routingPolicies = []
            builder.routes4 = [IPv4Settings.Route("192.168.1.34", "255.255.255.255", nil)]
            builder.routes6 = []
            builder.isDNSEnabled = false
            builder.isProxyEnabled = false
            let configuration = OpenVPN.ProviderConfiguration("SIB SmartHub", appGroup: Settings.group,
                                                              configuration: builder.build())
            inMemoryProtocol = try configuration.asTunnelProtocol(withBundleIdentifier: Settings.tunnelID, extra: nil)
            fallbackDNSServers = []
            leaseQueue.sync {
                self.lastForeground = ProcessInfo.processInfo.systemUptime
                let timer = DispatchSource.makeTimerSource(queue: self.leaseQueue)
                timer.schedule(deadline: .now() + 5, repeating: 5)
                timer.setEventHandler { [weak self] in
                    guard let self, ProcessInfo.processInfo.systemUptime - self.lastForeground > 20 else { return }
                    self.leaseTimer?.cancel()
                    self.leaseTimer = nil
                    self.cancelTunnelWithError(AppFailure("SIB non è più in primo piano."))
                }
                self.leaseTimer = timer
                timer.resume()
            }
            super.startTunnel(options: options) { error in
                if error != nil { self.clearLease() }
                completionHandler(error)
            }
        } catch { completionHandler(error) }
    }

    // Enforce the single host also when the server pushes broader network routes.
    override func setTunnelNetworkSettings(_ tunnelNetworkSettings: NETunnelNetworkSettings?,
                                          completionHandler: ((Error?) -> Void)? = nil) {
        if let settings = tunnelNetworkSettings as? NEPacketTunnelNetworkSettings {
            settings.ipv4Settings?.includedRoutes = [NEIPv4Route(destinationAddress: "192.168.1.34", subnetMask: "255.255.255.255")]
            settings.ipv4Settings?.excludedRoutes = []
            settings.ipv6Settings = nil
            settings.dnsSettings = nil
            settings.proxySettings = nil
        }
        super.setTunnelNetworkSettings(tunnelNetworkSettings, completionHandler: completionHandler)
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard messageData == Data("foreground".utf8) else { completionHandler?(nil); return }
        leaseQueue.async { self.lastForeground = ProcessInfo.processInfo.systemUptime }
        completionHandler?(Data("ok".utf8))
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        clearLease()
        super.stopTunnel(with: reason) {
            self.inMemoryProtocol = nil
            completionHandler()
        }
    }

    private func clearLease() {
        leaseQueue.async {
            self.leaseTimer?.cancel()
            self.leaseTimer = nil
        }
    }
}
