package com.cuvay.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
//  Chat OFFLINE por DNS: usa el mensajero del servidor Go local
//  (/api/chat/*), el mismo del proyecto original. Cifrado de extremo a
//  extremo; todo viaja en consultas DNS, asi que es lento (minutos).
//  Para usarlo: en TheFeedApp/CuvayApp cambia `ChatScreen()` por `ChatTab(baseUrl)`.
// =====================================================================

private const val DNS_PREFS = "cuvay_ui"
private const val DNS_MODE = "chat_mode"   // "online" | "offline"

/** Llamada HTTP al servidor Go local. Lanza IOException con el motivo si no es 2xx. */
private fun dnsCall(base: String, method: String, path: String, body: JSONObject? = null, readMs: Int = 15000): String {
    val c = URL(base + path).openConnection() as HttpURLConnection
    c.connectTimeout = 5000; c.readTimeout = readMs
    c.requestMethod = method
    if (body != null) {
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
    }
    val code = c.responseCode
    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    if (code !in 200..299) throw java.io.IOException("HTTP $code" + if (txt.isNotBlank()) ": " + txt.trim().take(120) else "")
    return txt
}

private fun dnsEnc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun dnsErr(key: String): String = when (key) {
    "chat_err_no_key" -> "Tu config no tiene la clave pública del servidor (sk). Agrégala con esa clave."
    "chat_err_disabled" -> "Este servidor no tiene la mensajería activada."
    "chat_err_unreachable" -> "No se pudo conectar al mensajero por DNS ahora. Revisa los resolvers y reintenta."
    "chat_err_unverified" -> "No se pudo verificar la firma del servidor."
    "chat_err_version" -> "El mensajero de este servidor es de otra versión."
    "chat_err_rate_limited" -> "Llegaste al límite de envíos de esta hora."
    "chat_err_inbox_full" -> "El buzón del contacto está lleno."
    "chat_err_pair_quota" -> "Tienes demasiados mensajes sin entregar a este contacto; espera un rato."
    "chat_err_unknown_recipient" -> "Ese contacto no está registrado en este servidor."
    "chat_err_busy" -> "Servidor ocupado; reintenta en un momento."
    "chat_err_server_disabled" -> "La mensajería está desactivada temporalmente en este servidor."
    "" -> "Falló el envío"
    else -> "Falló ($key)"
}

private fun dnsObjs(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

private data class DnsThreadRow(val addr: String, val name: String, val lastText: String, val lastTs: Long,
                                val unread: Int, val pinned: Boolean)
private data class DnsMsg(val dir: String, val seq: Long, val text: String, val ts: Long)

private fun dnsCopy(ctx: Context, label: String, text: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

// ---------------------------------------------------------------------
//  Selector ONLINE / OFFLINE (reemplaza a ChatScreen() en la pestaña Chat)
// ---------------------------------------------------------------------
@Composable
fun ChatTab(baseUrl: String) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(DNS_PREFS, Context.MODE_PRIVATE) }
    var offline by remember { mutableStateOf(prefs.getString(DNS_MODE, "online") == "offline") }
    var collapsed by remember { mutableStateOf(prefs.getBoolean("chat_mode_collapsed", false)) }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize()) {
        if (!imeOpen) {
            // Pastillita para encoger / abrir el selector (igual que la del feed): flechita que gira.
            val turn by animateFloatAsState(if (collapsed) 180f else 0f, label = "chatChev")
            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.rounded(20.dp).background(Chip)
                    .clickable { collapsed = !collapsed; prefs.edit().putBoolean("chat_mode_collapsed", collapsed).apply() }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(11.dp, 7.dp).rotate(turn)) {
                        val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
                        drawLine(color = Blue, start = Offset(0f, h), end = Offset(w / 2f, 0f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(color = Blue, start = Offset(w / 2f, 0f), end = Offset(w, h), strokeWidth = sw, cap = StrokeCap.Round)
                    }
                    Spacer(Modifier.width(7.dp))
                    // Encogido igual dice en que modo estas, para no quedarte a ciegas.
                    Text(if (collapsed) "MOSTRAR CONTROLES (${if (offline) "OFFLINE" else "ONLINE"})" else "OCULTAR CONTROLES",
                        color = Blue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = !collapsed,
                enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(Glass, 14.dp).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("ONLINE (INTERNET)" to false, "OFFLINE (Sin Datos)" to true).forEach { (t, off) ->
                        Text(t, Modifier.weight(1f).rounded(10.dp)
                            .background(if (offline == off) Color.White else Color.Transparent)
                            .clickable { offline = off; if (!off) DnsBackend.stop(); prefs.edit().putString(DNS_MODE, if (off) "offline" else "online").apply() }
                            .padding(vertical = 10.dp),
                            color = if (offline == off) Blue else Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (offline) DnsPretty(baseUrl, collapsed) { offline = false; DnsBackend.stop(); prefs.edit().putString(DNS_MODE, "online").apply() }
            else ChatScreen(collapsed)
        }
    }
}

// ---------------------------------------------------------------------
//  OFFLINE con la cara del chat bonito: global, privados y grupos, todo por DNS
// ---------------------------------------------------------------------
@Composable
fun DnsPretty(base: String, collapsed: Boolean = false, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(DNS_PREFS, Context.MODE_PRIVATE) }
    var tick by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<Result<JSONObject>?>(null) }
    var ready by remember { mutableStateOf(false) }
    var nickOk by remember { mutableStateOf(prefs.getBoolean("dns_nick_ok", false)) }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { runCatching { JSONObject(dnsCall(base, "GET", "/api/chat/info")) } }
        if (r.getOrNull()?.optBoolean("exists") == true) {
            withContext(Dispatchers.IO) { runCatching { DnsBackend.start(AndroidDnsIo(base, ctx)) } }
            ready = DnsBackend.active
        }
        state = r
    }
    val s = state
    when {
        s == null -> LoadingBlock("Conectando Espere por favor…")
        s.isFailure -> Column {
            StubScreen("Error: ${s.exceptionOrNull()?.message}")
            Spacer(Modifier.height(8.dp))
            Btn("REINTENTAR", Blue) { state = null; tick++ }
        }
        s.getOrThrow().optBoolean("exists") != true -> DnsOnboarding(base) { state = null; tick++ }
        !ready -> Column {
            StubScreen("No se pudo arrancar el chat DNS. Revisa que tu config esté activa.")
            Spacer(Modifier.height(8.dp))
            Btn("REINTENTAR", Blue) { state = null; tick++ }
        }
        !nickOk -> DnsNickGate(DnsBackend.nick()) { n ->
            DnsBackend.setNick(n)
            prefs.edit().putBoolean("dns_nick_ok", true).apply()
            nickOk = true
        }
        else -> Column(Modifier.fillMaxSize()) {
            // Encogido: se esconde la tarjeta "Tu direccion DNS" (y adentro, GLOBAL / PRIVADOS / GRUPOS).
            androidx.compose.animation.AnimatedVisibility(visible = !collapsed,
                enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
                DnsAddrBar(ctx)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { ChatHomeDns(collapsed, onExit) }
        }
    }
}

/** Tu direccion de 20 letras (para que te escriban) y el aviso de que va lento. Se oculta con el teclado. */
@Composable
private fun DnsAddrBar(ctx: Context) {
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (imeOpen) return
    val addr = DnsBackend.myAddr
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(Glass, 12.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tu dirección ID (compártela para que te escriban)", color = Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(if (addr.isEmpty()) "…" else addr, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            if (addr.isNotEmpty()) Text("COPIAR",
                Modifier.rounded(10.dp).background(Color(0x33FFFFFF)).clickable { dnsCopy(ctx, "Dirección", addr) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text("Modo Offline(Sin Datos): los mensajes pueden tardar minutos en llegar dependiendo de su conexión. Para escribirle a alguien, búscalo pegando su dirección.",
            Modifier.padding(top = 4.dp), color = Soft, fontSize = 11.sp)
    }
}

/** Primera vez en modo DNS: elegir el apodo con el que te ven en el global y en los grupos. */
@Composable
private fun DnsNickGate(initial: String, done: (String) -> Unit) {
    var n by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
        Text("Elige tu apodo", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text("Así te verán los demás en el chat global y en los grupos. No lleva contraseña: tu identidad es tu Identificación(ID).",
            Modifier.padding(top = 6.dp, bottom = 12.dp), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        ChatInput(n, { v -> n = v.filter { c -> c.isLetterOrDigit() || c == '_' }.take(16) }, "tu_apodo", Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Btn("ENTRAR", Red) { if (n.length >= 2) done(n) }
    }
}

// ---------------------------------------------------------------------
//  Entrada del modo offline
// ---------------------------------------------------------------------
@Composable
fun DnsChat(base: String) {
    var tick by remember { mutableStateOf(0) }
    var peer by remember { mutableStateOf<String?>(null) }
    var peerName by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<Result<JSONObject>?>(null) }
    LaunchedEffect(tick) {
        state = withContext(Dispatchers.IO) { runCatching { JSONObject(dnsCall(base, "GET", "/api/chat/info")) } }
    }
    BackHandler(enabled = peer != null) { peer = null }

    val s = state
    when {
        s == null -> StubScreen("Cargando...")
        s.isFailure -> Column {
            StubScreen("Error: ${s.exceptionOrNull()?.message}")
            Spacer(Modifier.height(8.dp))
            Btn("REINTENTAR", Blue) { tick++ }
        }
        !s.getOrThrow().optBoolean("exists") -> DnsOnboarding(base) { tick++ }
        peer != null -> DnsThread(base, peer!!, peerName) { peer = null }
        else -> DnsHome(base, s.getOrThrow(), { tick++ }) { a, n -> peer = a; peerName = n }
    }
}

@Composable
private fun DnsOnboarding(base: String, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().card().padding(16.dp)) {
            Text("Mensajería offline", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("Chatea con otras personas del server free sin internet. " +
                "Va cifrado de extremo a extremo. Es lento: un mensaje puede tardar varios minutos dependiendo de tu conexión.",
                Modifier.padding(top = 8.dp), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Text("Se crea una dirección de 20 letras que compartes para que te escriban. No lleva usuario ni contraseña: " +
                "tu identidad es un código de recuperación que se guarda en este teléfono.",
                Modifier.padding(top = 8.dp), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (busy) "CREANDO..." else "ACTIVAR", Red) {
                if (busy) return@Btn
                busy = true; msg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { dnsCall(base, "POST", "/api/chat/enable", JSONObject().put("action", "create")) }
                    }
                    busy = false
                    r.onSuccess { done() }.onFailure { msg = "Error: ${it.message}" }
                }
            }
            Btn("YA TENGO UN CÓDIGO", Blue) { showImport = !showImport }
        }
        if (showImport) {
            Spacer(Modifier.height(8.dp))
            Field(recovery, { recovery = it }, "xxxx-xxxx-xxxx-...", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Btn("RESTAURAR", Red) {
                val code = recovery.trim()
                if (code.isEmpty() || busy) return@Btn
                busy = true; msg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            dnsCall(base, "POST", "/api/chat/seed", JSONObject().put("action", "import").put("recovery", code))
                        }
                    }
                    busy = false
                    r.onSuccess { done() }.onFailure { msg = "Error: código inválido (${it.message})" }
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 10.dp), color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------
//  Inicio: dirección, servidores, contactos y conversaciones
// ---------------------------------------------------------------------
@Composable
private fun DnsHome(base: String, info: JSONObject, changed: () -> Unit, open: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val address = info.optString("address")
    val servers = dnsObjs(info.optJSONArray("servers") ?: JSONArray())
    var threads by remember { mutableStateOf<List<DnsThreadRow>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var newAddr by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf<String?>(null) }

    // Lista local (barata): se refresca sola cada 6 s; el servidor Go ya revisa el buzon por DNS en segundo plano.
    LaunchedEffect(refresh) {
        while (true) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    dnsObjs(JSONArray(dnsCall(base, "GET", "/api/chat/threads"))).map {
                        DnsThreadRow(it.optString("addr"), it.optString("name"), it.optString("lastText"),
                            it.optLong("lastTs"), it.optInt("unread"), it.optBoolean("pinned"))
                    }
                }
            }
            r.onSuccess { threads = it }
            delay(6000)
        }
    }

    fun act(label: String, f: () -> Unit) {
        if (busy) return
        busy = true; msg = label
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { f() } }
            busy = false
            msg = r.fold({ "" }, { "Error: ${it.message}" })
            changed(); refresh++
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        if (msg.isNotEmpty()) item {
            Text(msg, color = if (msg.startsWith("Error")) Red else Blue, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).card(Color.White, 12.dp).padding(10.dp))
        }
        item {
            Section("TU DIRECCIÓN")
            Column(Modifier.fillMaxWidth().card().padding(14.dp)) {
                Text(address, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("Compártela para que te escriban. Los mensajes de otros llegan solos cada pocos minutos.",
                    Modifier.padding(top = 4.dp), color = Muted, fontSize = 12.sp)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("COPIAR", Blue) { dnsCopy(ctx, "direccion", address); msg = "Dirección copiada" }
                    Btn(if (seed == null) "CÓDIGO DE RECUPERACIÓN" else "OCULTAR", Ink) {
                        if (seed != null) seed = null
                        else scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { JSONObject(dnsCall(base, "GET", "/api/chat/seed")).optString("recovery") }
                            }
                            r.onSuccess { seed = it }.onFailure { msg = "Error: ${it.message}" }
                        }
                    }
                }
                seed?.let { code ->
                    Text(code, Modifier.padding(top = 10.dp), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Guárdalo en un lugar seguro: con él recuperas tu dirección si pierdes el teléfono. No lo compartas.",
                        Modifier.padding(top = 4.dp), color = Red, fontSize = 12.sp)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("COPIAR CÓDIGO", Blue) { dnsCopy(ctx, "recuperacion", code); msg = "Código copiado" }
                        Btn("YA LO GUARDÉ", Red) {
                            scope.launch(Dispatchers.IO) {
                                runCatching { dnsCall(base, "POST", "/api/chat/seed", JSONObject().put("action", "backedup")) }
                            }
                            seed = null
                        }
                    }
                }
            }
        }

        item { Section("SERVIDORES DE CHAT (${servers.size})") }
        if (servers.isEmpty()) item {
            Text("Ninguna config tiene la clave pública del servidor (sk) o el servidor no tiene chat activado. " +
                "Agrega la config con su clave pública en Fuentes.",
                Modifier.fillMaxWidth().card(Color(0xFFFFF3CD), 12.dp).padding(12.dp),
                color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        items(servers, key = { it.optString("key") }) { sv ->
            val key = sv.optString("key"); val on = sv.optBoolean("enabled")
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card().padding(14.dp)) {
                Text(sv.optString("name").ifBlank { sv.optString("domain") }, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(sv.optString("domain") + if (on) "  ·  ACTIVO" else "  ·  APAGADO", color = if (on) Blue else Muted,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (on) "APAGAR" else "ACTIVAR", if (on) Ink else Red) {
                        act(if (on) "Apagando…" else "Registrando en el servidor por DNS… (puede tardar)") {
                            val o = JSONObject(dnsCall(base, "POST", "/api/chat/enable",
                                JSONObject().put("action", "server").put("server", key).put("on", !on), 90000))
                            if (!on && !o.optBoolean("available") && !o.optBoolean("checking"))
                                throw java.io.IOException(dnsErr(o.optString("reason")))
                        }
                    }
                    Btn("PROBAR", Blue) {
                        act("Probando el servidor por DNS… (hasta 1 minuto)") {
                            val o = JSONObject(dnsCall(base, "GET", "/api/chat/probe?retry=1&server=" + dnsEnc(key), readMs = 70000))
                            val r = o.optJSONObject("server")
                            if (r != null && !r.optBoolean("available"))
                                throw java.io.IOException(if (r.optBoolean("checking")) "Sigue comprobando, reintenta en un momento" else dnsErr(r.optString("reason")))
                        }
                    }
                }
            }
        }

        item {
            Section("NUEVO CONTACTO")
            Field(newAddr, { newAddr = it }, "dirección de 20 letras", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Field(newName, { newName = it }, "nombre (opcional)", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Btn("AÑADIR Y ABRIR", Red) {
                val a = newAddr.trim().lowercase().replace(" ", "")
                if (a.isEmpty()) { msg = "Error: pega la dirección del contacto"; return@Btn }
                val n = newName.trim().ifEmpty { a.take(8) }
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { dnsCall(base, "POST", "/api/chat/contacts", JSONObject().put("addr", a).put("name", n)) }
                    }
                    r.onSuccess { newAddr = ""; newName = ""; msg = ""; open(a, n) }
                        .onFailure { msg = if (it.message?.contains("invalid address") == true) "Error: dirección inválida" else "Error: ${it.message}" }
                }
            }
        }

        item { Section("CONVERSACIONES") }
        val list = threads
        if (list == null) item { StubScreen("Cargando...") }
        else if (list.isEmpty()) item {
            Text("Aún no hay conversaciones. Añade un contacto arriba.", color = Soft, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        }
        else items(list, key = { it.addr }) { t ->
            val title = t.name.ifBlank { t.addr.take(8) }
            Item(title, t.lastText.replace("\n", " ").take(60),
                if (t.unread > 0) "${t.unread} nuevos" else if (t.pinned) "FIJADO" else "", lead = title,
                onClick = { open(t.addr, t.name) })
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ---------------------------------------------------------------------
//  Conversacion
// ---------------------------------------------------------------------
@Composable
private fun DnsThread(base: String, peer: String, name: String, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msgs by remember(peer) { mutableStateOf<List<DnsMsg>>(emptyList()) }
    var accepted by remember(peer) { mutableStateOf(0L) }
    var delivered by remember(peer) { mutableStateOf(0L) }
    var pinned by remember(peer) { mutableStateOf(false) }
    var emojis by remember(peer) { mutableStateOf("") }
    var text by remember(peer) { mutableStateOf("") }
    var pending by remember(peer) { mutableStateOf<String?>(null) }
    var status by remember(peer) { mutableStateOf("") }
    var polling by remember(peer) { mutableStateOf(false) }
    var confirm by remember(peer) { mutableStateOf("") }
    val title = name.ifBlank { peer.take(8) }

    suspend fun reload() {
        val r = withContext(Dispatchers.IO) {
            runCatching { JSONObject(dnsCall(base, "GET", "/api/chat/messages?markRead=1&peer=" + dnsEnc(peer))) }
        }
        r.onSuccess { o ->
            val server = o.optString("server")
            val list = dnsObjs(o.optJSONArray("msgs") ?: JSONArray()).map {
                DnsMsg(it.optString("dir"), it.optLong("seq"), it.optString("text"), it.optLong("ts"))
            }
            if (list != msgs) msgs = list
            accepted = o.optJSONObject("accepted")?.optLong(server) ?: 0L
            delivered = o.optJSONObject("delivered")?.optLong(server) ?: 0L
            val em = o.optJSONArray("emojis")
            if (em != null) emojis = (0 until em.length()).joinToString("") { em.optString(it) }
        }
    }
    LaunchedEffect(peer) {
        // Lee el hilo guardado en el servidor local cada 4 s (barato); el buzon por DNS lo revisa el servidor Go solo.
        while (true) { reload(); delay(4000) }
    }
    LaunchedEffect(peer) {
        val r = withContext(Dispatchers.IO) { runCatching { dnsObjs(JSONArray(dnsCall(base, "GET", "/api/chat/threads"))) } }
        pinned = r.getOrNull()?.firstOrNull { it.optString("addr") == peer }?.optBoolean("pinned") ?: false
    }

    fun send() {
        val t = text.trim()
        if (t.isEmpty() || pending != null) return
        val bytes = t.toByteArray().size
        if (bytes > 500) { status = "Error: máximo 500 bytes por mensaje (llevas $bytes)"; return }
        pending = t; text = ""; status = "Enviando … puede tardar varios minutos"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { JSONObject(dnsCall(base, "POST", "/api/chat/send",
                    JSONObject().put("peer", peer).put("text", t), 16 * 60 * 1000)) }
            }
            pending = null
            r.onSuccess { o ->
                if (o.optBoolean("ok")) { status = ""; reload() }
                else { status = "Error: " + dnsErr(o.optString("error")); text = t }
            }.onFailure { status = "Error: ${it.message}"; text = t }
        }
    }

    fun checkMail() {
        if (polling) return
        polling = true; status = "Revisando el buzón puede tardar"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { JSONObject(dnsCall(base, "POST", "/api/chat/poll", JSONObject(), 11 * 60 * 1000)) } }
            polling = false
            status = r.fold({ val n = it.optInt("got"); if (n > 0) "Llegaron $n mensajes" else "No hay mensajes nuevos" }, { "Error: ${it.message}" })
            reload()
        }
    }

    fun threadAction(action: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { dnsCall(base, "POST", "/api/chat/thread", JSONObject().put("peer", peer).put("action", action)) }
            }
            when (action) {
                "delete" -> back()
                "pin" -> pinned = true
                "unpin" -> pinned = false
                "clear" -> { msgs = emptyList(); reload() }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", Modifier.clickable { back() }.padding(end = 12.dp), color = Blue, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    if (emojis.isNotEmpty()) Text("Código de seguridad: $emojis", color = Muted, fontSize = 12.sp)
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(if (polling) "REVISANDO..." else "REVISAR BUZÓN", Blue) { checkMail() }
                Btn(if (pinned) "QUITAR FIJO" else "FIJAR", Ink) { threadAction(if (pinned) "unpin" else "pin") }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(if (confirm == "clear") "¿SEGURO?" else "VACIAR", Ink) {
                    if (confirm == "clear") { confirm = ""; threadAction("clear") } else confirm = "clear"
                }
                Btn(if (confirm == "delete") "¿SEGURO? BORRAR" else "BORRAR", Red) {
                    if (confirm == "delete") { confirm = ""; threadAction("delete") } else confirm = "delete"
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
            pending?.let { p ->
                item(key = "pending") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Column(Modifier.widthIn(max = 290.dp).card(Mine, 16.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(p, color = Color.White, fontSize = 15.sp)
                            Text("enviando…", color = Soft, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            items(msgs.reversed()) { m ->
                val mine = m.dir == "out"
                val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(m.ts * 1000))
                val tick = if (!mine) "" else if (m.seq <= delivered) "✓✓" else if (m.seq <= accepted) "✓" else ""
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 290.dp).card(if (mine) Mine else Color.White, 16.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(m.text, color = if (mine) Color.White else Ink, fontSize = 15.sp)
                        Text(time + if (tick.isNotEmpty()) "  $tick" else "", color = if (mine) Soft else Muted, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp).align(Alignment.End))
                    }
                }
            }
        }

        if (status.isNotEmpty()) Text(status, Modifier.padding(vertical = 4.dp), color = if (status.startsWith("Error")) Red else Soft,
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(text, { text = it }, "Escribe (máx. 500 bytes)…", Modifier.weight(1f))
            Btn(if (pending != null) "…" else "ENVIAR", Red) { send() }
        }
    }
}
