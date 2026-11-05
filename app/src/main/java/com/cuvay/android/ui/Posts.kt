package com.cuvay.android.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cuvay.android.AndroidBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// =============================================================================
//  Foto de perfil de cada canal (como en Telegram)
//  Se baja una sola vez de t.me (directo, no por DNS) y queda guardada 7 dias en el telefono.
//  Si el telefono no llega a t.me o el canal no tiene foto, se ve la letra de siempre.
// =============================================================================
private val avCache = object : LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
    override fun sizeOf(k: String, v: ImageBitmap) = v.width * v.height * 4
}
private val avMiss = mutableSetOf<String>()   // fallos de esta sesion: no se reintenta en cada scroll

private fun loadAvatar(ctx: Context, key: String): ImageBitmap? {
    val dir = File(ctx.cacheDir, "avatars").apply { mkdirs() }
    val f = File(dir, "$key.jpg")
    if (f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 3600 * 1000)
        BitmapFactory.decodeFile(f.path)?.let { return it.asImageBitmap() }
    val c = URL("https://t.me/i/userpic/320/$key.jpg").openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 15000
    if (c.responseCode != 200) return null
    val data = c.inputStream.use { it.readBytes() }
    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    runCatching { f.writeBytes(data) }
    return bmp.asImageBitmap()
}

@Composable
fun ChannelAvatar(username: String, title: String, size: Dp = 42.dp) {
    val ctx = LocalContext.current
    val key = username.trim().trimStart('@').lowercase()
    var bmp by remember(key) { mutableStateOf(avCache.get(key)) }
    LaunchedEffect(key) {
        if (bmp != null || key.isEmpty() || key in avMiss) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { loadAvatar(ctx, key) }.getOrNull() }
        if (r != null) { avCache.put(key, r); bmp = r } else avMiss.add(key)
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    else Avatar(title.ifBlank { username }, size)
}

/** Fila de la lista de canales: foto grande, nombre y @usuario. */
@Composable
fun ChannelRow(c: Api.Channel, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).card(Color.White, 18.dp).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ChannelAvatar(c.name, c.title, 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("@${c.name}", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("›", color = Muted, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// =============================================================================
//  Fotos de las publicaciones
// =============================================================================
/** Las fotos de hasta este peso se bajan solas; las mas grandes esperan a que las toques (cada foto son muchas consultas DNS). */
private const val AUTO_MAX = 800_000L

private fun isDoneState(s: String) = s == "done" || s == "completed"

/** Reconoce el formato por los primeros bytes (para saber por que una foto no se ve). */
private fun sniffFormat(h: ByteArray): String {
    if (h.size < 12) return "vacío o incompleto"
    val s = String(h, Charsets.ISO_8859_1)
    return when {
        (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xFF) == 0xD8 -> "JPEG"
        s.startsWith("\u0089PNG") -> "PNG"
        s.startsWith("GIF8") -> "GIF"
        s.startsWith("RIFF") && s.substring(8, 12) == "WEBP" -> "WEBP"
        s.substring(4, 8) == "ftyp" -> s.substring(8, 12).trim().uppercase()      // AVIF, HEIC...
        (h[0].toInt() and 0xFF) == 0x1F && (h[1].toInt() and 0xFF) == 0x8B -> "gzip"
        else -> "desconocido"
    }
}

private fun scaledFromBytes(b: ByteArray): ImageBitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, o)
    var s = 1; while (o.outWidth / s > 1200) s *= 2
    return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}

/**
 * Decodifica la foto descargada. Primero lee el archivo privado directo (no depende de FileProvider);
 * si no hay, prueba por URI. Devuelve la imagen, o null y el motivo (formato y peso) para mostrarlo.
 */
private fun decodePhoto(ctx: Context, bridge: AndroidBridge, id: String): Pair<ImageBitmap?, String> {
    val f = bridge.downloadedFile(id)
    if (f != null) {
        val head = runCatching { f.inputStream().use { st -> ByteArray(12).also { st.read(it) } } }.getOrDefault(ByteArray(12))
        val fmt = sniffFormat(head)
        val bmp = runCatching {
            val raw = if (fmt == "gzip") java.util.zip.GZIPInputStream(f.inputStream()).use { it.readBytes() } else f.readBytes()
            scaledFromBytes(raw)
        }.getOrNull()
        return if (bmp != null) bmp to "" else null to "$fmt, ${fmtSize(f.length())}"
    }
    val u = bridge.downloadedUri(id) ?: return null to "sin archivo local"
    val bmp = runCatching {
        val raw = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching null
        scaledFromBytes(raw)
    }.getOrNull()
    return if (bmp != null) bmp to "" else null to "no se pudo leer"
}

@Composable
private fun PhotoBlock(f: Api.Media, ch: Int, msgId: Long, bridge: AndroidBridge?, auto: Boolean, onView: (ImageBitmap, String) -> Unit) {
    val ctx = LocalContext.current
    val id = "m${ch}_${msgId}_${f.crc}"
    var state by remember(id) { mutableStateOf("idle") }
    var received by remember(id) { mutableStateOf(0L) }
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var tapped by remember(id) { mutableStateOf(false) }
    var retry by remember(id) { mutableStateOf(0) }
    var why by remember(id) { mutableStateOf("") }
    val go = tapped || (auto && f.size <= AUTO_MAX)
    remember(id, DlConfig.photoRoute) { DlConfig.applyPhotoRoute(f) }

    // Baja la foto (si hace falta) y sigue su progreso.
    LaunchedEffect(id, go, retry) {
        if (bridge == null || f.source == null) return@LaunchedEffect
        var started = false; var n = 0
        while (n++ < 600) {
            val j = runCatching { JSONObject(bridge.mediaDownloadStatus(id)) }.getOrNull()
            val s = j?.optString("state") ?: "idle"
            if (j != null) { state = s; received = j.optLong("received", 0) }
            if (isDoneState(s)) break
            if (s == "idle" || s == "canceled" || s == "error") {
                if (go && !started) {
                    started = true
                    // El prefijo "feed_" le dice al motor que la guarde privada (sin Descargas ni aviso).
                    bridge.startMediaDownload(id, f.url(), "feed_" + f.name.ifEmpty { "$id.jpg" }, f.size, f.crc, f.sha)
                    state = "running"
                } else if (!go || (started && s == "error")) break
            }
            delay(600)
        }
    }
    // Cuando termina, la decodifica (reducida) y la muestra.
    LaunchedEffect(id, state) {
        if (!isDoneState(state) || bmp != null || bridge == null) return@LaunchedEffect
        val res = withContext(Dispatchers.IO) { decodePhoto(ctx, bridge, id) }
        bmp = res.first
        why = res.second
    }

    val b = bmp
    if (b != null) {
        val ratio = (b.width.toFloat() / b.height).coerceIn(0.6f, 2f)
        Image(b, null, Modifier.fillMaxWidth().aspectRatio(ratio).rounded(14.dp).clickable { onView(b, id) },
            contentScale = ContentScale.Crop)
    } else {
        val running = state == "running" || state == "verifying"
        val frac = if (f.size > 0) (received.toFloat() / f.size).coerceIn(0f, 1f) else 0f
        Column {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).rounded(14.dp).background(Chip)
            .clickable {
                if (state == "error") { retry++; tapped = true } else if (!go) tapped = true
                else if (isDoneState(state)) bridge?.openDownloadedMedia(id)
            }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                when {
                    f.source == null -> Text("📷 Foto no disponible", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    bridge == null -> Text("📷 Foto", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    state == "error" -> Text("No se pudo bajar · toca para reintentar", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    isDoneState(state) -> Text("No se pudo mostrar" + (if (why.isNotEmpty()) " ($why)" else "") + " · toca para abrir", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    !go -> Text("📷 TOCA PARA VER · " + fmtSize(f.size), color = Blue, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    else -> {
                        Text(if (running && received > 0) "📷 ${(frac * 100).toInt()}%  ·  ${fmtSize(received)} / ${fmtSize(f.size)}" else "📷 Cargando foto…",
                            color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.6f).height(5.dp).rounded(3.dp).background(Color(0xFFCBD3E6))) {
                            Box(Modifier.fillMaxWidth(if (running) frac else 0.08f).fillMaxHeight().background(Red))
                        }
                    }
                }
            }
        }
        if (state == "error") DlFallbackBar(f, id, bridge, namePrefix = "feed_", ext = ".jpg") { state = "running"; retry++ }
        }
    }
}

/** Foto a pantalla completa: pellizca para acercar, doble toque alterna zoom, toca fuera o la X para cerrar. */
@Composable
private fun PhotoViewer(b: ImageBitmap, onSave: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var off by remember { mutableStateOf(Offset.Zero) }
        var saved by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color(0xF2000000))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale <= 1.05f) onClose() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    off = if (scale <= 1f) Offset.Zero else off + pan
                }
            }, contentAlignment = Alignment.Center) {
            Image(b, null, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y),
                contentScale = ContentScale.Fit)
            Text("✕", Modifier.align(Alignment.TopEnd).padding(16.dp).clickable { onClose() }.padding(8.dp),
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                Btn(if (saved) "GUARDADA ✓" else "GUARDAR EN EL TELÉFONO", if (saved) Ink else Blue) {
                    if (!saved) { saved = true; onSave() }
                }
            }
        }
    }
}

// =============================================================================
//  Tarjeta de publicacion
// =============================================================================
@Composable
fun PostCard(ch: Api.Channel, m: Api.Msg, bridge: AndroidBridge?, photosAuto: Boolean) {
    // Mismo filtro de antes, pero una IMAGE sin nombre ahora se muestra si se puede descargar.
    val vis = m.media.filter { it.kind != "IMAGE" || it.name.isNotEmpty() || it.source != null }
    val thumb = vis.firstOrNull { it.kind == "IMAGE" && it.name.startsWith("thumb_") }
    val main = vis.filter { !(it.kind == "IMAGE" && it.name.startsWith("thumb_")) }
    val photos = main.filter { it.kind == "IMAGE" || it.kind == "PHOTO" }
    val others = main.filter { it.kind != "IMAGE" && it.kind != "PHOTO" }
    val rich = others.size == 1 && (others[0].kind == "VIDEO" || others[0].kind == "AUDIO")
    var viewing by remember { mutableStateOf<Pair<ImageBitmap, String>?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().card(Color.White, 20.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(ch.name, ch.title, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.time, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("#${m.id}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        photos.forEach { f ->
            PhotoBlock(f, ch.num, m.id, bridge, photosAuto) { bmp, pid -> viewing = bmp to pid }
            Spacer(Modifier.height(8.dp))
        }
        if (rich) RichMediaCard(thumb, others[0], ch.num, m.id, bridge)
        else others.forEach { f ->
            val (label, color) = when (f.kind) {
                "AUDIO" -> "AUDIO" to Red
                "VIDEO" -> "VIDEO" to Blue
                else -> "ARCHIVO" to Ink
            }
            MediaCard(f, label, color, "m${ch.num}_${m.id}_${f.crc}", bridge)
        }
        if (others.isNotEmpty()) SendToBotSection(ch, m.id, others[0])
        CaptionText(m.caption)
    }
    viewing?.let { (bmp, pid) ->
        PhotoViewer(bmp, onSave = { scope.launch(Dispatchers.IO) { runCatching { bridge?.saveDownloadedMedia(pid) } } }) { viewing = null }
    }
}
