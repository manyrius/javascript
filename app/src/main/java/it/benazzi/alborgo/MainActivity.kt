package it.benazzi.alborgo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Al Borgo - app di consultazione.
 * Stadio 1: attivazione del dispositivo (QR -> approvazione -> jolly cifrata decifrata nel chip) e portale.
 * (Lo Stadio 2 aggiungerà il tunnel VPN; qui la credenziale viene scaricata e verificata.)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var activation: View
    private lateinit var status: TextView
    private lateinit var actBtn: Button
    private lateinit var prog: ProgressBar

    private val prefs by lazy { getSharedPreferences("alborgo", MODE_PRIVATE) }

    // indirizzo del portale (per NUMERO, non per nome: in VPN i nomi non si risolvono)
    private val portalUrl get() = prefs.getString("portalUrl", DEFAULT_PORTAL) ?: DEFAULT_PORTAL
    private val apiUrl get() = prefs.getString("apiUrl", null)
    private val deviceId get() = prefs.getString("deviceId", null)
    private val isActivated get() = prefs.getString("cred", null) != null

    private val deviceLabel get() = (Build.MODEL ?: "Dispositivo")

    private val scan = registerForActivityResult(ScanContract()) { res ->
        if (res.contents != null) onQr(res.contents)
    }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScan() else toast(getString(R.string.no_camera))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        swipe = findViewById(R.id.swipe)
        activation = findViewById(R.id.activation)
        status = findViewById(R.id.status)
        actBtn = findViewById(R.id.actBtn)
        prog = findViewById(R.id.prog)

        CookieManager.getInstance().setAcceptCookie(true)
        with(web.settings) {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            useWideViewPort = true; loadWithOverviewMode = true; setSupportZoom(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !allowed(request.url)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (allowed(request.url)) return null
                return android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
            }
            override fun onPageFinished(view: WebView, url: String) { swipe.isRefreshing = false; CookieManager.getInstance().flush() }
        }
        swipe.setOnRefreshListener { web.loadUrl(portalUrl, headers()) }

        actBtn.setOnClickListener { ensureCameraThenScan() }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private var foreground = false
    private var epoch = 0
    override fun onResume() {
        super.onResume()
        foreground = !isInMultiWindowMode && !isInPictureInPictureMode
        if (!foreground) { status.text = "Apri Al Borgo a schermo intero."; return }
        if (permissionPending) return
        val u = apiUrl
        val id = deviceId
        if (isActivated) showPortal()
        else if (u != null && id != null) startCheck(u, id)
        else showActivation()
    }

    override fun onPause() {
        foreground = false
        epoch++
        poll?.interrupt()
        vpnCheck?.interrupt()
        closeTunnel()
        web.stopLoading()
        web.loadUrl("about:blank")
        activation.visibility = View.VISIBLE
        super.onPause()
    }

    // --------------------------------------------------------------- attivazione
    private fun showActivation() {
        activation.visibility = View.VISIBLE
        prog.visibility = View.GONE
        actBtn.visibility = View.VISIBLE
        actBtn.text = getString(R.string.activate_button)
        status.text = getString(R.string.activate_hint)
    }

    private fun ensureCameraThenScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchScan()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun launchScan() {
        scan.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false)
            .setPrompt("Inquadra il QR di attivazione").setDesiredBarcodeFormats(ScanOptions.QR_CODE))
    }

    private fun onQr(contents: String) {
        val u: String; val t: String
        try { val j = JSONObject(contents); u = j.getString("u"); t = j.getString("t") }
        catch (e: Exception) { toast("QR non valido"); return }
        actBtn.visibility = View.GONE
        prog.visibility = View.VISIBLE
        status.text = "Registrazione del dispositivo…"
        thread { enrollAndWait(u, t) }
    }

    private fun enrollAndWait(u: String, t: String) {
        val api = Api(u)
        val id: String
        try {
            val name = runCatching {
                android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            }.getOrNull().orEmpty().trim().take(100)
            val r = api.enroll(t, deviceLabel, Build.MANUFACTURER.orEmpty(), name)
            id = r.getString("deviceId")
            prefs.edit().putString("apiUrl", u).putString("deviceId", id).apply()
        } catch (e: Exception) {
            ui { status.text = "Errore: ${e.message}"; prog.visibility = View.GONE; actBtn.visibility = View.VISIBLE; actBtn.text = "Riprova" }
            return
        }
        ui { startCheck(u, id) }
    }

    private var poll: Thread? = null

    override fun onDestroy() {
        vpnCheck?.interrupt()
        closeTunnel()
        poll?.interrupt()
        super.onDestroy()
    }

    private fun startCheck(u: String, id: String) {
        status.text = "Verifica dispositivo " + id.take(8)
        prog.visibility = View.VISIBLE
        actBtn.visibility = View.GONE
        if (!foreground) return
        poll?.interrupt()
        poll = thread {
            val api = Api(u)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    if (Thread.currentThread().isInterrupted) return@thread
                    val (code, reply) = api.credential(id)
                    when (code) {
                        202 -> {
                            require(reply?.optString("status") == "pending") { "Risposta di attesa non valida" }
                            ui { status.text = getString(R.string.waiting) + " (" + id.take(8) + ")" }
                            Thread.sleep(4000)
                        }
                        403 -> {
                            require(reply?.optString("status") == "revoked") { "Accesso negato da Aruba" }
                            ui { wipeAndShowRevoked() }
                            return@thread
                        }
                        200 -> {
                            require(reply != null && reply.has("wrapped")) { "Risposta senza credenziale" }
                            try { Envelope.open(reply) } catch (e: Exception) {
                                throw IllegalStateException("Approvato. Apertura credenziale fallita (" + e.javaClass.simpleName + ")")
                            }
                            if (Thread.currentThread().isInterrupted) return@thread
                            prefs.edit().putString("cred", reply.toString()).apply()
                            ui { showPortal() }
                            return@thread
                        }
                        else -> error("Aruba HTTP " + code + ": " + (reply?.optString("error")?.ifBlank { null } ?: reply?.optString("status").orEmpty()))
                    }
                }
                retry(u, id, getString(R.string.waiting))
            } catch (_: InterruptedException) {
                return@thread
            } catch (e: Exception) {
                retry(u, id, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun retry(u: String, id: String, message: String) = ui {
        status.text = message + " (" + id.take(8) + ")"
        prog.visibility = View.GONE
        actBtn.visibility = View.VISIBLE
        actBtn.text = "Controlla di nuovo"
        actBtn.setOnClickListener { startCheck(u, id) }
    }

    // --------------------------------------------------------------- revoca / portale
    private var sessionChecks: Thread? = null
    private fun startSessionChecks(current: Int) {
        sessionChecks?.interrupt()
        sessionChecks = thread {
            try {
                repeat(26) {
                    Thread.sleep(30000)
                    val state = Api(requireNotNull(apiUrl)).heartbeat(requireNotNull(deviceId))?.optString("status")
                    if (state != "Approved") {
                        ui { if (current == epoch && started) {
                            if (state == "Revoked" || state == "unknown") wipeAndShowRevoked()
                            else vpnError("Autorizzazione del dispositivo non confermata.")
                        } }
                        return@thread
                    }
                }
                ui { if (current == epoch && started) verifyPortal() }
            } catch (_: InterruptedException) {
                return@thread
            } catch (_: Exception) {
                ui { if (current == epoch && started) vpnError("Verifica del dispositivo non disponibile.") }
            }
        }
    }

    private fun wipeAndShowRevoked() {
        vpnCheck?.interrupt()
        closeTunnel()
        prefs.edit().remove("cred").remove("deviceId").remove("apiUrl").apply()
        DeviceKey.delete()
        web.loadUrl("about:blank")
        activation.visibility = View.VISIBLE
        prog.visibility = View.GONE
        actBtn.visibility = View.VISIBLE
        actBtn.text = getString(R.string.activate_button)
        actBtn.setOnClickListener { ensureCameraThenScan() }
        status.text = getString(R.string.revoked)
    }

    private fun showPortal() {
        if (!foreground || started) return
        val current = epoch
        activation.visibility = View.VISIBLE
        actBtn.visibility = View.GONE
        prog.visibility = View.VISIBLE
        status.text = "Verifica autorizzazione�"
        vpnCheck?.interrupt()
        vpnCheck = thread {
            try {
                val state = Api(requireNotNull(apiUrl)).heartbeat(requireNotNull(deviceId))?.optString("status")
                ui {
                    if (!isActivated || !foreground || current != epoch) return@ui
                    when (state) {
                        "Approved" -> prepareTunnel()
                        "Revoked", "unknown" -> wipeAndShowRevoked()
                        else -> vpnError("Autorizzazione non confermata da Aruba.")
                    }
                }
            } catch (e: Exception) { ui { if (isActivated) vpnError("Aruba non raggiungibile.") } }
        }
    }

    private var vpnCheck: Thread? = null
    private var started = false
    private var permissionPending = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionPending = false
        if (it.resultCode != RESULT_OK) { consentDenied = true; vpnError("Autorizzazione Android necessaria per collegarsi.") }
        else if (foreground) showPortal()
    }
    private var consentDenied = false

    private fun prepareTunnel() {
        if (!foreground || permissionPending || started || !isActivated) return
        try {
            val request = android.net.VpnService.prepare(this)
            if (request != null) {
                if (consentDenied) { vpnError("Autorizza il collegamento per consultare Al Borgo."); return }
                permissionPending = true
                permission.launch(request)
                return
            }
            val plain = Envelope.open(JSONObject(requireNotNull(prefs.getString("cred", null))))
            val profile = try {
                de.blinkt.openvpn.core.ConfigParser().run {
                    parseConfig(plain.inputStream().bufferedReader())
                    convertProfile()
                }
            } finally { plain.fill(0) }
            profile.mName = "Al Borgo"
            profile.mAllowedAppsVpn.clear()
            profile.mAllowedAppsVpn.add(packageName)
            profile.mAllowedAppsVpnAreDisallowed = false
            profile.mRoutenopull = true
            profile.mUseDefaultRoute = false
            profile.mUseDefaultRoutev6 = false
            profile.mCustomRoutes = "192.168.1.34/32"
            profile.mCustomRoutesv6 = ""
            profile.mAllowLocalLAN = false
            de.blinkt.openvpn.core.ProfileManager.setTemporaryProfile(this, profile)
            de.blinkt.openvpn.core.VPNLaunchHelper.startOpenVpn(profile, this, "Al Borgo", false)
            started = true
            status.text = "Collegamento al portale�"
            verifyPortal()
        } catch (e: Exception) { vpnError("Collegamento non riuscito: " + e.javaClass.simpleName) }
    }

    private fun verifyPortal() {
        val current = epoch
        vpnCheck = thread {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            repeat(30) {
                if (Thread.currentThread().isInterrupted) return@thread
                val network = cm.activeNetwork
                if (network != null && cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) {
                    val reachable = runCatching {
                        val c = network.openConnection(java.net.URL(portalUrl)) as java.net.HttpURLConnection
                        try {
                            c.instanceFollowRedirects = false
                            c.connectTimeout = 1500
                            c.readTimeout = 1500
                            c.responseCode in 200..399
                        } finally { c.disconnect() }
                    }.getOrDefault(false)
                    if (reachable) {
                        try {
                            val id = requireNotNull(deviceId)
                            val conn = network.openConnection(java.net.URL(portalUrl + "mobile-auth/challenge?id=" + id)) as java.net.HttpURLConnection
                            conn.connectTimeout = 5000
                            conn.readTimeout = 8000
                            val challenge = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
                            val proof = DeviceKey.unwrap(android.util.Base64.decode(challenge.getString("wrapped"), android.util.Base64.DEFAULT))
                            val token = android.util.Base64.encodeToString(proof, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                            proof.fill(0)
                            ui { if (foreground && started && isActivated && epoch == current) {
                                activation.visibility = View.GONE
                                startSessionChecks(current)
                                web.postUrl(portalUrl + "mobile-auth/session", ("proof=" + token).toByteArray())
                            } }
                        } catch (e: Exception) { ui { if (foreground && started) vpnError("Accesso consultatore non riuscito. Verifica la versione del portale.") } }
                        return@thread
                    }
                }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return@thread }
            }
            ui { if (started) vpnError("Il portale non risponde attraverso la VPN. Verifica il collegamento del server.") }
        }
    }

    private fun closeTunnel() {
        sessionChecks?.interrupt()
        if (!started) return
        started = false
        val app = applicationContext
        val service = android.content.Intent(app, de.blinkt.openvpn.core.OpenVPNService::class.java)
        service.action = de.blinkt.openvpn.core.OpenVPNService.START_SERVICE
        val stopper = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                try { de.blinkt.openvpn.core.IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false) }
                finally { app.unbindService(this) }
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        if (!app.bindService(service, stopper, 0)) app.stopService(service)
    }

    private fun vpnError(message: String) {
        web.stopLoading()
        web.loadUrl("about:blank")
        closeTunnel()
        activation.visibility = View.VISIBLE
        prog.visibility = View.GONE
        status.text = message
        actBtn.visibility = View.VISIBLE
        actBtn.text = "Riprova"
        actBtn.setOnClickListener { consentDenied = false; showPortal() }
    }

    private fun allowed(uri: android.net.Uri): Boolean =
        uri.scheme == "http" && uri.host == "192.168.1.34" && uri.port in listOf(-1, 80) &&
        uri.path?.startsWith("/ilborghetto/") == true && uri.pathSegments.none { it == ".." }

    private fun headers(): Map<String, String> = mapOf("X-Borghetto-Device" to (deviceLabel + " · " + (deviceId?.take(8) ?: "")))

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (activation.visibility != View.VISIBLE && web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        // Portale per NUMERO (in VPN i nomi non si risolvono).
        const val DEFAULT_PORTAL = "http://192.168.1.34/ilborghetto/"
    }
}
