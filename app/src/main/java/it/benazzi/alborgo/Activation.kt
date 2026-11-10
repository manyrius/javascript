package it.benazzi.alborgo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Chiave del dispositivo nel chip sicuro (Android Keystore). La parte privata non esce mai dal chip.
 * Serve a riaprire la busta che arriva da artsdomus (la jolly cifrata su misura per questo telefono).
 */
object DeviceKey {
    private const val ALIAS = "alborgo_device_key"

    fun ensurePublic(): RSAPublicKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .apply {
                        if (android.os.Build.VERSION.SDK_INT >= 35) {
                            setMgf1Digests(KeyProperties.DIGEST_SHA256)
                        }
                    }
                    .build()
            )
            kpg.generateKeyPair()
        }
        return ks.getCertificate(ALIAS).publicKey as RSAPublicKey
    }

    fun unwrap(wrapped: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val priv = ks.getKey(ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        val spec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        cipher.init(Cipher.DECRYPT_MODE, priv, spec)
        return cipher.doFinal(wrapped)
    }

    fun delete() {
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
    }
}

/** Apre la busta ricevuta da artsdomus e restituisce la jolly.ovpn in chiaro (solo in memoria). */
object Envelope {
    fun open(json: JSONObject): ByteArray {
        val wrapped = b64(json.getString("wrapped"))
        val iv = b64(json.getString("iv"))
        val ct = b64(json.getString("ct"))
        val tag = b64(json.getString("tag"))
        val keymat = DeviceKey.unwrap(wrapped)          // 64 byte: 32 AES + 32 HMAC
        val aesKey = keymat.copyOfRange(0, 32)
        val macKey = keymat.copyOfRange(32, 64)
        // verifica HMAC su iv||ct
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        mac.update(iv); mac.update(ct)
        val calc = mac.doFinal()
        if (!calc.contentEquals(tag)) throw SecurityException("Firma della credenziale non valida")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }
    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
}

/** Chiamate all'API di attivazione su artsdomus. */
class Api(private val base: String) {
    fun enroll(token: String, model: String, brand: String, phoneName: String): JSONObject {
        val pub = DeviceKey.ensurePublic()
        val mod = Base64.encodeToString(unsigned(pub.modulus), Base64.NO_WRAP)
        val exp = Base64.encodeToString(unsigned(pub.publicExponent), Base64.NO_WRAP)
        // dati nell'URL (niente corpo: sopravvive a eventuali redirect del hosting)
        val url = "$base?action=enroll&token=${enc(token)}&modulus=${enc(mod)}&exponent=${enc(exp)}&model=${enc(model)}"
        val connection = open(url, "POST")
        val (code, text) = try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.use { it.write(("brand=" + enc(brand) + "&phoneName=" + enc(phoneName)).toByteArray()) }
            Pair(connection.responseCode, readAll(connection))
        } finally { connection.disconnect() }
        val j = JSONObject(if (text.isBlank()) "{}" else text)
        if (code >= 400) throw RuntimeException(if (j.has("error")) j.getString("error") else "errore server ($code)")
        return j
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Ritorna coppia (codice http, json). 200 = busta pronta, 202 = in attesa, 403 = revocato. */
    fun credential(deviceId: String): Pair<Int, JSONObject?> {
        val (code, text) = get("$base?action=credential&id=$deviceId")
        val j = if (text.isNotBlank()) runCatching { JSONObject(text) }.getOrNull() else null
        return Pair(code, j)
    }

    fun heartbeat(deviceId: String): JSONObject? {
        val (_, text) = get("$base?action=heartbeat&id=$deviceId")
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val c = open(url, "POST")
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = readAll(c)
        val j = JSONObject(if (text.isBlank()) "{}" else text)
        if (c.responseCode >= 400 && j.has("error")) throw RuntimeException(j.getString("error"))
        return j
    }

    private fun get(url: String): Pair<Int, String> {
        val c = open(url, "GET")
        return try { Pair(c.responseCode, readAll(c)) } finally { c.disconnect() }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.useCaches = false
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 12000
        return c
    }

    private fun readAll(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: return "")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }
}
