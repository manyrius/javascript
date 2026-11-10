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
 * (Lo Stadio 2 aggiungerÃ  il tunnel VPN; qui la credenziale viene scaricata e verificata.)
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
                val u = request.url.toString()
                return if (u.startsWith("http")) { view.loadUrl(u, headers()); true } else false
            }
            override fun onPageFinished(view: WebView, url: String) { swipe.isRefreshing = false; CookieManager.getInstance().flush() }
        }
        swipe.setOnRefreshListener { web.loadUrl(portalUrl, headers()) }

        actBtn.setOnClickListener { ensureCameraThenScan() }

        val savedUrl = apiUrl
        val savedId = deviceId
        if (isActivated) showPortal()
        else if (savedUrl != null && savedId != null) startCheck(savedUrl, savedId)
        else showActivation()
    }

    override fun onResume() {
        super.onResume()
        if (isActivated) checkRevokeInBackground()
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
        status.text = "Registrazione del dispositivoâ€¦"
        thread { enrollAndWait(u, t) }
    }

    private fun enrollAndWait(u: String, t: String) {
        val api = Api(u)
        val id: String
        try {
            val r = api.enroll(t, deviceLabel)
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
        poll = thread {
            val api = Api(u)
            try {
                repeat(45) {
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
    private fun checkRevokeInBackground() {
        val u = apiUrl ?: return; val id = deviceId ?: return
        thread {
            try {
                val hb = Api(u).heartbeat(id) ?: return@thread
                if (hb.optString("status") == "Revoked" || hb.optString("status") == "unknown") ui { wipeAndShowRevoked() }
            } catch (_: Exception) {}
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
        activation.visibility = View.VISIBLE
        actBtn.visibility = View.GONE
        prog.visibility = View.VISIBLE
        status.text = "Verifica autorizzazione VPN…"
        vpnCheck?.interrupt()
        vpnCheck = thread {
            try {
                val state = Api(requireNotNull(apiUrl)).heartbeat(requireNotNull(deviceId))?.optString("status")
                ui {
                    if (!isActivated) return@ui
                    when (state) {
                        "Approved" -> bindTunnel()
                        "Revoked", "unknown" -> wipeAndShowRevoked()
                        else -> vpnError("Autorizzazione VPN non confermata da Aruba.")
                    }
                }
            } catch (e: Exception) { ui { if (isActivated) vpnError("Aruba non raggiungibile.") } }
        }
    }

    private var vpnCheck: Thread? = null
    private var tunnel: de.blinkt.openvpn.api.IOpenVPNAPIService? = null
    private var bound = false
    private var started = false
    private var permissionPending = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionPending = false
        if (it.resultCode == RESULT_OK) prepareTunnel() else vpnError("Autorizzazione VPN annullata.")
    }

    private fun bindTunnel() {
        try {
            val pkg = "de.blinkt.openvpn"
            @Suppress("DEPRECATION")
            val sig = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.singleOrNull()
            val digest = sig?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            val fingerprint = digest?.joinToString("") { "%02x".format(it) }
            require(fingerprint == "b26483ea68fb0ab6cd51420c7e5e84bb28b980d87036222f6927b361c7cba902") {
                "Installa OpenVPN for Android dalla release ufficiale di Arne Schwabe."
            }
            val intent = android.content.Intent("de.blinkt.openvpn.api.IOpenVPNAPIService").setPackage(pkg)
            bound = bindService(intent, connection, BIND_AUTO_CREATE)
            if (!bound) vpnError("Servizio OpenVPN non disponibile.")
        } catch (e: PackageManager.NameNotFoundException) {
            vpnError("Installa OpenVPN for Android dalla release ufficiale di Arne Schwabe.")
        } catch (e: Exception) { vpnError(e.message ?: "Collegamento a OpenVPN fallito.") }
    }

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            tunnel = de.blinkt.openvpn.api.IOpenVPNAPIService.Stub.asInterface(binder)
            prepareTunnel()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {
            tunnel = null
            if (isActivated) vpnError("Servizio VPN scollegato.")
        }
    }

    private fun prepareTunnel() {
        val service = tunnel ?: return
        if (!isActivated || permissionPending || started) return
        try {
            val request = service.prepare(packageName) ?: service.prepareVPNService()
            if (request != null) {
                permissionPending = true
                permission.launch(request)
                return
            }
            val profile = Envelope.open(JSONObject(requireNotNull(prefs.getString("cred", null))))
            try { service.startVPN(profile.toString(Charsets.UTF_8)) } finally { profile.fill(0) }
            started = true
            status.text = "Connessione VPN al Borgo…"
            verifyPortal()
        } catch (e: Exception) { vpnError("Avvio VPN fallito: " + e.javaClass.simpleName) }
    }

    private fun verifyPortal() {
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
                        ui { if (started && isActivated) { activation.visibility = View.GONE; web.loadUrl(portalUrl, headers()) } }
                        return@thread
                    }
                }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return@thread }
            }
            ui { if (started) vpnError("Il portale non risponde attraverso la VPN. Controlla il registro di OpenVPN for Android.") }
        }
    }

    private fun closeTunnel() {
        if (started) runCatching { tunnel?.disconnect() }
        started = false
        if (bound) { unbindService(connection); bound = false }
        tunnel = null
    }

    private fun vpnError(message: String) {
        closeTunnel()
        activation.visibility = View.VISIBLE
        prog.visibility = View.GONE
        status.text = message
        actBtn.visibility = View.VISIBLE
        actBtn.text = "Riprova VPN"
        actBtn.setOnClickListener { showPortal() }
    }

    private fun headers(): Map<String, String> = mapOf("X-Borghetto-Device" to (deviceLabel + " Â· " + (deviceId?.take(8) ?: "")))

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (activation.visibility != View.VISIBLE && web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    companion object {
        // Portale per NUMERO (in VPN i nomi non si risolvono).
        const val DEFAULT_PORTAL = "http://192.168.1.34/ilborghetto/"
    }
}
