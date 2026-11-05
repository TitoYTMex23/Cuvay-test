package com.cuvay.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.lazy.grid.items
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =====================================================================
//  Cliente del servidor de chat (cubachat.py). Sin librerias extra.
// =====================================================================
class ChatError(val code: String, msg: String) : Exception(msg)

private fun Throwable?.isAuth() = this is ChatError && (code == "unauthorized" || code == "banned")

private fun errText(code: String) = when (code) {
    "name_taken" -> "Ese ID ya está en uso, prueba con otro"
    "name_invalid" -> "Usa de 3 a 16 letras, números o _"
    "name_reserved" -> "Ese ID no está disponible"
    "rate_limited" -> "Vas muy rápido, espera un momento"
    "unauthorized" -> "Tu sesión ya no es válida"
    "banned" -> "Tu cuenta fue suspendida por un admin"
    "no_such_user" -> "Ese usuario no existe"
    "bad_text" -> "Mensaje vacío o demasiado largo"
    "bad_peer" -> "No puedes escribirte a ti mismo"
    "bad_login" -> "ID o clave incorrectos"
    "password_invalid" -> "La clave debe tener de 6 a 64 caracteres"
    "password_required" -> "Este servidor exige una clave"
    "not_yours" -> "Solo puedes cambiar tus propios mensajes"
    "not_admin" -> "Solo los admins pueden hacer eso"
    "bad_target" -> "No puedes hacer eso con un admin ni contigo mismo"
    "too_many_pins" -> "Ya hay 10 mensajes anclados, quita uno primero"
    "not_editable" -> "Ese mensaje no se puede editar"
    "not_pinnable" -> "Ese mensaje no se puede anclar"
    "no_such_message" -> "El mensaje ya no existe"
    "bad_sticker" -> "Sticker no válido"
    "bad_reply" -> "El mensaje al que respondes ya no existe"
    "bad_emoji" -> "Reacción no válida"
    "not_reactable" -> "No puedes reaccionar a un mensaje eliminado"
    else -> "Error del servidor ($code)"
}

data class CReply(val id: Long, val from: String, val text: String, val kind: String, val deleted: Boolean)

data class CMsg(
    val id: Long, val from: String, val text: String, val ts: Long,
    val edited: Boolean = false, val deleted: Boolean = false,
    val pinned: Boolean = false, val kind: String = "text",
    val delBy: String = "", val reply: CReply? = null,
    val reactions: Map<String, List<String>> = emptyMap()
)
data class CUser(val name: String, val online: Boolean)
data class AUser(val name: String, val online: Boolean, val banned: Boolean, val admin: Boolean, val msgs: Int)
data class CThread(val peer: String, val text: String, val ts: Long, val mine: Boolean, val unread: Int,
                   val deleted: Boolean, val kind: String = "text")
data class CPack(val id: String, val name: String, val type: String, val icon: String, val items: List<String>)
class Poll(val messages: List<CMsg>, val updated: List<CMsg>, val rev: Long)

private val DEFAULT_STICKERS = listOf("😀", "😂", "🤣", "😍", "😎", "🥳", "😢", "😭", "😡", "🤔", "🙄", "😴",
    "👍", "👎", "👏", "🙏", "💪", "🤝", "👋", "🔥", "❤️", "💔", "🎉", "✨",
    "💯", "✅", "❌", "⚠️", "🇨🇺", "☕", "🍺", "🍕", "🎶", "📷", "🚀", "🌴")

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥")

class ChatApi(base: String,val token: String = "") {
            val root = base.trim().trimEnd('/')

            fun call(method: String, path: String, body: JSONObject? = null, readMs: Int = 15000): JSONObject {
        try {
            if (root == DnsBackend.ROOT) {            // modo OFFLINE: todo por DNS
                try { return DnsBackend.handle(method, path, body) }
                catch (e: DnsErr) { throw ChatError(e.code, e.message ?: errText(e.code)) }
            }
            val c = URL(root + path).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = readMs
            c.requestMethod = method
            if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val o = runCatching { JSONObject(txt) }.getOrNull()
            if (code !in 200..299) {
                val k = o?.optString("error").orEmpty().ifEmpty { "http_$code" }
                throw ChatError(k, errText(k))
            }
            return o ?: JSONObject()
        } catch (e: ChatError) {
            throw e
        } catch (e: Exception) {
            throw ChatError("offline", "Sin conexión con el servidor del chat")
        }
    }


    private fun roomQ(room: String) =
         if (room == "global" || room.startsWith("g:")) "room=" + room else "peer=" + URLEncoder.encode(room.removePrefix("@"), "UTF-8")

            fun msgOf(o: JSONObject): CMsg {
        val rp = o.optJSONObject("reply")?.let {
            CReply(it.optLong("id"), it.optString("from"), it.optString("text"), it.optString("kind", "text"), it.optBoolean("deleted"))
        }
        val rx = LinkedHashMap<String, List<String>>()
        o.optJSONObject("reactions")?.let { r ->
            r.keys().forEach { k ->
                val a = r.optJSONArray(k)
                if (a != null && a.length() > 0) rx[k] = (0 until a.length()).map { i -> a.optString(i) }
            }
        }
        return CMsg(
            o.optLong("id"), o.optString("from"), o.optString("text"), o.optLong("ts"),
            o.optBoolean("edited"), o.optBoolean("deleted"), o.optBoolean("pinned"), o.optString("kind", "text"),
            o.optString("delBy"), rp, rx)
    }

    private fun list(a: org.json.JSONArray?): List<CMsg> =
        if (a == null) emptyList() else (0 until a.length()).map { msgOf(a.getJSONObject(it)) }

    // ---- cuenta ----
    fun register(name: String, password: String): JSONObject {
        val b = JSONObject().put("name", name)
        if (password.isNotEmpty()) b.put("password", password)
        return call("POST", "/api/register", b)
    }
    fun login(name: String, password: String): JSONObject =
        call("POST", "/api/login", JSONObject().put("name", name).put("password", password))
    fun logout() { call("POST", "/api/logout", JSONObject()) }
    fun me(): JSONObject = call("GET", "/api/me")
    fun changePassword(new: String, old: String) {
        val b = JSONObject().put("password", new)
        if (old.isNotEmpty()) b.put("old", old)
        call("POST", "/api/password", b)
    }

    // ---- usuarios / hilos ----
    fun users(query: String): List<CUser> {
        val a = call("GET", "/api/users?q=" + URLEncoder.encode(query, "UTF-8")).optJSONArray("users") ?: return emptyList()
        return (0 until a.length()).map { val o = a.getJSONObject(it); CUser(o.optString("name"), o.optBoolean("online")) }
    }

    fun threads(): List<CThread> {
        val a = call("GET", "/api/threads").optJSONArray("threads") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CThread(o.optString("peer"), o.optString("text"), o.optLong("ts"), o.optBoolean("mine"),
                o.optInt("unread"), o.optBoolean("deleted"), o.optString("kind", "text"))
        }
    }

    // ---- mensajes ----
    /** kind: "text" | "sticker" (emoji) | "simg" (imagen de un pack, text = "pack/archivo.webp"). reply = id al que respondes. */
    fun send(room: String, text: String, kind: String = "text", reply: Long = 0L): CMsg {
        val b = JSONObject().put("text", text)
        if (kind == "sticker" || kind == "simg") b.put("kind", kind)
        if (reply > 0) b.put("reply", reply)
        if (room == "global" || room.startsWith("g:")) b.put("room", room) else b.put("to", room.removePrefix("@"))
        return msgOf(call("POST", "/api/send", b).getJSONObject("message"))
    }

    /** Long-poll: devuelve nuevos + cambios (ediciones/borrados/anclados/reacciones) en cuanto ocurren, o vacio tras 'wait' s. */
    fun poll(room: String, after: Long, since: Long, wait: Int): Poll {
        val o = call("GET", "/api/messages?${roomQ(room)}&after=$after&since=$since&wait=$wait&limit=60",
            readMs = (wait + 12) * 1000)
        return Poll(list(o.optJSONArray("messages")), list(o.optJSONArray("updated")), o.optLong("rev"))
    }

    fun edit(id: Long, text: String): CMsg =
        msgOf(call("POST", "/api/edit", JSONObject().put("id", id).put("text", text)).getJSONObject("message"))

    fun delete(id: Long): CMsg =
        msgOf(call("POST", "/api/delete", JSONObject().put("id", id)).getJSONObject("message"))

    fun pin(id: Long, pinned: Boolean): CMsg =
        msgOf(call("POST", "/api/pin", JSONObject().put("id", id).put("pinned", pinned)).getJSONObject("message"))

    /** Pone, cambia o quita (mismo emoji otra vez) tu reaccion. */
    fun react(id: Long, emoji: String): CMsg =
        msgOf(call("POST", "/api/react", JSONObject().put("id", id).put("emoji", emoji)).getJSONObject("message"))

    fun pins(room: String): List<CMsg> = list(call("GET", "/api/pins?${roomQ(room)}").optJSONArray("messages"))

    // ---- stickers ----
    fun stickerUrl(rel: String): String =
        root + "/stickers/" + rel.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun stickerPacks(): List<CPack> {
        val o = call("GET", "/api/stickers", readMs = 25000)
        val a = o.optJSONArray("packs")
        if (a != null && a.length() > 0) return (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            val arr = p.optJSONArray("items")
            CPack(p.optString("id"), p.optString("name"), p.optString("type"), p.optString("icon"),
                if (arr == null) emptyList() else (0 until arr.length()).map { j -> arr.optString(j) })
        }
        val l = o.optJSONArray("stickers")
        val items = if (l == null) DEFAULT_STICKERS else (0 until l.length()).map { l.optString(it) }.filter { it.isNotEmpty() }
        return listOf(CPack("legacy", "Emoji", "emoji", "😀", items))
    }

    // ---- admin ----
    fun adminUsers(q: String): List<AUser> {
        val a = call("GET", "/api/admin/users?q=" + URLEncoder.encode(q, "UTF-8")).optJSONArray("users") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            AUser(o.optString("name"), o.optBoolean("online"), o.optBoolean("banned"), o.optBoolean("admin"), o.optInt("msgs"))
        }
    }
    fun adminBan(name: String, purge: Boolean): Int =
        call("POST", "/api/admin/ban", JSONObject().put("name", name).put("purge", purge)).optInt("purged")
    fun adminUnban(name: String) { call("POST", "/api/admin/unban", JSONObject().put("name", name)) }
    fun adminRemove(name: String): Int =
        call("POST", "/api/admin/remove", JSONObject().put("name", name)).optInt("purged")
}

// =====================================================================
//  Imagenes de stickers: descarga + cache en memoria (sin librerias)
// =====================================================================
private const val STICKER_PX = 256
private val netGate = Semaphore(6)
private val stickerCache = object : LruCache<String, ImageBitmap>(
    minOf(Runtime.getRuntime().maxMemory() / 10, 48L * 1024 * 1024).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private fun fetchSticker(url: String): ImageBitmap? {
    stickerCache.get(url)?.let { return it }
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        val bytes = c.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var ss = 1
        while (bounds.outWidth / (ss * 2) >= STICKER_PX && bounds.outHeight / (ss * 2) >= STICKER_PX) ss *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
            ?.asImageBitmap()
        if (bmp != null) stickerCache.put(url, bmp)
        bmp
    } catch (e: Exception) { null }
}

@Composable
private fun StickerImage(url: String, size: Dp) {
    val bmp by produceState<ImageBitmap?>(stickerCache.get(url), url) {
        if (value == null) value = netGate.withPermit { withContext(Dispatchers.IO) { fetchSticker(url) } }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size), contentScale = ContentScale.Fit) else Box(Modifier.size(size))
}

/** El telefono solo dibuja los emoji que su fuente conoce; el resto se descarta. */
private val glyphPaint by lazy { Paint() }
private fun canDraw(s: String) = Build.VERSION.SDK_INT < 23 || glyphPaint.hasGlyph(s)

private var packsCache: List<CPack> = emptyList()

// =====================================================================
//  Sesion (se guarda en las preferencias; el respaldo de Ajustes la incluye)
// =====================================================================
private const val PREFS = "cuvay_ui"
private const val DEFAULT_URL = "http://172.235.128.53:8091"

class ChatSession(val url: String, val token: String, val name: String)

        fun loadSession(ctx: Context): ChatSession {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return ChatSession(p.getString("chat_url", DEFAULT_URL) ?: DEFAULT_URL,
        p.getString("chat_token", "") ?: "", p.getString("chat_name", "") ?: "")
}

private fun saveSession(ctx: Context, url: String, token: String, name: String) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("chat_url", url).putString("chat_token", token).putString("chat_name", name).apply()
}

/** Stickers usados recientemente: "e:<emoji>" o "i:<pack/archivo>". */
private fun loadRecents(ctx: Context): List<String> =
    (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("chat_recent", "") ?: "")
        .split("\u0001").filter { it.isNotEmpty() }

private fun pushRecent(ctx: Context, e: String): List<String> {
    val l = (listOf(e) + loadRecents(ctx).filter { it != e }).take(32)
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("chat_recent", l.joinToString("\u0001")).apply()
    return l
}

// =====================================================================
//  Helpers de UI
// =====================================================================
private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun dayKey(ts: Long): Int {
    val c = Calendar.getInstance(); c.timeInMillis = ts * 1000
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(ts: Long): String {
    val nowSec = System.currentTimeMillis() / 1000
    val k = dayKey(ts)
    return when {
        k == dayKey(nowSec) -> "Hoy"
        k == dayKey(nowSec - 86400) -> "Ayer"
        else -> SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(ts * 1000))
    }
}

/** Texto corto para vistas previas (respuestas, anclados, lista de privados). */
private fun previewOf(kind: String, text: String, deleted: Boolean) = when {
    deleted -> "Mensaje eliminado"
    kind == "simg" -> "🖼 Sticker"
    kind == "sticker" -> text
    kind == "image" -> "📷 Foto"
    kind == "video" -> "🎬 Video"
    kind == "audio" -> if (text.contains("voz_")) "🎤 Mensaje de voz" else "🎵 Audio"
    kind == "file" -> "📎 Archivo"
    kind == "location" -> "📍 Ubicacion"
    else -> text.replace("\n", " ")
}

@Composable
internal fun ChatInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
                      maxLines: Int = 1, color: Color = Chip, password: Boolean = false) =
    Box(modifier.card(color, 22.dp)) {
        if (value.isEmpty()) Text(hint, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Muted, fontSize = 15.sp)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            maxLines = maxLines, singleLine = maxLines == 1, cursorBrush = SolidColor(Blue),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default)
    }

@Composable
private fun Label(t: String) =
    Text(t, Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp), color = Soft, fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)

private fun idFilter(v: String) = v.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(16)

/** Buscador: igual que idFilter pero deja pegar una dirección DNS completa (20 caracteres). */
private fun searchFilter(v: String) = v.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(24)

// =====================================================================
//  Pantalla del chat
// =====================================================================
@Composable
fun ChatScreen(collapsed: Boolean = false) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(loadSession(ctx)) }
    val api = remember(s.url, s.token) { ChatApi(s.url, s.token) }
    // Cierra sesion local (siempre) y en el servidor (si se puede).
    val clear = { saveSession(ctx, s.url, "", ""); s = loadSession(ctx) }
    val logout: () -> Unit = {
        val a = api
        if (s.token.isNotEmpty()) Thread { runCatching { a.logout() } }.start()
        clear()
    }
    if (s.token.isEmpty()) Onboarding(s.url) { url, token, name -> saveSession(ctx, url, token, name); s = loadSession(ctx) }
    else ChatHome(api, s.name, clear, logout, collapsed)
}

/** El mismo chat bonito (global, privados, grupos) pero sobre el mensajero DNS (modo OFFLINE). */
@Composable
fun ChatHomeDns(collapsed: Boolean = false, onExit: () -> Unit) {
    val api = remember { ChatApi(DnsBackend.ROOT, "dns") }
    ChatHome(api, DnsBackend.nick(), onExit, onExit, collapsed)
}

/** Entrar con ID y clave, o crear una cuenta nueva. */
@Composable
private fun Onboarding(url0: String, done: (String, String, String) -> Unit) {
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(url0) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var adv by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().card().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagMark(Modifier.size(36.dp, 24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Chat Cuvay", color = Blue, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("ENTRAR", if (!register) Blue else Muted) { register = false; err = "" }
                Btn("CREAR CUENTA", if (register) Blue else Muted) { register = true; err = "" }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (register) "Elige tu ID" else "Entra con tu ID", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(if (register) "Así te verán todos en el chat. Ejemplos: titoflow, rey5, cubano_21. La clave protege tu ID."
                else "Usa el ID y la clave de tu cuenta.", color = Muted, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@", color = Blue, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 8.dp))
                ChatInput(name, { name = idFilter(it) }, "tu_id", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ChatInput(pass, { pass = it.take(64) }, if (register) "Clave (6 a 64 caracteres)" else "Clave",
                Modifier.fillMaxWidth(), password = true)
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(14.dp))
            Btn(if (busy) "ESPERA..." else if (register) "CREAR Y ENTRAR" else "ENTRAR AL CHAT", Red) {
                val n = name.trim()
                if (busy) return@Btn
                if (n.length < 3) { err = "Usa de 3 a 16 letras, números o _"; return@Btn }
                if (!register && pass.isEmpty()) { err = "Escribe tu clave"; return@Btn }
                if (register && pass.isNotEmpty() && pass.length < 6) { err = errText("password_invalid"); return@Btn }
                busy = true; err = ""
                scope.launch {
                    val u = url.trim().trimEnd('/')
                    val r = withContext(Dispatchers.IO) {
                        runCatching { if (register) ChatApi(u).register(n, pass) else ChatApi(u).login(n, pass) }
                    }
                    busy = false
                    val o = r.getOrNull()
                    if (o != null) done(u, o.optString("token"), o.optString("name", n))
                    else err = r.exceptionOrNull()?.message ?: "Error"
                }
            }
            if (busy) BarLoader(null, Modifier.padding(top = 10.dp), track = Chip)
            Text(if (adv) "OCULTAR SERVIDOR" else "SERVIDOR", Modifier.padding(top = 14.dp).clickable { adv = !adv },
                color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            if (adv) ChatInput(url, { url = it }, "http://servidor:8091", Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}


@Composable
private fun ChatHome(api: ChatApi, me: String, onAuthFail: () -> Unit, logout: () -> Unit, hideBar: Boolean = false) {
    var tab by remember { mutableStateOf(0) }
    var dm by remember { mutableStateOf<String?>(null) }
    var grp by remember { mutableStateOf<String?>(null) }
    var admin by remember { mutableStateOf(false) }
    var hasPw by remember { mutableStateOf(true) }
    var showPw by remember { mutableStateOf(false) }
    // Navegación desde notificaciones (abre DM o Global dentro del Chat)
    LaunchedEffect(Alerts.tapTarget) {
        val t = Alerts.tapTarget ?: return@LaunchedEffect
        if (t == "global") { tab = 0; dm = null; grp = null; Alerts.tapTarget = null }
        else if (t.startsWith("dm:")) { tab = 1; dm = t.removePrefix("dm:"); grp = null; Alerts.tapTarget = null }
    }

    BackHandler(enabled = dm != null || grp != null) { dm = null; grp = null }

    LaunchedEffect(api) {
        val r = withContext(Dispatchers.IO) { runCatching { api.me() } }
        r.onSuccess { admin = it.optBoolean("admin"); hasPw = it.optBoolean("hasPassword", true) }
            .onFailure { e -> if (e.isAuth()) onAuthFail() }
    }

    Column(Modifier.fillMaxSize()) {
        val d = dm
        if (d == null && grp == null) {
            // Con el panel encogido (pastilla de arriba) se esconde GLOBAL / PRIVADOS / GRUPOS.
            androidx.compose.animation.AnimatedVisibility(visible = !hideBar,
                enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
                Column {
                    Seg(listOf("GLOBAL", "PRIVADOS", "GRUPOS"), tab) { tab = it }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        when {
            d != null -> RoomView(api, me, admin, "@$d", onAuthFail) { dm = null }
            grp != null -> RoomView(api, me, admin, grp!!, onAuthFail) { grp = null }
            tab == 0 -> RoomView(api, me, admin, "global", onAuthFail)
            tab == 1 -> DmList(api, me, hasPw, onAuthFail, { showPw = true }, logout) { dm = it }
            else -> GroupsList(api, me, onAuthFail) { grp = it }
        }
    }
    if (showPw) PasswordDialog(api, hasPw, onAuthFail, { hasPw = true; showPw = false }) { showPw = false }
}

/** Poner o cambiar la clave. Las cuentas viejas sin clave no piden la anterior. */
@Composable
private fun PasswordDialog(api: ChatApi, hasPw: Boolean, onAuthFail: () -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().card(Color.White, 22.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (hasPw) "Cambiar clave" else "Ponle clave a tu cuenta", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(if (hasPw) "Al cambiarla se cierran tus otras sesiones."
                else "Sin clave, quien tenga tu sesión puede usar tu ID. Con clave podrás entrar desde otro teléfono.",
                color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (hasPw) ChatInput(old, { old = it.take(64) }, "Clave actual", Modifier.fillMaxWidth(), password = true)
            ChatInput(new, { new = it.take(64) }, "Clave nueva (6 a 64)", Modifier.fillMaxWidth(), password = true)
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (busy) "GUARDANDO..." else "GUARDAR", Red) {
                    if (busy) return@Btn
                    if (new.length < 6) { err = errText("password_invalid"); return@Btn }
                    busy = true; err = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { api.changePassword(new, old) } }
                        busy = false
                        val e = r.exceptionOrNull()
                        when {
                            e == null -> onDone()
                            e.isAuth() -> onAuthFail()
                            else -> err = e.message ?: "Error"
                        }
                    }
                }
                Btn("CANCELAR", Ink) { onDismiss() }
            }
        }
    }
}

// =====================================================================
//  Panel de stickers: pestañas por pack + cuadricula
// =====================================================================
private class StTab(val id: String, val icon: String, val image: Boolean)
private class StCell(val image: Boolean, val value: String)

/** emojiOnly = true -> solo packs de emoji (para elegir una reaccion). onPick(esImagen, valor). */
@Composable
private fun StickerPanel(api: ChatApi, packs: List<CPack>, recents: List<String>, emojiOnly: Boolean,
                         modifier: Modifier = Modifier, onPick: (Boolean, String) -> Unit) {
    val usable = remember(packs, emojiOnly) { if (emojiOnly) packs.filter { it.type == "emoji" } else packs }
    val showRec = !emojiOnly && recents.isNotEmpty()
    val tabs = remember(usable, showRec) {
        buildList {
            if (showRec) add(StTab("_rec", "🕘", false))
            usable.forEach { add(StTab(it.id, it.icon, it.type == "image")) }
        }
    }
    var sel by remember { mutableStateOf("") }
    if (tabs.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cargando stickers…", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                BarLoader(null, Modifier.width(140.dp), track = Chip)
            }
        }
    } else {
        val cur = tabs.firstOrNull { it.id == sel } ?: tabs.first()
        val cells = remember(cur.id, usable, if (cur.id == "_rec") recents else null) {
            if (cur.id == "_rec") recents.map { StCell(it.startsWith("i:"), it.drop(2)) }
            else {
                val p = usable.first { it.id == cur.id }
                if (p.type == "image") p.items.map { StCell(true, it) }
                else p.items.filter { canDraw(it) }.map { StCell(false, it) }
            }
        }
        val imgGrid = cells.firstOrNull()?.image == true
        Column(modifier) {
            LazyRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tabs, key = { it.id }) { t ->
                    Box(Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (t.id == cur.id) Chip else Color.Transparent)
                        .clickable { sel = t.id }.padding(8.dp), contentAlignment = Alignment.Center) {
                        if (t.image) StickerImage(api.stickerUrl(t.icon), 28.dp) else Text(t.icon, fontSize = 22.sp)
                    }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(if (imgGrid) 76.dp else 46.dp), Modifier.fillMaxWidth().weight(1f)) {
                items(cells, key = { (if (it.image) "i" else "e") + it.value }) { c ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(c.image, c.value) }.padding(4.dp), contentAlignment = Alignment.Center) {
                        if (c.image) StickerImage(api.stickerUrl(c.value), 68.dp) else Text(c.value, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

// =====================================================================
//  Panel de admin: ver usuarios, banear, desbanear, borrar cuenta
// =====================================================================
private class Confirm(val title: String, val text: String, val run: () -> String)

@Composable
private fun ConfirmDialog(c: Confirm, onYes: () -> Unit, onNo: () -> Unit) =
    Dialog(onDismissRequest = onNo) {
        Column(Modifier.fillMaxWidth().card(Color.White, 22.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(c.title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(c.text, color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("CONFIRMAR", Red) { onYes() }
                Btn("CANCELAR", Ink) { onNo() }
            }
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminDialog(api: ChatApi, initial: String, onAuthFail: () -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf(initial) }
    var users by remember { mutableStateOf<List<AUser>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(q, tick) {
        delay(200)
        val r = withContext(Dispatchers.IO) { runCatching { api.adminUsers(q.trim().lowercase()) } }
        r.onSuccess { users = it }.onFailure { if (it.isAuth()) onAuthFail() else msg = it.message ?: "Error" }
    }
    fun go(c: Confirm) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { c.run() } }
            r.onSuccess { msg = it; tick++ }.onFailure { if (it.isAuth()) onAuthFail() else msg = it.message ?: "Error" }
        }
    }

    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).card(Color.White, 22.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🛡 Administrar usuarios", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            ChatInput(q, { q = idFilter(it) }, "Buscar por ID…", Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg, color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (users.isEmpty()) item {
                    Text("Nadie con ese ID", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                items(users, key = { it.name }) { u ->
                    Column(Modifier.fillMaxWidth().card(Chip, 12.dp).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.name, 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("@" + u.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Black)
                                Text(u.msgs.toString() + " mensajes en el global", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            when {
                                u.admin -> Text("ADMIN", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                u.banned -> Text("SUSPENDIDO", color = Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                u.online -> Text("en línea", color = Color(0xFF059669), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        if (!u.admin) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (u.banned) Btn("DESBANEAR", Blue) {
                                go(Confirm("", "") { api.adminUnban(u.name); "@${u.name} ya puede entrar" })
                            } else {
                                Btn("BANEAR", Ink) {
                                    confirm = Confirm("Banear a @${u.name}",
                                        "No podrá entrar ni escribir. Sus mensajes se quedan.") {
                                        api.adminBan(u.name, false); "@${u.name} baneado"
                                    }
                                }
                                Btn("BANEAR + BORRAR MSGS", Red) {
                                    confirm = Confirm("Banear y borrar mensajes de @${u.name}",
                                        "No podrá entrar y se borran todos sus mensajes del chat global.") {
                                        val n = api.adminBan(u.name, true); "@${u.name} baneado · $n mensajes borrados"
                                    }
                                }
                            }
                            Btn("ELIMINAR CUENTA", Red) {
                                confirm = Confirm("Eliminar la cuenta de @${u.name}",
                                    "Se borra la cuenta y sus mensajes del global. El ID queda libre para que otro lo use.") {
                                    val n = api.adminRemove(u.name); "@${u.name} eliminado · $n mensajes borrados"
                                }
                            }
                        }
                    }
                }
            }
            Btn("CERRAR", Ink) { onClose() }
        }
    }
    confirm?.let { c -> ConfirmDialog(c, { confirm = null; go(c) }, { confirm = null }) }
}

// =====================================================================
//  Una conversacion: sala global o privado "@usuario"
// =====================================================================
private class Pend(val id: Long, val text: String, val kind: String, val err: String?, val reply: Long = 0L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomView(api: ChatApi, me: String, admin: Boolean, room: String, onAuthFail: () -> Unit, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val msgs = remember(room) { mutableStateListOf<CMsg>() }
    val pend = remember(room) { mutableStateListOf<Pend>() }
    val outs = remember(room) { mutableStateListOf<OutUpload>() }          // fotos/videos/archivos subiendo
    var loadingFirst by remember(room) { mutableStateOf(true) }            // primera carga de mensajes
    var pins by remember(room) { mutableStateOf<List<CMsg>>(emptyList()) }
    var text by remember(room) { mutableStateOf("") }
    var status by remember(room) { mutableStateOf("") }
    var note by remember(room) { mutableStateOf("") }
    var editing by remember(room) { mutableStateOf<CMsg?>(null) }
    var replyTo by remember(room) { mutableStateOf<CMsg?>(null) }
    var actionsFor by remember(room) { mutableStateOf<CMsg?>(null) }
    var reactFor by remember(room) { mutableStateOf<CMsg?>(null) }
    var adminFor by remember(room) { mutableStateOf<String?>(null) }
    var showPins by remember(room) { mutableStateOf(false) }
    var showStickers by remember(room) { mutableStateOf(false) }
    var packs by remember { mutableStateOf(packsCache) }
    var recents by remember { mutableStateOf(loadRecents(ctx)) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val isGlobal = room == "global"
    val isGroup = room.startsWith("g:")
    val peer = room.removePrefix("@")
    val rows = msgs.sortedBy { it.id }.asReversed()   // el mas nuevo primero (reverseLayout)

    fun fail(e: Throwable?) {
        if (e.isAuth()) onAuthFail() else note = e?.message ?: "Error"
    }
    fun applyMsg(m: CMsg) {
        val i = msgs.indexOfFirst { it.id == m.id }
        if (i >= 0) msgs[i] = m else msgs.add(m)
    }
    val rec = rememberVoiceRec()
    var voiceBusy by remember(room) { mutableStateOf(false) }
    fun sendVoice(f: java.io.File, sec: Int) {
        val reply = replyTo?.id ?: 0L
        replyTo = null; voiceBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.sendVoice(room, f, sec, reply) } }
            voiceBusy = false
            runCatching { f.delete() }
            r.onSuccess { applyMsg(it) }.onFailure { note = uiErr(it) }
        }
    }
    suspend fun loadPins() {
        val r = withContext(Dispatchers.IO) { runCatching { api.pins(room) } }
        r.getOrNull()?.let { pins = it }
    }

    // Tiempo real: long-poll. Trae mensajes nuevos y cambios (editar/borrar/anclar/reaccionar) de los ya vistos.
    LaunchedEffect(room) {
        var after = 0L
        var since = 0L
        var first = true
        loadPins()
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { api.poll(room, after, since, if (first) 0 else 20) } }
            val got = r.getOrNull()
            if (got != null) {
                status = ""; first = false; loadingFirst = false
                for (m in got.messages) if (msgs.none { it.id == m.id }) msgs.add(m)
                if (got.messages.isNotEmpty()) after = maxOf(after, got.messages.maxOf { it.id })
                for (u in got.updated) {
                    val i = msgs.indexOfFirst { it.id == u.id }
                    if (i >= 0) msgs[i] = u
                }
                if (got.rev > since) since = got.rev
                if (got.messages.isNotEmpty())
                    pend.removeAll { p -> p.err == null && msgs.any { it.from == me && it.text == p.text } }
                if (got.updated.isNotEmpty()) loadPins()
            } else {
                val e = r.exceptionOrNull()
                if (e.isAuth()) { onAuthFail(); return@LaunchedEffect }
                status = e?.message ?: "Sin conexión"
                delay(3000)
            }
        }
    }
    // Los packs se bajan una sola vez, la primera vez que se abre el panel de stickers o el selector de reacciones.
    LaunchedEffect(showStickers, reactFor) {
        if ((showStickers || reactFor != null) && packs.isEmpty()) {
            val r = withContext(Dispatchers.IO) { runCatching { api.stickerPacks() } }
            val got = r.getOrNull()?.takeIf { it.isNotEmpty() }
            if (got != null) { packs = got; packsCache = got }
            else packs = listOf(CPack("legacy", "Emoji", "emoji", "😀", DEFAULT_STICKERS))
        }
    }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(4000); note = "" } }
    // Baja solo cuando llega algo y ya estabas abajo; al enviar siempre baja.
    LaunchedEffect(msgs.size) { if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) }
    LaunchedEffect(pend.size) { listState.animateScrollToItem(0) }

    fun send(t: String, kind: String = "text", old: Pend? = null, reply: Long = old?.reply ?: 0L) {
        val id = old?.id ?: System.nanoTime()
        val at = pend.indexOfFirst { it.id == id }
        if (at >= 0) pend[at] = Pend(id, t, kind, null, reply) else pend.add(Pend(id, t, kind, null, reply))
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.send(room, t, kind, reply) } }
            val j = pend.indexOfFirst { it.id == id }
            val m = r.getOrNull()
            if (m != null) {
                if (msgs.none { it.id == m.id }) msgs.add(m)
                if (j >= 0) pend.removeAt(j)
            } else {
                val e = r.exceptionOrNull()
                if (e.isAuth()) { onAuthFail(); return@launch }
                if (j >= 0) pend[j] = Pend(id, t, kind, e?.message ?: "No enviado", reply)
            }
        }
    }
    fun doEdit(target: CMsg, t: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.edit(target.id, t) } }
            r.onSuccess { applyMsg(it) }.onFailure { fail(it) }
        }
    }
    fun doDelete(target: CMsg) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.delete(target.id) } }
            r.onSuccess {
                applyMsg(it)
                if (editing?.id == it.id) { editing = null; text = "" }
                if (replyTo?.id == it.id) replyTo = null
                loadPins()
            }.onFailure { fail(it) }
        }
    }
    fun doPin(target: CMsg) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.pin(target.id, !target.pinned) } }
            r.onSuccess { applyMsg(it); loadPins() }.onFailure { fail(it) }
        }
    }
    fun doReact(target: CMsg, emoji: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.react(target.id, emoji) } }
            r.onSuccess { applyMsg(it) }.onFailure { fail(it) }
        }
    }
    fun jumpTo(id: Long) {
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) scope.launch { listState.animateScrollToItem(pend.size + i) }
        else note = "Ese mensaje es muy antiguo"
    }
    fun pickSticker(image: Boolean, v: String) {
        recents = pushRecent(ctx, (if (image) "i:" else "e:") + v)
        send(v, if (image) "simg" else "sticker", reply = replyTo?.id ?: 0L)
        replyTo = null
    }

    Column(Modifier.fillMaxSize()) {
        // Cabecera de la sala
        Row(Modifier.fillMaxWidth().card(Glass, 16.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Text("‹", Modifier.clickable { onBack() }.padding(start = 4.dp, end = 12.dp),
                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            if (isGlobal || isGroup) Box(Modifier.size(38.dp).rounded(19.dp).background(Red), contentAlignment = Alignment.Center) {
                Text("★", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            } else Avatar(peer, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isGlobal) "Chat global" else if (isGroup) (RoomNames.name[room] ?: "Grupo") else "@$peer",
                    fontWeight = FontWeight.Black, maxLines = 1)
                val sub = when {
                    status.isNotEmpty() -> status
                    note.isNotEmpty() -> note
                    isGroup -> "Grupo · en vivo"; isGlobal -> "Todos los usuarios · en vivo"
                    else -> "Mensaje privado · en vivo"
                }
                Text(sub, color = if (status.isNotEmpty() || note.isNotEmpty()) Color(0xFFFFB4BD) else Soft,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isGlobal && admin) Text("🛡", Modifier.clickable { adminFor = "" }.padding(horizontal = 8.dp), fontSize = 22.sp)
        }
        // Barra de anclados
        if (pins.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().card(Glass, 12.dp).clickable { showPins = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("📌", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(pins.first().let { (if (isGlobal || isGroup) "@" + it.from + ": " else "") + previewOf(it.kind, it.text, false) },
                    Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pins.size > 1) Text("${pins.size}", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loadingFirst && rows.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cargando mensajes…", color = Soft, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                BarLoader(null, Modifier.width(160.dp))
            }
            else if (rows.isEmpty() && pend.isEmpty() && outs.isEmpty()) Text("Aquí empieza la conversación.\n¡Escribe el primer mensaje!",
                Modifier.align(Alignment.Center).padding(24.dp), color = Soft, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
                items(outs.reversed(), key = { "u" + it.id }) { o ->
                    UploadBubble(o,
                        onCancel = { o.cancelled = true; outs.remove(o) },
                        onRetry = { startUpload(ctx, api, room, o) { m -> outs.remove(o); applyMsg(m) } })
                }
                items(pend.reversed(), key = { "p" + it.id }) { p -> PendBubble(api, p) { send(p.text, p.kind, p) } }
                itemsIndexed(rows, key = { _, m -> m.id }) { i, m ->
                    val older = rows.getOrNull(i + 1)
                    val newer = if (i > 0) rows[i - 1] else null
                    val newDay = older == null || dayKey(older.ts) != dayKey(m.ts)
                    val head = newDay || older == null || older.from != m.from || m.ts - older.ts > 300
                    val glued = newer != null && newer.from == m.from && newer.ts - m.ts <= 300 && dayKey(newer.ts) == dayKey(m.ts)
                    Column(Modifier.fillMaxWidth()) {
                        if (newDay) DayChip(dayLabel(m.ts))
                        Bubble(api, m, me, isGlobal || isGroup, head, if (glued) 2.dp else 10.dp,
                            onLong = { if (!m.deleted) actionsFor = m },
                            onReact = { e -> doReact(m, e) },
                            onReplyClick = { id -> jumpTo(id) })
                    }
                }
            }
        }
        // Panel de stickers
        if (showStickers) {
            StickerPanel(api, packs, recents, false,
                Modifier.fillMaxWidth().height(270.dp).card(Color.White, 16.dp).padding(8.dp)) { img, v -> pickSticker(img, v) }
            Spacer(Modifier.height(6.dp))
        }
        // Aviso de respuesta
        replyTo?.let { rp ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).card(Glass, 12.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Respondiendo a @" + rp.from, color = Soft, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Text(previewOf(rp.kind, rp.text, false), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("✕", Modifier.clickable { replyTo = null }.padding(start = 12.dp),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Aviso de edicion
        editing?.let { ed ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).card(Glass, 12.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Editando mensaje", color = Soft, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Text(ed.text.replace("\n", " "), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("✕", Modifier.clickable { editing = null; text = "" }.padding(start = 12.dp),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Barra de escribir
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom) {
            if (rec.active) RecordingBar(rec, Modifier.weight(1f)) else {
            if (editing == null) Text(if (showStickers) "⌨" else "☺", Modifier.rounded(22.dp)
                .background(if (showStickers) Color(0x66FFFFFF) else Glass).clickable { showStickers = !showStickers }
                .padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White, fontSize = 20.sp)
            if (editing == null && api.root != DnsBackend.ROOT) AttachButton(api, room, replyTo?.id ?: 0L, outs,
                onStart = { replyTo = null }, onSent = { applyMsg(it) }, onError = { note = it })

            ChatInput(text, { text = it.take(1000) }, "Escribe un mensaje…", Modifier.weight(1f), maxLines = 4, color = Color.White)
            }
            if (text.isBlank() && editing == null && api.root != DnsBackend.ROOT) MicButton(rec, voiceBusy, onSend = { f, sec -> sendVoice(f, sec) }, onNote = { note = it })
            else {
            val can = text.isNotBlank()
            Text(if (editing != null) "GUARDAR" else "ENVIAR", Modifier.rounded(22.dp).background(if (can) Red else Color(0x66CF142B))
                .clickable(enabled = can) {
                    val t = text.trim(); val ed = editing; val rp = replyTo
                    text = ""; editing = null; replyTo = null
                    if (ed != null) { if (t != ed.text) doEdit(ed, t) } else send(t, reply = rp?.id ?: 0L)
                }
                .padding(horizontal = 18.dp, vertical = 14.dp),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }

    // Acciones sobre un mensaje (mantener pulsado)
    actionsFor?.let { m ->
        val mine = m.from == me
        val canPin = (!isGlobal && !isGroup) || admin || (isGroup && RoomNames.mod[room] == true)
        val canDel = mine || (admin && isGlobal) || (isGroup && RoomNames.mod[room] == true)
        Dialog(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.fillMaxWidth().card(Color.White, 22.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (m.kind == "simg") StickerImage(api.stickerUrl(m.text), 90.dp)
                else Text(if (m.kind == "sticker") m.text else m.text.take(140), color = Ink,
                    fontSize = if (m.kind == "sticker") 36.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (m.reactions.isNotEmpty()) Text(
                    m.reactions.entries.joinToString("   ") { (e, u) -> e + " " + u.joinToString(", ") },
                    color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                // Reacciones rapidas
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QUICK_REACTIONS.forEach { e ->
                        Text(e, Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (m.reactions[e]?.contains(me) == true) Chip else Color.Transparent)
                            .clickable { doReact(m, e); actionsFor = null }.padding(8.dp), fontSize = 26.sp)
                    }
                    Text("➕", Modifier.clip(RoundedCornerShape(12.dp)).background(Chip)
                        .clickable { reactFor = m; actionsFor = null }.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 20.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("RESPONDER", Blue) { replyTo = m; editing = null; text = if (editing == null) text else ""; actionsFor = null }
                    if (m.kind == "text") Btn("COPIAR", Ink) { clip.setText(AnnotatedString(m.text)); actionsFor = null }
                    if (mine && m.kind == "text") Btn("EDITAR", Blue) { editing = m; replyTo = null; text = m.text; showStickers = false; actionsFor = null }
                    if (canPin) Btn(if (m.pinned) "DESANCLAR" else "ANCLAR", Blue) { doPin(m); actionsFor = null }
                    if (canDel) Btn(if (mine) "ELIMINAR" else "ELIMINAR (ADMIN)", Red) { doDelete(m); actionsFor = null }
                    if (admin && isGlobal && !mine) Btn("ADMINISTRAR @" + m.from, Ink) { adminFor = m.from; actionsFor = null }
                }
            }
        }
    }
    // Selector completo de reacciones
    reactFor?.let { m ->
        Dialog(onDismissRequest = { reactFor = null }) {
            StickerPanel(api, packs, emptyList(), true,
                Modifier.fillMaxWidth().height(340.dp).card(Color.White, 22.dp).padding(10.dp)) { _, e ->
                doReact(m, e); reactFor = null
            }
        }
    }
    // Panel de admin
    adminFor?.let { who -> AdminDialog(api, who, onAuthFail) { adminFor = null } }
    // Lista de anclados
    if (showPins) Dialog(onDismissRequest = { showPins = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).card(Color.White, 22.dp).padding(18.dp)) {
            Text("📌 Anclados", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pins.forEach { p ->
                    Column(Modifier.fillMaxWidth().card(Chip, 12.dp).padding(10.dp)) {
                        Text("@" + p.from + " · " + dayLabel(p.ts) + " " + hm.format(Date(p.ts * 1000)),
                            color = userColor(p.from), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        if (p.kind == "simg") StickerImage(api.stickerUrl(p.text), 60.dp)
                        else Text(p.text, color = Ink, fontSize = if (p.kind == "sticker") 30.sp else 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Btn("CERRAR", Ink) { showPins = false }
        }
    }
}

@Composable
private fun DayChip(t: String) =
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(t, Modifier.card(Glass, 12.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            color = Soft, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    }

/** Una burbuja: respuesta citada, contenido, reacciones y hora. Doble toque = ❤️. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Bubble(api: ChatApi, m: CMsg, me: String, global: Boolean, head: Boolean, gap: Dp,
                   onLong: () -> Unit, onReact: (String) -> Unit, onReplyClick: (Long) -> Unit) {
    val mine = m.from == me
    val shape = if (mine) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
    else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = gap),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (!mine && global) {
            if (head) Avatar(m.from, 32.dp) else Spacer(Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
        }
        val big = !m.deleted && (m.kind == "sticker" || m.kind == "simg")
        val bg = when {
            m.deleted -> Glass
            big -> Color.Transparent
            mine -> Mine
            else -> Color.White
        }
        Column(Modifier.widthIn(max = 280.dp).clip(shape).background(bg)
            .combinedClickable(onClick = {}, onDoubleClick = { if (!m.deleted) onReact("❤️") }, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!mine && global && head) Text("@" + m.from, color = userColor(m.from), fontSize = 12.sp, fontWeight = FontWeight.Black)
            // Mensaje al que responde
            m.reply?.let { r ->
                Column(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (mine && !big) Color(0x26FFFFFF) else Chip)
                    .clickable { onReplyClick(r.id) }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (r.from.isNotEmpty()) Text("@" + r.from, color = userColor(r.from), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(previewOf(r.kind, r.text, r.deleted), color = if (mine && !big) Color.White else Muted,
                        fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontStyle = if (r.deleted) FontStyle.Italic else FontStyle.Normal)
                }
            }
            when {
                m.deleted -> Text(if (m.delBy.isNotEmpty() && m.delBy != m.from) "Eliminado por un admin" else "Mensaje eliminado",
                    color = Soft, fontSize = 14.sp, fontStyle = FontStyle.Italic)
                m.kind == "simg" -> StickerImage(api.stickerUrl(m.text), 140.dp)
                m.kind == "sticker" -> Text(m.text, fontSize = 52.sp)
                m.kind in listOf("image", "video", "audio", "file", "location") -> AttachBubble(api, m.kind, m.text, mine)
                else -> Text(m.text, color = if (mine) Color.White else Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            // Reacciones
            if (!m.deleted && m.reactions.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                m.reactions.forEach { (emoji, users) ->
                    val iMe = me in users
                    val chipBg = when {
                        big -> if (iMe) Color(0x66FFFFFF) else Glass
                        mine -> if (iMe) Color(0x66FFFFFF) else Color(0x33FFFFFF)
                        else -> if (iMe) Color(0xFFD6E2FF) else Chip
                    }
                    Text("$emoji ${users.size}", Modifier.clip(RoundedCornerShape(12.dp)).background(chipBg)
                        .clickable { onReact(emoji) }.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = if (big || mine) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            val meta = (if (m.pinned && !m.deleted) "📌 " else "") + (if (m.edited && !m.deleted) "editado · " else "") +
                hm.format(Date(m.ts * 1000))
            Text(meta, Modifier.align(Alignment.End).padding(top = 2.dp),
                color = when { m.deleted || big -> Soft; mine -> Color(0xFFBFD0FF); else -> Muted },
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PendBubble(api: ChatApi, p: Pend, retry: () -> Unit) {
    val bad = p.err != null
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
        Column(Modifier.widthIn(max = 280.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp))
            .background(if (bad) Red else Color(0xFF5B7BDB))
            .then(if (bad) Modifier.clickable { retry() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (p.kind == "simg") StickerImage(api.stickerUrl(p.text), 90.dp)
            else Text(p.text, color = Color.White, fontSize = if (p.kind == "sticker") 40.sp else 15.sp, fontWeight = FontWeight.Medium)
            Text(if (bad) p.err + " · toca para reintentar" else "Enviando…", Modifier.align(Alignment.End).padding(top = 2.dp),
                color = Color(0xFFE0E8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Privados: conversaciones, buscador de usuarios, personas recientes y cuenta. */
@Composable
private fun DmList(api: ChatApi, me: String, hasPw: Boolean, onAuthFail: () -> Unit, onPassword: () -> Unit,
                   logout: () -> Unit, open: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf<List<CThread>>(emptyList()) }
    var people by remember { mutableStateOf<List<CUser>>(emptyList()) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { runCatching { api.threads() } }
            r.onSuccess { threads = it; err = "" }.onFailure {
                if (it.isAuth()) { onAuthFail(); return@LaunchedEffect }
                err = it.message ?: ""
            }
            delay(8000)
        }
    }
    LaunchedEffect(q) {
        delay(250)
        val r = withContext(Dispatchers.IO) { runCatching { api.users(q.trim().lowercase()) } }
        r.onSuccess { people = it }
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item {
            ChatInput(q, { q = searchFilter(it) }, "Buscar por ID o pegar dirección DNS…", Modifier.fillMaxWidth(), color = Color.White)
        }
        if (err.isNotEmpty()) item { Text(err, color = Color(0xFFFFB4BD), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        if (q.isBlank() && threads.isNotEmpty()) {
            item { Label("CONVERSACIONES") }
            items(threads, key = { "t" + it.peer }) { t ->
                Row(Modifier.fillMaxWidth().card().clickable { open(t.peer) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(t.peer, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@" + t.peer, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        val preview = previewOf(t.kind, t.text, t.deleted)
                        Text((if (t.mine) "Tú: " else "") + preview, color = Muted, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontStyle = if (t.deleted) FontStyle.Italic else FontStyle.Normal)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (dayKey(t.ts) == dayKey(System.currentTimeMillis() / 1000)) hm.format(Date(t.ts * 1000)) else dayLabel(t.ts),
                            color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (t.unread > 0) Text("${t.unread}", Modifier.padding(top = 4.dp).rounded(10.dp).background(Red)
                            .padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        item { Label(if (q.isBlank()) "PERSONAS" else "RESULTADOS") }
        if (people.isEmpty()) item {
            Text(if (q.isBlank()) "Aún no hay nadie más. Comparte la app para llenar el chat." else "Nadie con ese ID",
                color = Soft, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        }
        items(people, key = { "u" + it.name }) { u ->
            Row(Modifier.fillMaxWidth().card().clickable { open(u.name) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(u.name, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text("@" + u.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                if (u.online) Text("en línea", color = Color(0xFF059669), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        // Cuenta
        item {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).card(Glass, 14.dp).padding(12.dp)) {
                Text("Tu ID: @$me", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                if (!hasPw) Text("Tu cuenta no tiene clave. Ponle una para poder entrar desde otro teléfono y que nadie use tu ID.",
                    color = Color(0xFFFFE08A), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (hasPw) "CAMBIAR CLAVE" else "PONER CLAVE", if (hasPw) Ink else Red) { onPassword() }
                    Btn("SALIR", Ink) { logout() }
                }
            }
        }
    }
}
