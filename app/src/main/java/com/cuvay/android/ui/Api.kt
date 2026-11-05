package com.cuvay.android.ui

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cliente minimo contra el servidor Go local. Sin librerias extra: org.json ya viene en Android. */
class Api(private val base: String) {
    private fun get(path: String): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 20000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun post(path: String, body: String = "{}", readMs: Int = 60000): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = readMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        // Antes un 400/500 del servidor se veia como un error vacio; ahora trae el motivo.
        if (code !in 200..299) throw java.io.IOException("HTTP $code" + if (txt.isNotBlank()) ": " + txt.trim().take(120) else "")
        return txt
    }

    /** POST generico al servidor Go (lo usan Resolver y Ajustes). */
    fun send(path: String, body: String = "{}"): String = post(path, body)
    fun getRaw(path: String): String = get(path)
    data class Channel(val num: Int, val name: String, val title: String)
    data class Media(val kind: String, val size: Long, val name: String, val relays: List<Boolean> = emptyList(),
                     val ch: Long = 0, val blk: Long = 0, val crc: String = "", val sha: String = "") {
        /** Origen de descarga: relay HTTP (rapido) > GitHub > DNS (lento). null = no descargable. */
        val source: String? get() = DlConfig.pickSource(this)
        fun url(): String {
            // TURBO: la app baja directo del relay HTTPS del servidor (nginx -> :8090),
            // sin pasar por el nucleo. El nombre es tamano_crc; la app ya verifica el CRC al final.
            if (source == "http" && size > 0 && crc.length == 8) return "$PANEL/f/${size}_$crc"
            // NUBE: la app baja directo de GitHub y descifra ella misma (sin el nucleo).
            if (source == "fast" && DlConfig.effectiveRoute(this) != DlRoute.EDU) {
                return GhRelay.url(size, crc) ?: ("gh://x?err=" + java.net.URLEncoder.encode(GhRelay.why, "UTF-8"))
            }
            return coreUrl()
        }
        private fun coreUrl(): String = "/api/media/get?ch=$ch&blk=$blk&size=$size&crc=$crc" +
            (if (name.isNotEmpty()) "&name=" + java.net.URLEncoder.encode(name, "UTF-8") else "") + "&source=$source" + DlConfig.proxyParam(this, source)
    }
    data class Msg(val id: Long, val time: String, val media: List<Media>, val caption: String)

    fun channels(): List<Channel> {
        val raw = get("/api/channels").trim()
        if (raw.isEmpty() || raw == "null") return emptyList()
        val a = JSONArray(raw)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val n = o.optString("Name", o.optString("name", "Canal ${it + 1}"))
            val t = listOf("Title", "title", "DisplayName", "displayName").map { k -> o.optString(k, "") }.firstOrNull { s -> s.isNotBlank() } ?: n
            Channel(it + 1, n, t)
        }
    }
    private val tagRe = Regex("^\\[(FILE|AUDIO|VIDEO|PHOTO|IMAGE)\\](.*)$")
    private val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())

    /** Separa el Text del servidor en archivos ([AUDIO]...) y texto/descripcion. */
    private fun parse(text: String): Pair<List<Media>, String> {
        val media = mutableListOf<Media>(); val cap = mutableListOf<String>()
        for (raw in text.split("\n")) {
            val line = raw.trim()
            val m = tagRe.find(line)
            if (m == null) { if (line.isNotEmpty() || cap.isNotEmpty()) cap.add(raw); continue }
            val p = m.groupValues[2].split(":")
            val hasSha = p.size >= 7 && Regex("^[0-9a-fA-F]{64}$").matches(p.last().trim())
            val name = when { hasSha -> p.subList(5, p.size - 1).joinToString(":"); p.size >= 6 -> p.drop(5).joinToString(":"); else -> "" }
            media.add(Media(m.groupValues[1], p.getOrNull(0)?.toLongOrNull() ?: 0L, name,
                (p.getOrNull(1) ?: "").split(",").map { it.trim() == "1" },
                p.getOrNull(2)?.toLongOrNull() ?: 0L, p.getOrNull(3)?.toLongOrNull() ?: 0L,
                (p.getOrNull(4) ?: "").lowercase(), if (hasSha) p.last().trim().lowercase() else ""))
        }
        return media to cap.joinToString("\n").trim()
    }

    fun messages(num: Int): List<Msg> {
        val o = JSONObject(get("/api/messages/$num"))
        val a = o.optJSONArray("messages") ?: o.optJSONArray("Messages") ?: JSONArray()
        return (0 until a.length()).map {
            val m = a.getJSONObject(it)
            val (media, cap) = parse(m.optString("Text", ""))
            Msg(m.optLong("ID"), fmt.format(java.util.Date(m.optLong("Timestamp") * 1000)), media, cap)
        }
    }
    fun mirrorChannels(): List<String> {
    val o = JSONObject(get("/api/telemirror/channels"))
    val a = o.optJSONArray("channels") ?: JSONArray()
    return (0 until a.length()).mapNotNull {
        when (val x = a.opt(it)) {
            is JSONObject -> x.optString("username", x.optString("name")).ifBlank { null }
            null -> null
            else -> x.toString().ifBlank { null }
        }
    }
}
    fun resolvers(): String = get("/api/resolvers/active")
    fun resolverStatus() = JSONObject(get("/api/resolvers/active"))
    fun rescan() = post("/api/rescan")
    fun refresh() = post("/api/refresh")
    /** Pide al cliente local que consulte ese canal por DNS ahora mismo (corre en segundo plano). */
    fun refreshChannel(num: Int): String = post("/api/refresh?channel=$num")
    fun status(): String = get("/api/status")

    // ---- Chat, Ajustes ----
    fun chatInfo() = JSONObject(get("/api/chat/info"))
    fun chatThreads() = JSONArray(get("/api/chat/threads"))
    fun chatMessages(peer: String) = JSONObject(get("/api/chat/messages?markRead=1&peer=" + java.net.URLEncoder.encode(peer, "UTF-8")))

    fun chatSend(peer: String, text: String): String =
    post("/api/chat/send", JSONObject().put("peer", peer).put("text", text).toString(), 16 * 60 * 1000)

    fun chatEnable(): String = post("/api/chat/enable", JSONObject().put("action", "create").toString())
    fun profiles() = JSONObject(get("/api/profiles"))
    fun switchProfile(id: String): String = post("/api/profiles/switch", JSONObject().put("id", id).toString())
    fun settings() = JSONObject(get("/api/settings"))
    fun saveSettings(json: String): String = post("/api/settings", json)
    /** Reaplica el perfil activo SIN reescanear, para que modo/limite/timeout nuevos entren en vigor. */
    fun applyActiveProfile(): String {
        val p = profiles()
        val id = p.optString("Active", p.optString("active"))
        if (id.isEmpty()) return ""
        return post("/api/profiles/switch", JSONObject().put("id", id).put("skipCheck", true).toString())
    }
    fun cacheStats() = JSONObject(get("/api/cache/stats"))
    fun clearCache(): String = post("/api/cache/clear")
}
