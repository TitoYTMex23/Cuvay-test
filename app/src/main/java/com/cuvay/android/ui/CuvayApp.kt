package com.cuvay.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { Feed("Feed"), Mirror("Fuentes"), Chat("Chat"), Telegram("Telegram"), Resolver("Res."), Settings("Ajus."), Red("Red") }

@Composable
fun CuvayApp(baseUrl: String, bridge: com.cuvay.android.AndroidBridge? = null) {
    val api = remember { Api(baseUrl) }
    AlertsHost(api, baseUrl)
    var tab by remember { mutableStateOf(Tab.Feed) }
    var openChannel by remember { mutableStateOf<Api.Channel?>(null) }
    val feedListState = rememberLazyListState()
    // Navegación desde notificaciones (abre Canales o salta a la pestaña Chat)
    LaunchedEffect(Alerts.tapTarget) {
        val t = Alerts.tapTarget ?: return@LaunchedEffect
        when {
            t == "global" || t.startsWith("dm:") -> { tab = Tab.Chat; openChannel = null }
            t.startsWith("feed:") -> {
                val chNum = t.removePrefix("feed:").toIntOrNull()
                if (chNum != null) {
                    withContext(Dispatchers.IO) { runCatching { api.channels() } }.getOrNull()?.find { it.num == chNum }?.let { ch ->
                        tab = Tab.Feed; openChannel = ch
                    }
                }
                Alerts.tapTarget = null
            }
        }
    }
    // Con el teclado abierto en el chat se oculta el encabezado para que quepa la conversacion.
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val compact = imeOpen && tab == Tab.Chat

    androidx.activity.compose.BackHandler(enabled = openChannel != null) { openChannel = null }

    // Enlace de fabrica: en la primera apertura (instalacion nueva) se conecta sola si FACTORY_URI tiene un enlace.
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ensureSpeedDefaults(appCtx, api) }; runCatching { ensureDefaultResolvers(appCtx, api) } } }
    LaunchedEffect(Unit) {
        DlConfig.load(appCtx)
        DlConfig.api = api
        // La cuenta de la universidad y la base de GitHub no se guardan en el telefono: se piden al servidor en cada arranque.
        withContext(Dispatchers.IO) {
            runCatching { DlConfig.refreshEdu(appCtx) }
            runCatching { GhRelay.refresh(api) }   // dominio y clave para NUBE directa
            DlConfig.applyProxy(api)   // el nucleo Go arranca sin proxy: se lo vuelve a poner (o se lo quita)
        }
    }
    LaunchedEffect(Unit) {
        val prefs = appCtx.getSharedPreferences("cuvay_ui", android.content.Context.MODE_PRIVATE)
        val fa = parseUri(FACTORY_URI)
        if (fa == null || prefs.getBoolean("factory_done", false)) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            repeat(12) {   // el servidor interno puede tardar unos segundos en arrancar
                val ok = runCatching { if (!hasProfile(api)) addConfig(api, fa) }.isSuccess
                if (ok) { prefs.edit().putBoolean("factory_done", true).apply(); return@withContext }
                kotlinx.coroutines.delay(2500)
            }
        }
    }

    FlagBackground {
        CuvayScene3D()   // fondo 3D con profundidad (detras de todo)
        // imePadding SOLO aqui (una vez): las pantallas de adentro no lo repiten.
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 12.dp)) {
            if (!compact) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (openChannel != null) {
                        Text("‹", Modifier.clickable { openChannel = null }.padding(end = 12.dp, start = 2.dp),
                            color = Color.White, fontSize = 32.sp, fontWeight = Heavy)
                        ChannelAvatar(openChannel!!.name, openChannel!!.title, 34.dp)
                        Spacer(Modifier.width(10.dp))
                    } else {
                        FlagMark(Modifier.size(40.dp, 26.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(openChannel?.title ?: if (tab == Tab.Feed) "Cuvay" else tab.label,
                        color = Color.White, fontSize = 24.sp, fontWeight = Heavy, fontFamily = Display, maxLines = 1)
                }
                Seg(Tab.values().map { it.label }, tab.ordinal) { tab = Tab.values()[it]; openChannel = null }
                Spacer(Modifier.height(10.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    openChannel != null -> MessagesScreen(api, openChannel!!, bridge)
                    tab == Tab.Feed -> FeedScreen(api, { tab = Tab.Mirror }, feedListState) { openChannel = it }
                    tab == Tab.Mirror -> MirrorScreen(api)
                    tab == Tab.Resolver -> ResolverScreen(api)
                    tab == Tab.Chat -> ChatTab(baseUrl)
                    tab == Tab.Telegram -> TelegramTab()
                    tab == Tab.Red -> RedScreen()
                    else -> SettingsScreen(api)
                }
            }
        }
        RemoteOverlay(api)
        CuvayAssistant(tab, openChannel != null) { tab = Tab.Chat; openChannel = null }
    }
}

@Composable
fun <T> Load(key: Any? = Unit, fn: () -> T, content: @Composable (T) -> Unit) {
    var state by remember(key) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key) { state = withContext(Dispatchers.IO) { runCatching(fn) } }
    when (val s = state) {
        null -> LoadingBlock()
        else -> s.fold({ content(it) }, { StubScreen("Error: ${it.message}") })
    }
}

@Composable fun StubScreen(t: String) =
    Text(t, Modifier.fillMaxWidth().card().padding(16.dp), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)

@Composable
fun Item(title: String, sub: String = "", right: String = "", lead: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).card()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) { Avatar(lead, 42.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (right.isNotEmpty()) Text(right, color = Red, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

private fun hasProfile(api: Api): Boolean = runCatching {
    val p = api.profiles()
    (p.optJSONArray("profiles") ?: p.optJSONArray("Profiles"))?.length() ?: 0
}.getOrDefault(0) > 0

@Composable
fun FeedScreen(api: Api, onConfig: () -> Unit, listState: LazyListState, open: (Api.Channel) -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var tries by remember { mutableStateOf(0) }
    Load(tick, { api.channels() to hasProfile(api) }) { (list, configured) ->
        if (list.isEmpty()) {
            if (configured) LaunchedEffect(tick) {
                if (tries < 8) {
                    tries++
                    withContext(Dispatchers.IO) { runCatching { api.refresh() } }
                    kotlinx.coroutines.delay(4000)
                    tick++
                }
            }
            Column {
                // Bienvenida: tu canal siempre visible, aunque todavia no haya conexion.
                Item("freecubas3", "@freecubas3", if (configured) "CONECTANDO" else "SIN CONFIG", lead = "freecubas3")
                Spacer(Modifier.height(4.dp))
                if (!configured) {
                    StubScreen("Falta conectar con tu servidor. Pon el dominio y la clave en Fuentes.")
                    Spacer(Modifier.height(8.dp))
                    Btn("CONFIGURAR AHORA", Red) { onConfig() }
                } else {
                    StubScreen(if (tries < 8) "Pidiendo canales… (reintenta solo)"
                               else "No llegan canales. Revisa que haya resolvers activos y que la config activa sea la correcta.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("REINTENTAR", Blue) { tries = 0; tick++ }
                        Btn("CONFIG", Ink) { onConfig() }
                    }
                }
            }
                } else LazyColumn(state = listState) { items(list, key = { it.num }) { ChannelRow(it) { open(it) } } }
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%d KB".format(b / 1024)
    else -> "$b B"
}

/** Mensajes de un canal con boton ACTUALIZAR y actualizacion automatica cada N segundos (la eliges tu). */
@Composable
fun MessagesScreen(api: Api, ch: Api.Channel, bridge: com.cuvay.android.AndroidBridge?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("cuvay_ui", android.content.Context.MODE_PRIVATE) }
    var msgs by remember(ch.num) { mutableStateOf<List<Api.Msg>?>(null) }
    var err by remember(ch.num) { mutableStateOf("") }
    var last by remember(ch.num) { mutableStateOf("") }
    var busy by remember(ch.num) { mutableStateOf(false) }
    var auto by remember { mutableStateOf(prefs.getBoolean("auto", false)) }
    var secsText by remember { mutableStateOf(prefs.getInt("secs", 5).toString()) }
    var photosAuto by remember { mutableStateOf(prefs.getBoolean("photos_auto", true)) }
    var photoVia by remember { mutableStateOf(DlConfig.photoRoute) }
    var photoDlg by remember { mutableStateOf(false) }
    if (photoDlg) PhotoRouteDialog(photoVia,
        onPick = { r -> photoVia = r; DlConfig.setPhotoRoute(ctx, r); photoDlg = false },
        onCancel = { photoDlg = false })
    var collapsed by remember { mutableStateOf(prefs.getBoolean("panel_collapsed", false)) }
    val secs = (secsText.toIntOrNull() ?: 5).coerceIn(1, 3600)
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    // Recarga la lista SIN ponerla en "Cargando...": solo reemplaza si algo cambio.
    suspend fun reload() {
        val r = withContext(Dispatchers.IO) { runCatching { api.messages(ch.num) } }
        r.onSuccess { if (it != msgs) msgs = it; err = ""; last = timeFmt.format(java.util.Date()) }
            .onFailure { err = it.message ?: "Error" }
    }
    // Pide al cliente local que consulte el canal por DNS y luego recarga la lista.
    suspend fun refreshNow() {
        if (busy) return
        busy = true
        try {
            withContext(Dispatchers.IO) { runCatching { api.refreshChannel(ch.num) } }
            kotlinx.coroutines.delay(1500)
            reload()
        } finally { busy = false }
    }
    LaunchedEffect(ch.num) { reload(); refreshNow() }
    LaunchedEffect(auto, secs, ch.num) {
        while (auto) { kotlinx.coroutines.delay(secs * 1000L); refreshNow() }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).card().padding(horizontal = 12.dp)
            .padding(top = 2.dp, bottom = if (collapsed) 2.dp else 12.dp)) {
            // Botoncito para encoger / abrir los controles: pastilla chica con una flechita que gira.
            val turn by animateFloatAsState(if (collapsed) 180f else 0f, label = "chev")
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.rounded(20.dp).background(Chip)
                    .clickable { collapsed = !collapsed; prefs.edit().putBoolean("panel_collapsed", collapsed).apply() }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(11.dp, 7.dp).rotate(turn)) {
                        val w = size.width; val h = size.height; val sw = 2.2.dp.toPx()
                        drawLine(color = Blue, start = Offset(0f, h), end = Offset(w / 2f, 0f), strokeWidth = sw, cap = StrokeCap.Round)
                        drawLine(color = Blue, start = Offset(w / 2f, 0f), end = Offset(w, h), strokeWidth = sw, cap = StrokeCap.Round)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(if (collapsed) "MOSTRAR CONTROLES" else "OCULTAR CONTROLES", color = Blue, fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = !collapsed,
                enter = androidx.compose.animation.expandVertically(), exit = androidx.compose.animation.shrinkVertically()) {
            Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn(if (busy) "ACTUALIZANDO..." else "ACTUALIZAR", Blue) { scope.launch { refreshNow() } }
                Btn(if (auto) "AUTO: SI" else "AUTO: NO", if (auto) Red else Ink) {
                    auto = !auto; prefs.edit().putBoolean("auto", auto).apply()
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                Btn(if (photosAuto) "FOTOS: AUTO" else "FOTOS: AL TOCAR", if (photosAuto) Blue else Ink) {
                    photosAuto = !photosAuto; prefs.edit().putBoolean("photos_auto", photosAuto).apply()
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                Btn("📷 FOTOS POR: " + photoVia.tag + " ▾", Blue) { photoDlg = true }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cada", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Field(secsText, { v ->
                    val d = v.filter { c -> c.isDigit() }.take(4)
                    secsText = d
                    d.toIntOrNull()?.let { n -> prefs.edit().putInt("secs", n.coerceIn(1, 3600)).apply() }
                }, "5", Modifier.width(80.dp), numeric = true)
                Text("segundos", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (busy) BarLoader(null, Modifier.padding(top = 8.dp), track = Chip)
            val status = when {
                err.isNotEmpty() -> "Error: $err"
                last.isEmpty() -> ""
                else -> "Ultima actualizacion: $last" + if (auto) " - automatico cada ${secs}s" else ""
            }
            if (status.isNotEmpty()) Text(status, color = if (err.isNotEmpty()) Red else Muted, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
            }
            // Con el panel encogido, igual se ve la barrita mientras actualiza.
            if (collapsed && busy) BarLoader(null, Modifier.padding(bottom = 6.dp), track = Chip)
        }
        val list = msgs
        if (list == null) { if (err.isNotEmpty()) StubScreen("Error: $err") else LoadingBlock("Cargando mensajes…") }
        else LazyColumn(Modifier.weight(1f), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 6.dp)) {
            items(list.distinctBy { it.id }.reversed(), key = { it.id }) { m ->
                PostCard(ch, m, bridge, photosAuto)
            }
        }
    }
}

// MirrorScreen ahora vive en Mirror.kt (pestana "Fuentes")


@Composable
fun MediaCard(f: Api.Media, label: String, color: Color, id: String, bridge: com.cuvay.android.AndroidBridge?) {
    var st by remember { mutableStateOf(org.json.JSONObject("{\"state\":\"idle\"}")) }
    val state = st.optString("state", "idle")
    LaunchedEffect(id, state) {
        if (bridge == null) return@LaunchedEffect
        while (true) {
            val j = runCatching { org.json.JSONObject(bridge.mediaDownloadStatus(id)) }.getOrNull()
            if (j != null) st = j
            val s = j?.optString("state") ?: "idle"
            if (s == "done" || s == "completed" || s == "error" || s == "idle" || s == "canceled" || s == "paused") break
            kotlinx.coroutines.delay(500)
        }
    }
    val rec = st.optLong("received", 0); val tot = st.optLong("total", f.size).let { if (it > 0) it else f.size }
    val frac = if (tot > 0) (rec.toFloat() / tot).coerceIn(0f, 1f) else 0f
    val done = state == "done" || state == "completed"
    val running = state == "running" || state == "verifying"
    var showPlayer by remember(id) { mutableStateOf(false) }
    val playable = done && (f.kind == "AUDIO" || f.kind == "VIDEO") && bridge != null
    val ask = rememberDlAsk()
    DlAskHost(ask)

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(Chip, 12.dp).padding(10.dp)) {
        Text(label + "  " + fmtSize(f.size), color = color, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        if (f.name.isNotEmpty()) Text(f.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        if (f.source != null && bridge != null) {
            if (running || state == "paused") {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).rounded(3.dp).background(Color(0xFFCBD3E6))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
                }
                Text("${(frac * 100).toInt()}%  ${fmtSize(rec)} / ${fmtSize(tot)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (state == "error") Text(st.optString("detail", "Error"), color = Red, fontSize = 12.sp)
            if (state == "error") DlFallbackBar(f, id, bridge) { st = org.json.JSONObject("{\"state\":\"running\"}") }
            if (playable && showPlayer) bridge!!.downloadedUri(id)?.let { InlinePlayer(it, f.kind == "VIDEO") }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fun btn(t: String, bg: Color, f2: () -> Unit) = t to (bg to f2)
                val actions = when {
                    done -> listOfNotNull(
                        if (playable) btn(if (showPlayer) "CERRAR" else "REPRODUCIR", Red) { showPlayer = !showPlayer } else null,
                        btn("ABRIR", Blue) { bridge.openDownloadedMedia(id) }, btn("COMPARTIR", Ink) { bridge.shareDownloadedMedia(id) })
                    running -> listOf(btn("CANCELAR", Red) { bridge.cancelMediaDownload(id); st = org.json.JSONObject("{\"state\":\"canceled\"}") })
                    else -> listOf(btn(if (state == "paused") "REANUDAR" else "DESCARGAR", Blue) {
                        ask.request(f, resume = state == "paused") {
                            bridge.startMediaDownload(id, f.url(), f.name.ifEmpty { "$id.bin" }, f.size, f.crc, f.sha)
                            st = org.json.JSONObject("{\"state\":\"running\"}")
                        }
                    })
                }
                actions.forEach { (t, p) ->
                    Text(t, Modifier.rounded(10.dp).background(p.first).clickable { p.second() }.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}
