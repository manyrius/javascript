package it.benazzi.alborgo

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.UUID

/**
 * App "Al Borgo": mostra il portale di consultazione del bar dentro una WebView.
 * - ricorda l'accesso (i cookie restano salvati tra un'apertura e l'altra);
 * - invia l'intestazione X-Borghetto-Device, cosi la pagina "Utenti e azioni" sa da quale tablet arriva;
 * - l'indirizzo del server si imposta al primo avvio e si puo cambiare dal menu.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout

    private val prefs by lazy { getSharedPreferences("alborgo", MODE_PRIVATE) }

    private val deviceId: String
        get() {
            var id = prefs.getString("deviceId", null)
            if (id == null) {
                id = UUID.randomUUID().toString().substring(0, 8)
                prefs.edit().putString("deviceId", id).apply()
            }
            return id
        }

    /** Etichetta leggibile mostrata nel registro azioni: modello + codice breve del dispositivo. */
    private val deviceLabel: String
        get() = (android.os.Build.MODEL ?: "Tablet") + " · " + deviceId

    private val extraHeaders: Map<String, String>
        get() = mapOf("X-Borghetto-Device" to deviceLabel)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        swipe = findViewById(R.id.swipe)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }

        web.webViewClient = object : WebViewClient() {
            // Tutta la navigazione interna passa con l'intestazione del dispositivo
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith("http")) { view.loadUrl(url, extraHeaders); true } else false
            }
            override fun onPageFinished(view: WebView, url: String) {
                swipe.isRefreshing = false
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) swipe.isRefreshing = false
            }
        }

        findViewById<Button>(R.id.settings).setOnClickListener { askServerUrl(firstTime = false) }

        swipe.setOnRefreshListener { web.loadUrl(currentUrl(), extraHeaders) }

        val saved = prefs.getString("serverUrl", null)
        if (saved.isNullOrBlank()) askServerUrl(firstTime = true) else web.loadUrl(saved, extraHeaders)
    }

    private fun currentUrl(): String =
        prefs.getString("serverUrl", DEFAULT_URL) ?: DEFAULT_URL

    private fun askServerUrl(firstTime: Boolean) {
        val input = EditText(this).apply {
            hint = getString(R.string.server_hint)
            setText(prefs.getString("serverUrl", DEFAULT_URL))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_change_server))
            .setView(input)
            .setCancelable(!firstTime)
            .setPositiveButton("OK") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http")) url = "http://$url"
                    prefs.edit().putString("serverUrl", url).apply()
                    web.loadUrl(url, extraHeaders)
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.menu_change_server))
        menu.add(0, 2, 1, getString(R.string.retry))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> askServerUrl(firstTime = false)
            2 -> web.loadUrl(currentUrl(), extraHeaders)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    companion object {
        // Indirizzo predefinito: cambialo qui, oppure dal menu dell'app al primo avvio.
        const val DEFAULT_URL = "http://192.168.1.34/ilborghetto/"
    }
}
