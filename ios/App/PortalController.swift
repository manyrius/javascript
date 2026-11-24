import UIKit
import WebKit

@MainActor
final class PortalController: UIViewController, WKNavigationDelegate {
    private let api = Provisioning()
    private let tunnel = Tunnel()
    private var work: Task<Void, Never>?
    private var enrolling = false
    private var pendingToken: String?
    private var active = false
    private var generation = 0
    private var web: WKWebView!
    private let panel = UIStackView()
    private let status = UILabel()
    private let name = UITextField()
    private let scan = UIButton(type: .system)
    private let retry = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .large)
    private let session = URLSession(configuration: .ephemeral)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.06, green: 0.20, blue: 0.17, alpha: 1)
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        web = WKWebView(frame: .zero, configuration: configuration)
        web.navigationDelegate = self
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)
        NSLayoutConstraint.activate([web.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            web.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: view.leadingAnchor), web.trailingAnchor.constraint(equalTo: view.trailingAnchor)])
        let title = UILabel()
        title.text = "SIB SmartHub"
        title.font = .systemFont(ofSize: 34, weight: .bold)
        title.textColor = UIColor(red: 0.85, green: 0.95, blue: 0.35, alpha: 1)
        title.textAlignment = .center
        status.textColor = .white
        status.numberOfLines = 0
        status.textAlignment = .center
        name.placeholder = "Nome del dispositivo o del proprietario"
        name.borderStyle = .roundedRect
        name.text = UIDevice.current.model
        name.autocorrectionType = .no
        scan.setTitle("Inquadra QR di attivazione", for: .normal)
        scan.addTarget(self, action: #selector(openScanner), for: .touchUpInside)
        retry.setTitle("Controlla di nuovo", for: .normal)
        retry.addTarget(self, action: #selector(restart), for: .touchUpInside)
        for button in [scan, retry] {
            button.configuration = .filled()
            button.tintColor = UIColor(red: 0.36, green: 0.50, blue: 0.14, alpha: 1)
        }
        spinner.color = .white
        panel.axis = .vertical
        panel.spacing = 22
        [title, status, spinner, name, scan, retry].forEach(panel.addArrangedSubview)
        panel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(panel)
        let preferredWidth = panel.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.85)
        preferredWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([panel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            panel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            preferredWidth,
            panel.widthAnchor.constraint(lessThanOrEqualToConstant: 500)])
        show("Attiva il dispositivo dal portale Dispositivi.", busy: false)
    }

    private func show(_ message: String, busy: Bool) {
        web.isHidden = true
        panel.isHidden = false
        status.text = message
        retry.isHidden = busy
        scan.isHidden = busy
        name.isHidden = busy
        if busy { spinner.startAnimating() } else { spinner.stopAnimating() }
    }

    func resume() {
        loadViewIfNeeded()
        active = true
        guard work == nil, !enrolling, presentedViewController == nil else { return }
        let current = generation
        work = Task {
            do {
                if let token = pendingToken {
                    enrolling = true
                    show("Registrazione dispositivo…", busy: true)
                    _ = try await api.enroll(token: token, name: name.text ?? UIDevice.current.model)
                    pendingToken = nil
                    enrolling = false
                }
                try Task.checkCancellation()
                guard let id = try DeviceIdentity.savedID() else {
                    show("Inquadra il QR generato dall'amministratore sul portale Dispositivi.", busy: false)
                    work = nil
                    return
                }
                try await run(id: id)
            } catch {
                enrolling = false
                guard current == generation else {
                    if active { resume() }
                    return
                }
                tunnel.stop()
                web.stopLoading()
                show(error.localizedDescription, busy: false)
            }
            if current == generation { work = nil }
        }
    }

    func suspend() {
        active = false
        generation += 1
        work?.cancel()
        work = nil
        tunnel.stop()
        guard isViewLoaded else { return }
        web.stopLoading()
        web.loadHTMLString("", baseURL: nil)
        show("Collegamento sospeso. Riapri SIB per consultare le società assegnate.", busy: false)
    }

    @objc private func restart() { suspend(); resume() }

    @objc private func openScanner() {
        suspend()
        active = true
        let scanner = QRScanner()
        scanner.onCode = { [weak self] raw in
            guard let self else { return }
            do {
                self.pendingToken = try ActivationQR.parse(raw).t
                self.resume()
            } catch { self.show(error.localizedDescription, busy: false) }
        }
        scanner.onError = { [weak self] message in self?.show(message, busy: false) }
        present(scanner, animated: true)
    }

    private func run(id: String) async throws {
        show("Verifica dispositivo \(id.prefix(8))…", busy: true)
        var profile: Data?
        while profile == nil {
            try Task.checkCancellation()
            switch try await api.credential(id) {
            case .pending:
                show("In attesa dell'approvazione dell'amministratore.\nDispositivo \(id.prefix(8))", busy: true)
                try await Task.sleep(nanoseconds: 4_000_000_000)
            case .revoked:
                throw AppFailure("Dispositivo revocato. Richiedi un nuovo QR all'amministratore.")
            case .approved(let envelope): profile = try DeviceIdentity.open(envelope)
            }
        }
        try Task.checkCancellation()
        show("Collegamento protetto al portale…", busy: true)
        try await tunnel.connect(profile: profile!)
        profile = nil
        try Task.checkCancellation()
        try await openReader(id: id)
        var ticks = 0
        while true {
            try await Task.sleep(nanoseconds: 5_000_000_000)
            guard active, tunnel.connected else { throw AppFailure("Collegamento interrotto. Premi Controlla di nuovo.") }
            try tunnel.ping()
            ticks += 1
            if ticks % 6 == 0 {
                guard try await api.approved(id) else { throw AppFailure("Autorizzazione revocata o in attesa di approvazione.") }
                try Task.checkCancellation()
            }
            if ticks % 144 == 0 { try await openReader(id: id) }
        }
    }

    private func openReader(id: String) async throws {
        var url = URLComponents(url: Settings.portal.appendingPathComponent("mobile-auth/challenge"), resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "id", value: id)]
        var request = URLRequest(url: url.url!)
        request.timeoutInterval = 10
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw AppFailure("Accesso consultatore fallito. Verifica autorizzazione e versione del portale.")
        }
        struct Challenge: Decodable { let wrapped: Data }
        let challenge = try JSONDecoder().decode(Challenge.self, from: data)
        let proof = try DeviceIdentity.decrypt(challenge.wrapped).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        var login = URLRequest(url: Settings.portal.appendingPathComponent("mobile-auth/session"))
        login.httpMethod = "POST"
        login.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        login.httpBody = Data("proof=\(proof)".utf8)
        web.load(login)
        panel.isHidden = true
        web.isHidden = false
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let url = navigationAction.request.url
        let allowed = url.map(Settings.acceptsPortal) ?? false
        decisionHandler(active && allowed ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        guard active, (error as NSError).code != NSURLErrorCancelled else { return }
        suspend()
        active = true
        show("Portale non raggiungibile attraverso la VPN. Premi Controlla di nuovo.", busy: false)
    }
}
