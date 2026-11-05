package com.cuvay.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Panel en tu VPS detras de Caddy (HTTPS). Cambia el dominio por el tuyo.
internal const val PANEL = "https://panel.titomx.work.gd"
internal const val RPREFS = "cuvay_remote"

internal fun deviceId(ctx: Context): String {
    val p = ctx.getSharedPreferences(RPREFS, Context.MODE_PRIVATE)
    return p.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
}

internal fun fetchRemote(ctx: Context, track: Boolean = true): JSONObject {
    val u = "$PANEL/api/app/config?v=${com.cuvay.android.BuildConfig.VERSION_CODE}" +
        (if (track) "&id=${deviceId(ctx)}&model=" + URLEncoder.encode(Build.MODEL, "UTF-8") else "")
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 6000; c.readTimeout = 8000
    return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
}

private fun openUrl(ctx: Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun styleColor(s: String) = when (s) {
    "promo" -> Color(0xFFCF142B); "warn" -> Color(0xFFF2A30F); "ok" -> Color(0xFF19B67A); else -> Color(0xFF2F5BFF)
}
private fun styleEmoji(s: String) = when (s) { "promo" -> "🎁"; "warn" -> "⚠️"; "ok" -> "✅"; else -> "ℹ️" }

/**
 * FEEDS DESDE EL PANEL. Si la respuesta de /api/app/config trae "feeds": [{id, uri, activate}],
 * la app crea esas configs sola (y la que lleve activate:true queda activa). Si un id deja de venir,
 * se borra la config que el panel puso. Si la respuesta no trae "feeds", no toca nada.
 * Devuelve false si algo falló (se reintenta).
 */
internal fun applyFeeds(ctx: Context, api: Api, s: JSONObject): Boolean {
    val arr = s.optJSONArray("feeds") ?: return true
    val prefs = ctx.getSharedPreferences(RPREFS, Context.MODE_PRIVATE)
    val applied = runCatching { JSONObject(prefs.getString("feeds_applied", "{}") ?: "{}") }.getOrDefault(JSONObject())
    val wanted = mutableSetOf<String>()
    var allOk = true
    for (i in 0 until arr.length()) {
        val f = arr.optJSONObject(i) ?: continue
        val uri = f.optString("uri"); val a = parseUri(uri) ?: continue
        val id = f.optString("id").ifBlank { "u" + uri.hashCode() }
        wanted += id
        val sig = a.domain + "|" + a.key
        if (applied.optString(id) == sig) continue                     // ya aplicado
        if (runCatching { addConfig(api, a, f.optBoolean("activate", true)) }.isSuccess) {
            if (applied.has(id)) applied.optString(id).split("|", limit = 2).takeIf { it.size == 2 && it[0] + "|" + it[1] != sig }
                ?.let { runCatching { removeConfig(api, it[0], it[1]) } }   // el panel cambió la clave de ese feed
            applied.put(id, sig)
        } else allOk = false
    }
    for (id in applied.keys().asSequence().toList()) if (id !in wanted) {   // quitado desde el panel
        val p = applied.optString(id).split("|", limit = 2)
        if (p.size == 2 && runCatching { removeConfig(api, p[0], p[1]) }.isSuccess) applied.remove(id) else allOk = false
    }
    prefs.edit().putString("feeds_applied", applied.toString()).apply()
    return allOk
}

/** El servidor interno de la app puede tardar en arrancar: reintenta unas veces. */
private suspend fun applyFeedsRetry(ctx: Context, api: Api, s: JSONObject) {
    repeat(6) {
        val ok = withContext(Dispatchers.IO) { runCatching { applyFeeds(ctx, api, s) }.getOrDefault(false) }
        if (ok) return
        delay(15_000)
    }
}

/** Tarjeta de aviso: franja de color, emoji, titulo, texto y botones. */
@Composable
private fun NoticeDialog(emoji: String, color: Color, title: String, body: String, dismissible: Boolean,
                         primary: String?, onPrimary: () -> Unit, secondary: String?, onSecondary: () -> Unit, progress: Float? = null) {
    Dialog(onDismissRequest = { if (dismissible) onSecondary() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible)) {
        Column(Modifier.fillMaxWidth().card(Color.White, 24.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(color))
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text(emoji, fontSize = 34.sp)
                Text(title, Modifier.padding(top = 6.dp), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black)
                if (body.isNotBlank()) Text(body, Modifier.padding(top = 6.dp), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                if (progress != null) Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(8.dp).rounded(4.dp).background(Chip)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(color))
                }
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (primary != null) Btn(primary, color) { onPrimary() }
                    if (secondary != null) Btn(secondary, Ink) { onSecondary() }
                }
            }
        }
    }
}

/**
 * Capa que consulta el panel al abrir (y cada 5 min). Pon RemoteOverlay(api) dentro de FlagBackground, al final.
 * Si el panel no responde, usa la ultima respuesta guardada (asi el force update no se salta sin internet).
 */
@Composable
fun RemoteOverlay(api: Api) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(RPREFS, Context.MODE_PRIVATE) }
    var st by remember { mutableStateOf(runCatching { JSONObject(prefs.getString("cache", "") ?: "") }.getOrNull()) }
    var seen by remember { mutableStateOf((prefs.getStringSet("seen", emptySet()) ?: emptySet()).toSet()) }
    var closed by remember { mutableStateOf(setOf<Int>()) }
    var skipUpdate by remember { mutableStateOf(false) }

    // Notificaciones del sistema: pide permiso (Android 13+) y programa la revision cada 15 min.
    val askPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        scheduleNotifyWorker(ctx)
    }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { fetchRemote(ctx) } }.onSuccess {
                st = it; prefs.edit().putString("cache", it.toString()).apply()
                applyFeedsRetry(ctx, api, it)
            }
            delay(5 * 60 * 1000L)
        }
    }
    val s = st ?: return
    val msgs = s.optJSONArray("messages")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    fun dismiss(m: JSONObject) {
        val id = m.optInt("id"); closed = closed + id
        if (m.optString("show") != "always") {
            seen = seen + id.toString(); prefs.edit().putStringSet("seen", seen).apply()
        }
    }

    // 1) Mantenimiento: cubre toda la app.
    val mt = s.optJSONObject("maintenance")
    if (mt != null && mt.optBoolean("on")) {
        Box(Modifier.fillMaxSize().background(Navy0).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🛠️", fontSize = 48.sp)
                Text(mt.optString("title"), Modifier.padding(top = 12.dp), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(mt.optString("body"), Modifier.padding(top = 8.dp), color = Soft, fontSize = 15.sp)
            }
        }
        return
    }

    // 2) Actualizacion obligatoria (no se puede cerrar) u opcional.
    val notes = s.optString("notes"); val apk = s.optString("apk_url")
    // Actualizacion dentro de la app: baja el APK y abre el instalador.
    val scope = rememberCoroutineScope()
    var dl by remember { mutableStateOf(-1f) }       // -1 = sin descargar
    var dlErr by remember { mutableStateOf("") }
    fun startUpdate() {
        if (dl >= 0f) return
        dlErr = ""; dl = 0f
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { downloadApk(ctx, apk) { dl = it } } }
            dl = -1f
            r.onSuccess { f -> runCatching { installApk(ctx, f) }.onFailure { dlErr = "No se pudo abrir el instalador." } }
                .onFailure { dlErr = "No se pudo descargar (${it.message}). Revisa tu conexión e intenta de nuevo." }
        }
    }
    val upBody = { base: String ->
        when {
            dl >= 0f -> base + "\n\nDescargando… ${(dl * 100).toInt()}%"
            dlErr.isNotEmpty() -> base + "\n\n" + dlErr
            else -> base
        }
    }
    val upLabel = if (dl >= 0f) "DESCARGANDO…" else if (dlErr.isNotEmpty()) "REINTENTAR" else "ACTUALIZAR"
    if (s.optBoolean("force")) {
        NoticeDialog("🚀", Red, "Actualización necesaria", upBody(notes.ifBlank { "Instala la nueva versión para seguir usando la app." }),
            false, upLabel, { startUpdate() }, null, {}, if (dl >= 0f) dl else null)
        return
    }
    if (s.optBoolean("update") && !skipUpdate) {
        NoticeDialog("✨", Color(0xFF2F5BFF), "Hay una versión nueva", upBody(notes.ifBlank { "Ya puedes actualizar la app." }),
            dl < 0f, upLabel, { startUpdate() }, if (dl < 0f) "DESPUÉS" else null, { skipUpdate = true }, if (dl >= 0f) dl else null)
    }

    // 3) Popup (uno a la vez) y banners.
    fun pending(m: JSONObject) = m.optInt("id") !in closed && !(m.optString("show") != "always" && m.optInt("id").toString() in seen)
    msgs.firstOrNull { it.optString("kind") == "popup" && pending(it) }?.let { m ->
        val color = styleColor(m.optString("style")); val dis = m.optBoolean("dismissible", true)
        val url = m.optString("url"); val label = m.optString("btn")
        NoticeDialog(styleEmoji(m.optString("style")), color, m.optString("title"), m.optString("body"), dis,
            if (label.isNotBlank() && url.isNotBlank()) label else null, { openUrl(ctx, url); dismiss(m) },
            if (dis) "CERRAR" else null, { dismiss(m) })
    }
    msgs.filter { it.optString("kind") == "banner" && it.optInt("id") !in closed }.take(1).forEach { m ->
        val dis = m.optBoolean("dismissible", true); val url = m.optString("url")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Row(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
                .card(styleColor(m.optString("style")), 14.dp)
                .clickable { if (url.isNotBlank()) openUrl(ctx, url) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(styleEmoji(m.optString("style")) + "  " + listOf(m.optString("title"), m.optString("body")).filter { it.isNotBlank() }.joinToString(" "),
                    Modifier.weight(1f), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 3)
                if (dis) Text("✕", Modifier.clickable { closed = closed + m.optInt("id") }.padding(start = 12.dp),
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
