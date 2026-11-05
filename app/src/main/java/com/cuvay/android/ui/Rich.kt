package com.cuvay.android.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuvay.android.AndroidBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun isDone(s: String) = s == "done" || s == "completed"

/** Estado de descarga de un id del motor nativo, refrescado cada 500 ms mientras avanza. */
@Composable
private fun dlState(id: String, bridge: AndroidBridge?): MutableState<JSONObject> {
    val st = remember(id) { mutableStateOf(JSONObject("{\"state\":\"idle\"}")) }
    val state = st.value.optString("state", "idle")
    LaunchedEffect(id, state) {
        if (bridge == null) return@LaunchedEffect
        while (true) {
            val j = runCatching { JSONObject(bridge.mediaDownloadStatus(id)) }.getOrNull()
            if (j != null) st.value = j
            val s = j?.optString("state") ?: "idle"
            if (isDone(s) || s == "error" || s == "idle" || s == "canceled" || s == "paused") break
            delay(500)
        }
    }
    return st
}

private fun start(bridge: AndroidBridge, id: String, f: Api.Media) =
    bridge.startMediaDownload(id, f.url(), f.name.ifEmpty { "$id.bin" }, f.size, f.crc, f.sha)

/**
 * Tarjeta como la del WebView: miniatura grande (se baja sola) con insignia VIDEO/AUDIO,
 * duracion, y debajo nombre/tamano/botones. Tocar la miniatura descarga y luego reproduce.
 */
@Composable
fun RichMediaCard(thumb: Api.Media?, file: Api.Media, ch: Int, msgId: Long, bridge: AndroidBridge?) {
    val ctx = LocalContext.current
    val tid = if (thumb != null) "m${ch}_${msgId}_${thumb.crc}" else ""
    val fid = "m${ch}_${msgId}_${file.crc}"
    val video = file.kind == "VIDEO"
    val tSt = dlState(tid, if (thumb != null) bridge else null)
    val fSt = dlState(fid, bridge)
    val tDone = isDone(tSt.value.optString("state"))
    val fState = fSt.value.optString("state", "idle")
    val fDone = isDone(fState)
    val running = fState == "running" || fState == "verifying"
    var showPlayer by remember(fid) { mutableStateOf(false) }
    var wantPlay by remember(fid) { mutableStateOf(false) }
    var bmp by remember(tid) { mutableStateOf<ImageBitmap?>(null) }
    var durMs by remember(fid) { mutableStateOf(0L) }

    // La miniatura se baja sola (es chica), como en la version web.
    LaunchedEffect(tid) {
        if (bridge == null || thumb == null || thumb.source == null) return@LaunchedEffect
        val j = runCatching { JSONObject(bridge.mediaDownloadStatus(tid)) }.getOrNull()
        if ((j?.optString("state") ?: "idle") == "idle") { start(bridge, tid, thumb); tSt.value = JSONObject("{\"state\":\"running\"}") }
    }
    LaunchedEffect(tid, tDone) {
        if (!tDone || bridge == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val u = bridge.downloadedUri(tid) ?: return@runCatching null
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    // Sin miniatura publicada: se saca del propio archivo cuando ya esta descargado.
    LaunchedEffect(fid, fDone) {
        if (thumb != null || !fDone || bridge == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val u = bridge.downloadedUri(fid) ?: return@runCatching null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, u)
                    if (video) r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
                    else r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                } finally { runCatching { r.release() } }
            }.getOrNull()
        }
    }
    // Duracion real del archivo, leida cuando ya esta descargado.
    LaunchedEffect(fid, fDone) {
        if (!fDone || bridge == null) return@LaunchedEffect
        durMs = withContext(Dispatchers.IO) {
            runCatching {
                val u = bridge.downloadedUri(fid) ?: return@runCatching 0L
                val r = MediaMetadataRetriever()
                try { r.setDataSource(ctx, u); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                finally { runCatching { r.release() } }
            }.getOrDefault(0L)
        }
    }
    LaunchedEffect(fDone) { if (fDone && wantPlay) showPlayer = true }
    val ask = rememberDlAsk()
    DlAskHost(ask)

    fun tap() {
        if (bridge == null) return
        when {
            fDone -> showPlayer = true
            running -> {}
            else -> ask.request(file, resume = fState == "paused") {
                wantPlay = true; start(bridge, fid, file); fSt.value = JSONObject("{\"state\":\"running\"}")
            }
        }
    }

    val rec = fSt.value.optLong("received", 0)
    val tot = fSt.value.optLong("total", file.size).let { if (it > 0) it else file.size }
    val frac = if (tot > 0) (rec.toFloat() / tot).coerceIn(0f, 1f) else 0f
    val badge = if (video) "VIDEO" else "AUDIO"

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).rounded(14.dp)) {
        if (showPlayer && fDone && bridge != null) {
            val uri = remember(fid) { bridge.downloadedUri(fid) }
            if (uri != null) InlinePlayer(uri, video, bmp)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink).clickable { tap() }) {
                val b = bmp
                if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(badge, Modifier.align(Alignment.Center), color = Color(0xFF55607A), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(badge, Modifier.align(Alignment.TopStart).background(if (video) Blue else Red).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (durMs > 0) fmtDur(durMs) else fmtSize(file.size), Modifier.align(Alignment.BottomEnd)
                    .background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                // Boton central segun el estado.
                val (lbl, bg) = when {
                    fDone -> "REPRODUCIR" to Red
                    running -> "${(frac * 100).toInt()}%" to Blue
                    fState == "paused" -> "REANUDAR" to Blue
                    file.source == null -> "" to Ink
                    else -> "DESCARGAR" to Blue
                }
                if (lbl.isNotEmpty()) Text(lbl, Modifier.align(Alignment.Center).background(bg).padding(horizontal = 18.dp, vertical = 12.dp),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                if (running || fState == "paused") Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFFEFF2FA)).padding(10.dp)) {
            if (file.name.isNotEmpty()) Text(file.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = buildString {
                append(fmtSize(file.size))
                if (durMs > 0) append("  ·  ").append(fmtDur(durMs))
                if (running) {
                    append("  ·  ").append(fmtSize(rec)).append(" / ").append(fmtSize(tot))
                    val sp = fSt.value.optLong("speedBps", 0); if (sp > 0) append("  ·  ").append(fmtSize(sp)).append("/s")
                }
            }
            Text(info, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            if (fState == "error") Text(fSt.value.optString("detail", "Error"), color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (fState == "error") DlFallbackBar(file, fid, bridge) { fSt.value = JSONObject("{\"state\":\"running\"}") }
            if (bridge != null && file.source != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable fun b(t: String, bg: Color, f: () -> Unit) =
                    Text(t, Modifier.rounded(10.dp).background(bg).clickable { f() }.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                when {
                    fDone -> {
                        b(if (showPlayer) "CERRAR" else "REPRODUCIR", Red) { showPlayer = !showPlayer }
                        b("ABRIR", Blue) { bridge.openDownloadedMedia(fid) }
                        b("COMPARTIR", Ink) { bridge.shareDownloadedMedia(fid) }
                    }
                    running -> {
                        b("PAUSAR", Ink) { bridge.pauseMediaDownload(fid); fSt.value = JSONObject("{\"state\":\"paused\"}") }
                        b("CANCELAR", Red) { bridge.cancelMediaDownload(fid); wantPlay = false; fSt.value = JSONObject("{\"state\":\"canceled\"}") }
                    }
                    else -> b(if (fState == "paused") "REANUDAR" else "DESCARGAR", Blue) { tap() }
                }
            }
        }
    }
}
