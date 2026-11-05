package com.cuvay.android.ui

import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NUBE (GitHub) sin pasar por el nucleo: la app arma la direccion del archivo en el repo del
 * relay, lo baja de api.github.com y lo descifra ella misma (AES-256-GCM, igual que el servidor).
 * Las rutas del repo salen de HMAC con tu clave, igual que en el servidor (protocol/relay.go).
 */
internal object GhRelay {
    /** Repo publico donde el servidor sube los archivos cifrados. */
    const val REPO = "TitoYTMex23/cuvay-relay"

    /**
     * Token de GitHub SIN NINGUN PERMISO marcado (el repo es publico, leer no necesita permisos).
     * Solo sirve para subir el limite de consultas de 60 a 5000 por hora. Vacio = sin token.
     * NUNCA pongas aqui el token del servidor (el que puede escribir): cualquiera lo sacaria del APK.
     */
    const val TOKEN = ""

    @Volatile private var domain = ""
    @Volatile private var passphrase = ""
    @Volatile private var loadedAt = 0L
    /** Por que url() devolvio null (se muestra en el error de la descarga). */
    @Volatile var why = ""

    fun ready() = domain.isNotEmpty() && passphrase.isNotEmpty()

    /** Lee dominio y clave del perfil activo del nucleo local. Llamar desde un hilo de fondo. */
    fun refresh(api: Api) {
        if (ready() && System.currentTimeMillis() - loadedAt < 300_000) return
        runCatching {
            val p = api.profiles()
            val id = p.optString("Active", p.optString("active"))
            val arr = p.optJSONArray("Profiles") ?: p.optJSONArray("profiles") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("ID", o.optString("id")) != id) continue
                val c = o.optJSONObject("Config") ?: o.optJSONObject("config") ?: continue
                val d = c.optString("Domain", c.optString("domain"))
                val k = c.optString("Key", c.optString("key"))
                if (d.isNotEmpty() && k.isNotEmpty()) {
                    domain = d; passphrase = k; loadedAt = System.currentTimeMillis()
                }
            }
        }
    }

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun hmacHex(pass: String, ctx: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sha256(pass), "HmacSHA256"))
        mac.update(ctx.toByteArray(Charsets.UTF_8))
        mac.update(byteArrayOf(0))
        mac.update(msg.toByteArray(Charsets.UTF_8))
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    /** HKDF-SHA256(secreto = sha256(clave), sin salt, info = "thefeed-relay") -> 32 bytes. */
    private fun relayKey(pass: String): ByteArray {
        val ext = Mac.getInstance("HmacSHA256")
        ext.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = ext.doFinal(sha256(pass))
        val exp = Mac.getInstance("HmacSHA256")
        exp.init(SecretKeySpec(prk, "HmacSHA256"))
        exp.update("thefeed-relay".toByteArray(Charsets.UTF_8))
        exp.update(byteArrayOf(1))
        return exp.doFinal()
    }

    /** Direccion interna "gh://" que entiende AndroidBridge, o null si aun no hay dominio/clave. */
    fun url(size: Long, crcHex: String): String? {
        if (!ready()) {
            // Aun sin dominio/clave: lo pide ahora mismo (hilo aparte, espera maximo 3 s).
            val a = DlConfig.api
            if (a != null) {
                val t = Thread { refresh(a) }
                t.start(); runCatching { t.join(3000) }
            }
        }
        if (!ready()) { why = "NUBE: la app no pudo leer dominio/clave del perfil"; return null }
        if (size <= 0 || crcHex.length != 8) { why = "NUBE: archivo sin tamano/CRC (size=$size crc=$crcHex)"; return null }
        val dom = hmacHex(passphrase, "thefeed-relay-domain", domain).substring(0, 16)
        val obj = hmacHex(passphrase, "thefeed-relay-object", "${size}_$crcHex").substring(0, 24)
        val https = "https://api.github.com/repos/$REPO/contents/$dom/$obj"
        val key = relayKey(passphrase).joinToString("") { "%02x".format(it) }
        return "gh://x?u=" + URLEncoder.encode(https, "UTF-8") + "&k=" + key +
            (if (TOKEN.isNotEmpty()) "&t=" + TOKEN else "")
    }
}
