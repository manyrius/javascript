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
        status.text = "Registrazione del dispositivo…"
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
        activation.visibility = View.GONE
        web.loadUrl(portalUrl, headers())
    }

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
