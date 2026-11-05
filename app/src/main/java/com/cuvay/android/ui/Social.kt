package com.cuvay.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// =============================================================================
//  Red de bajo nivel (usa ChatApi: root, token, call y msgOf deben ser publicos)
// =============================================================================
fun ChatApi.gj(path: String) = call("GET", path)
fun ChatApi.pj(path: String, b: JSONObject) = call("POST", path, b)
fun ChatApi.fileUrl(id: String) = root + "/files/" + id

fun uiErr(e: Throwable): String = when ((e as? ChatError)?.code) {
    "too_big" -> "Archivo demasiado grande (fotos 12 MB, video 80 MB, audio/archivos 30 MB)"
    "quota" -> "Llegaste al limite de espacio (1 GB)"
    "rate_limited" -> "Vas muy rapido, espera un momento"
    "account_too_new" -> "Tu cuenta es muy nueva para subir videos, intenta en 1 hora"
    "channel_readonly" -> "En un canal solo publican los administradores"
    "not_member" -> "No eres miembro de este grupo"
    "too_many_rooms" -> "Ya creaste el maximo de grupos"
    "bad_name" -> "El nombre debe tener entre 3 y 40 letras"
    "no_room" -> "Ese grupo no existe o es privado (necesitas el codigo)"
    "unauthorized" -> "Inicia sesion en la pestana Chat"
    else -> e.message ?: "Error"
}

private fun shrink(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val cr = ctx.contentResolver
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > 1800) s *= 2
    var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
    val rot = cr.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
    } ?: 0f
    if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray()
}.getOrNull()

/**
 * Sube un archivo (foto comprimida, video, audio, lo que sea) y devuelve {id, kind, name, size, url}.
 * onProgress(0..1) se llama mientras sube. cancelled() devuelve true para abortar.
 */
fun ChatApi.upload(ctx: Context, uri: Uri, onProgress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): JSONObject {
    val cr = ctx.contentResolver
    var mime = cr.getType(uri) ?: "application/octet-stream"
    var name = "archivo"
    cr.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
    }
    var bytes: ByteArray? = null
    if (mime.startsWith("image/") && mime != "image/gif") {
        bytes = shrink(ctx, uri)
        if (bytes != null) { mime = "image/jpeg"; name = name.substringBeforeLast('.') + ".jpg" }
    }
    if (cancelled()) throw ChatError("canceled", "Cancelado")
    val c = URL(root + "/api/upload").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 180000
    c.setRequestProperty("X-Token", token); c.setRequestProperty("Content-Type", mime)
    c.setRequestProperty("X-Filename", URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
    if (bytes != null) {
        val data = bytes
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { o ->
            var off = 0
            while (off < data.size) {
                if (cancelled()) { c.disconnect(); throw ChatError("canceled", "Cancelado") }
                val n = minOf(32 * 1024, data.size - off)
                o.write(data, off, n); off += n
                onProgress(off.toFloat() / data.size)
            }
        }
    } else {
        val len = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (len <= 0) throw ChatError("bad_media", "No se pudo leer el archivo")
        c.setFixedLengthStreamingMode(len)
        cr.openInputStream(uri)!!.use { i ->
            c.outputStream.use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    if (cancelled()) { c.disconnect(); throw ChatError("canceled", "Cancelado") }
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    onProgress((done.toFloat() / len).coerceIn(0f, 1f))
                }
            }
        }
    }
    val code = c.responseCode
    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
    val j = JSONObject(txt)
    if (code !in 200..299) throw ChatError(j.optString("error", "error"), j.optString("error", "Error $code"))
    return j
}

private fun roomField(b: JSONObject, room: String): JSONObject =
    if (room == "global" || room.startsWith("g:")) b.put("room", room) else b.put("to", room.removePrefix("@"))

fun ChatApi.sendMedia(room: String, kind: String, uploadId: String, caption: String, reply: Long = 0L): CMsg {
    val b = JSONObject().put("kind", kind).put("upload", uploadId).put("caption", caption)
    if (reply > 0) b.put("reply", reply)
    return msgOf(pj("/api/send", roomField(b, room)).getJSONObject("message"))
}

fun ChatApi.sendLocation(room: String, lat: Double, lon: Double, reply: Long = 0L): CMsg {
    val b = JSONObject().put("kind", "location").put("lat", lat).put("lon", lon)
    if (reply > 0) b.put("reply", reply)
    return msgOf(pj("/api/send", roomField(b, room)).getJSONObject("message"))
}

/** Nombres y permisos de los grupos abiertos (los llena GroupsList para que RoomView muestre el titulo). */
object RoomNames { val name = mutableMapOf<String, String>(); val mod = mutableMapOf<String, Boolean>() }

// =============================================================================
//  Imagenes remotas
// =============================================================================
private val imgCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(k: String, v: ImageBitmap) = v.width * v.height * 4
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, showProgress: Boolean = true) {
    var bmp by remember(url) { mutableStateOf(imgCache.get(url)) }
    var frac by remember(url) { mutableStateOf<Float?>(null) }   // null = no se sabe cuanto pesa
    var failed by remember(url) { mutableStateOf(false) }
    var retry by remember(url) { mutableStateOf(0) }
    LaunchedEffect(url, retry) {
        if (bmp != null) return@LaunchedEffect
        failed = false; frac = null
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 30000
                val total = c.contentLengthLong
                val out = ByteArrayOutputStream()
                c.inputStream.use { i ->
                    val buf = ByteArray(16 * 1024); var done = 0L; var last = 0f
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val f = done.toFloat() / total; if (f - last >= 0.03f) { last = f; frac = f } }
                    }
                }
                val data = out.toByteArray()
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, o)
                var s = 1; while (o.outWidth / s > 1200) s *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
                    ?.asImageBitmap()?.also { imgCache.put(url, it) }
            }.getOrNull()
        }
        if (bmp == null) failed = true
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = scale)
    else Box((if (showProgress) modifier.heightIn(min = 140.dp) else modifier).background(Color(0x33FFFFFF))
        .then(if (failed) Modifier.clickable { retry++ } else Modifier), contentAlignment = Alignment.Center) {
        if (showProgress) {
            if (failed) Text("No se pudo cargar · toca para reintentar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            else {
                val f = frac
                Column(Modifier.fillMaxWidth(0.6f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (f != null) "${(f * 100).toInt()}%" else "Cargando…", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    BarLoader(f)
                }
            }
        }
    }
}

@Composable
fun AvatarImg(api: ChatApi, id: String, name: String, size: Dp) {
    if (id.isNotEmpty()) RemoteImage(api.fileUrl(id), Modifier.size(size).clip12(size), showProgress = false)
    else Box(Modifier.size(size).clip12(size).background(Blue), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.45f).sp)
    }
}
private fun Modifier.clip12(size: Dp): Modifier = this.then(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(size / 2)))

fun fmtBytes(b: Long) = when {
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

// =============================================================================
//  Bloques de media (chat y publicaciones)
// =============================================================================
@Composable
fun MediaBlock(api: ChatApi, kind: String, id: String, name: String, size: Long, mine: Boolean = false, imgHeight: Dp = 220.dp) {
    val ctx = LocalContext.current
    val url = api.fileUrl(id)
    var playVideo by remember { mutableStateOf(false) }
    val fg = if (mine) Color.White else Ink
    when (kind) {
        "image" -> RemoteImage(url, Modifier.fillMaxWidth().heightIn(max = imgHeight).clickable {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }, ContentScale.FillWidth)
        "video" -> {
            Column(Modifier.fillMaxWidth().background(Color(0xFF0B1330)).clickable { playVideo = true }.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▶", color = Color.White, fontSize = 40.sp)
                Text(name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                Text(fmtBytes(size), color = Color(0xFFBFD0FF), fontSize = 11.sp)
            }
            if (playVideo) Dialog(onDismissRequest = { playVideo = false }) {
                var loadingV by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxWidth().height(320.dp).background(Color.Black)) {
                    AndroidView(factory = { c ->
                        VideoView(c).apply {
                            val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                            setVideoURI(Uri.parse(url))
                            setOnPreparedListener { loadingV = false; start() }
                            setOnErrorListener { _, _, _ -> loadingV = false; false }
                        }
                    }, modifier = Modifier.fillMaxSize())
                    if (loadingV) Column(Modifier.align(Alignment.Center).padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cargando video…", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        BarLoader(null, Modifier.width(180.dp))
                    }
                }
            }
        }
        "audio" -> if (name.startsWith("voz_")) VoiceBubble(url, name, mine) else AudioChip(url, name, size, fg)
        else -> Row(Modifier.fillMaxWidth().clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📎", fontSize = 26.sp); Spacer(Modifier.width(8.dp))
            Column { Text(name, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
                Text(fmtBytes(size) + " · toca para abrir", color = if (mine) Color(0xFFBFD0FF) else Muted, fontSize = 11.sp) }
        }
    }
}

@Composable
fun AudioChip(url: String, name: String, size: Long, fg: Color) {
    var mp by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    DisposableEffect(url) { onDispose { runCatching { mp?.release() } } }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (loading) "…" else if (playing) "⏸" else "▶", Modifier.background(Red, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .clickable {
                val p = mp
                if (p == null) {
                    loading = true
                    mp = MediaPlayer().apply {
                        setDataSource(url)
                        setOnPreparedListener { loading = false; playing = true; start() }
                        setOnCompletionListener { playing = false }
                        setOnErrorListener { _, _, _ -> loading = false; playing = false; true }
                        prepareAsync()
                    }
                } else if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
            }.padding(horizontal = 16.dp, vertical = 10.dp), color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column { Text(name, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text("Audio · " + fmtBytes(size), color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
            if (loading) BarLoader(null, Modifier.width(140.dp).padding(top = 4.dp), 4.dp, Red, Color(0x33000000)) }
    }
}

/** Para pegar en Bubble() de Chat.kt: image / video / audio / file / location. */
@Composable
fun AttachBubble(api: ChatApi, kind: String, text: String, mine: Boolean) {
    val ctx = LocalContext.current
    val j = runCatching { JSONObject(text) }.getOrNull() ?: return
    val fg = if (mine) Color.White else Ink
    if (kind == "location") {
        val lat = j.optDouble("lat"); val lon = j.optDouble("lon")
        Column(Modifier.clickable {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Ubicacion)")))
        }) {
            Text("📍 Ubicacion", color = fg, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text("%.5f, %.5f".format(lat, lon), color = fg.copy(alpha = 0.8f), fontSize = 12.sp)
            Text("TOCA PARA ABRIR EL MAPA", color = if (mine) Color(0xFFBFD0FF) else Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        return
    }
    Column(Modifier.widthIn(min = 200.dp)) {
        MediaBlock(api, kind, j.optString("id"), j.optString("name"), j.optLong("size"), mine, 260.dp)
        val cap = j.optString("caption")
        if (cap.isNotEmpty()) Text(cap, color = fg, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

// =============================================================================
//  Boton de adjuntar: galeria, camara, video, audio, archivo, ubicacion
// =============================================================================
@SuppressLint("MissingPermission")
private fun currentLocation(ctx: Context, cb: (Location?) -> Unit) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val best = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (best != null && System.currentTimeMillis() - best.time < 120_000) { cb(best); return }
    val p = when { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER; else -> null }
    if (p == null) { cb(best); return }
    lm.requestSingleUpdate(p, object : LocationListener {
        override fun onLocationChanged(l: Location) = cb(l)
        @Deprecated("x") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }, Looper.getMainLooper())
}

/**
 * Selector de adjuntos. NO sube nada: solo devuelve el Uri elegido.
 * (Antes subia aqui dentro y, al cerrarse este menu, se cancelaba la subida y el boton se quedaba en "..." para siempre.)
 */
@Composable
fun AttachMenu(onDismiss: () -> Unit, allowLocation: Boolean, onPicked: (Uri) -> Unit,
               onLocation: (Double, Double) -> Unit, onError: (String) -> Unit, onBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    fun pick(uri: Uri) { onDismiss(); onPicked(uri) }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    fun newUri(ext: String): Uri {
        val d = File(ctx.cacheDir, "shared").apply { mkdirs() }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", File(d, "cam_${System.currentTimeMillis()}.$ext"))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::pick) }
    val camPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) camUri?.let(::pick) }
    val camVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> if (ok) camUri?.let(::pick) }
    val doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::pick) }
    fun locate() {
        onDismiss(); onBusy(true)
        currentLocation(ctx) { l -> onBusy(false); if (l != null) onLocation(l.latitude, l.longitude) else onError("No se pudo obtener la ubicacion (activa el GPS)") }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) locate() else onError("Permiso de ubicacion denegado") }

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.card(Color.White, 16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ADJUNTAR", color = Blue, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("FOTO / CAPTURA", Blue) { gallery.launch("image/*") }
                Btn("VIDEO", Blue) { gallery.launch("video/*") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("TOMAR FOTO", Red) { camUri = newUri("jpg"); camPhoto.launch(camUri!!) }
                Btn("GRABAR VIDEO", Red) { camUri = newUri("mp4"); camVideo.launch(camUri!!) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("MUSICA / AUDIO", Ink) { gallery.launch("audio/*") }
                Btn("ARCHIVO", Ink) { doc.launch(arrayOf("*/*")) }
            }
            if (allowLocation) Btn("UBICACION", Ink) {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                else perm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }
}

/**
 * Boton "+" del chat, estilo Telegram: eliges el archivo -> vista previa con comentario -> ENVIAR.
 * El envio aparece en la lista (outs) con barra de progreso real. La ubicacion se manda directa.
 */
@Composable
fun AttachButton(api: ChatApi, room: String, replyId: Long, outs: SnapshotStateList<OutUpload>,
                 onStart: () -> Unit, onSent: (CMsg) -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }     // solo mientras se manda la ubicacion
    Box(Modifier.rounded(22.dp).background(Color(0x33FFFFFF)).clickable(enabled = !busy) { open = true }
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        if (busy) Spinner(22.dp) else Text("＋", color = Color.White, fontSize = 20.sp)
    }
    if (open) AttachMenu({ open = false }, true,
        onPicked = { preview = it },
        onLocation = { la, lo -> scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { api.sendLocation(room, la, lo, replyId) } }
            busy = false; r.onSuccess(onSent).onFailure { onError(uiErr(it)) } } },
        onError = onError, onBusy = { busy = it })
    preview?.let { u ->
        AttachPreview(u, onCancel = { preview = null }) { caption ->
            preview = null
            val fi = fileInfo(ctx, u)
            val o = OutUpload(System.nanoTime(), u, fi.name, kindOf(fi.mime), fi.size, caption, replyId)
            outs.add(o); onStart()
            startUpload(ctx, api, room, o) { m -> outs.remove(o); onSent(m) }
        }
    }
}

// =============================================================================
//  GRUPOS Y CANALES
// =============================================================================
@Composable
fun GroupsList(api: ChatApi, me: String, onAuthFail: () -> Unit, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var rooms by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { runCatching { api.gj("/api/rooms/mine").optJSONArray("rooms") ?: JSONArray() } }
        r.onSuccess { a -> rooms = (0 until a.length()).map { a.getJSONObject(it) } }
            .onFailure { if ((it as? ChatError)?.code == "unauthorized") onAuthFail() else msg = uiErr(it) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("CREAR", Red) { showCreate = true }
            Btn("BUSCAR / UNIRME", Blue) { showFind = true }
            Btn("↻", Ink) { tick++ }
        }
        if (msg.isNotEmpty()) Text(msg, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        if (rooms.isEmpty()) Text("Aun no estas en ningun grupo o canal. Crea uno o busca uno publico.", color = Color.White.copy(alpha = 0.8f))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rooms) { r ->
                val rk = r.optString("room"); val role = r.optString("role")
                Row(Modifier.fillMaxWidth().card(Color(0x33FFFFFF), 14.dp).clickable {
                    RoomNames.name[rk] = r.optString("name"); RoomNames.mod[rk] = role == "owner" || role == "admin"; open(rk)
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarImg(api, "", r.optString("name"), 44.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (r.optString("kind") == "channel") "📢 " else "👥 ") + r.optString("name"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1)
                        val last = r.optJSONObject("last")
                        Text(if (last != null) "@${last.optString("from")}: " + last.optString("text").ifEmpty { "adjunto" }
                            else "${r.optInt("members")} miembros", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                    }
                    if (r.optInt("unread") > 0) Text("${r.optInt("unread")}", Modifier.background(Red, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Text("⋯", Modifier.clickable { manage = r }.padding(10.dp), color = Color.White, fontSize = 22.sp)
                }
            }
        }
    }
    if (showCreate) CreateRoomDialog(api, { showCreate = false }) { showCreate = false; tick++ }
    if (showFind) FindRoomDialog(api, { showFind = false }) { showFind = false; tick++ }
    manage?.let { RoomAdminDialog(api, me, it, { manage = null }) { manage = null; tick++ } }
}

@Composable
private fun Lbl(t: String) = Text(t, color = Blue, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun CreateRoomDialog(api: ChatApi, onDismiss: () -> Unit, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("group") }
    var name by remember { mutableStateOf("") }
    var descr by remember { mutableStateOf("") }
    var pub by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.card(Color.White, 16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("NUEVO", color = Blue, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("GRUPO", if (kind == "group") Blue else Muted) { kind = "group" }
                Btn("CANAL", if (kind == "channel") Blue else Muted) { kind = "channel" }
            }
            Text(if (kind == "group") "Todos los miembros pueden escribir." else "Solo tu y tus admins publican; los demas leen.", color = Muted, fontSize = 12.sp)
            Field(name, { name = it.take(40) }, "Nombre", Modifier.fillMaxWidth())
            Field(descr, { descr = it.take(200) }, "Descripcion (opcional)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("PUBLICO", if (pub) Blue else Muted) { pub = true }
                Btn("PRIVADO", if (!pub) Blue else Muted) { pub = false }
            }
            Text(if (pub) "Cualquiera puede encontrarlo y unirse." else "Solo entran con tu codigo de invitacion.", color = Muted, fontSize = 12.sp)
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 12.sp)
            Btn("CREAR", Red) { scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { api.pj("/api/rooms/create", JSONObject().put("kind", kind).put("name", name).put("descr", descr).put("public", pub)) } }
                r.onSuccess { done() }.onFailure { err = uiErr(it) } } }
        }
    }
}

@Composable
private fun FindRoomDialog(api: ChatApi, onDismiss: () -> Unit, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    fun search() = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { api.gj("/api/rooms/search?q=" + URLEncoder.encode(q, "UTF-8")).optJSONArray("rooms") ?: JSONArray() } }
        r.onSuccess { a -> res = (0 until a.length()).map { a.getJSONObject(it) } }.onFailure { err = uiErr(it) }
    }
    fun join(b: JSONObject) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { api.pj("/api/rooms/join", b) } }
        r.onSuccess { done() }.onFailure { err = uiErr(it) }
    }
    LaunchedEffect(Unit) { search() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.card(Color.White, 16.dp).padding(16.dp).heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("BUSCAR", color = Blue, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(q, { q = it }, "Nombre del grupo o canal", Modifier.weight(1f)); Btn("IR", Blue) { search() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(code, { code = it.trim() }, "Codigo de invitacion", Modifier.weight(1f))
                Btn("ENTRAR", Red) { if (code.isNotEmpty()) join(JSONObject().put("code", code)) }
            }
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 12.sp)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                res.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (r.optString("kind") == "channel") "📢 " else "👥 ") + r.optString("name"), color = Ink, fontWeight = FontWeight.Bold)
                            Text("${r.optInt("members")} miembros" + r.optString("descr").let { if (it.isNotEmpty()) " · $it" else "" }, color = Muted, fontSize = 12.sp, maxLines = 2)
                        }
                        if (r.optString("role").isEmpty()) Btn("UNIRME", Blue) { join(JSONObject().put("id", r.optInt("id"))) }
                        else Text("YA ESTAS", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomAdminDialog(api: ChatApi, me: String, r: JSONObject, onDismiss: () -> Unit, changed: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = r.optInt("id"); val role = r.optString("role")
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        runCatching { withContext(Dispatchers.IO) { api.gj("/api/rooms/members?id=$id").optJSONArray("members") ?: JSONArray() } }
            .onSuccess { a -> members = (0 until a.length()).map { a.getJSONObject(it) } }
    }
    fun act(path: String, b: JSONObject, close: Boolean = false) = scope.launch {
        val x = withContext(Dispatchers.IO) { runCatching { api.pj(path, b.put("id", id)) } }
        x.onSuccess { if (close) changed() else tick++ }.onFailure { err = uiErr(it) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.card(Color.White, 16.dp).padding(16.dp).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.optString("name"), color = Blue, fontWeight = FontWeight.Black, fontSize = 20.sp)
            if (r.optString("descr").isNotEmpty()) Text(r.optString("descr"), color = Muted, fontSize = 13.sp)
            if (r.has("code")) { Lbl("CODIGO DE INVITACION"); Text(r.optString("code"), color = Ink, fontWeight = FontWeight.Black, fontSize = 18.sp) }
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 12.sp)
            Lbl("MIEMBROS (${members.size})")
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                members.forEach { m ->
                    val n = m.optString("name"); val mr = m.optString("role")
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("@$n" + if (mr != "member") " · $mr" else "", color = Ink, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        if (n != me && mr != "owner") {
                            if (role == "owner") Btn(if (mr == "admin") "QUITAR ADMIN" else "HACER ADMIN", Blue) { act("/api/rooms/role", JSONObject().put("user", n).put("role", if (mr == "admin") "member" else "admin")) }
                            if (role == "owner" || (role == "admin" && mr == "member")) { Spacer(Modifier.width(4.dp)); Btn("EXPULSAR", Red) { act("/api/rooms/kick", JSONObject().put("user", n)) } }
                        }
                    }
                }
            }
            if (role == "owner") Btn("ELIMINAR PARA TODOS", Red) { act("/api/rooms/delete", JSONObject(), true) }
            else Btn("SALIR", Red) { act("/api/rooms/leave", JSONObject(), true) }
        }
    }
}

// =============================================================================
//  RED SOCIAL
// =============================================================================
@Composable
fun RedScreen() {
    val ctx = LocalContext.current
    val ses = remember { loadSession(ctx) }
    if (ses.token.isEmpty()) {
        Text("Entra primero en la pestana Chat con tu usuario y clave.", Modifier.fillMaxWidth().card(Color.White, 12.dp).padding(16.dp), color = Ink)
        return
    }
    val api = remember { ChatApi(ses.url, ses.token) }
    var tab by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf<String?>(null) }
    var compose by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    BackHandler(enabled = profile != null) { profile = null }
    Column(Modifier.fillMaxSize()) {
        if (profile == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("PARA TI", if (tab == 0) Blue else Muted) { tab = 0 }
            Btn("SIGUIENDO", if (tab == 1) Blue else Muted) { tab = 1 }
            Btn("MI PERFIL", if (tab == 2) Blue else Muted) { tab = 2 }
            Btn("＋", Red) { compose = true }
        }
        Spacer(Modifier.height(8.dp))
        val p = profile
        when {
            p != null -> ProfileView(api, ses.name, p, { profile = it })
            tab == 2 -> ProfileView(api, ses.name, ses.name, { profile = it })
            else -> Feed(api, ses.name, if (tab == 1) "following" else "all", tick) { profile = it }
        }
    }
    if (compose) ComposeDialog(api, { compose = false }) { compose = false; tick++ }
}

@Composable
private fun Feed(api: ChatApi, me: String, mode: String, refresh: Int, user: String? = null, openProfile: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var posts by remember(mode, refresh, user) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var msg by remember(mode, refresh, user) { mutableStateOf("") }
    var more by remember(mode, refresh, user) { mutableStateOf(true) }
    var loading by remember(mode, refresh, user) { mutableStateOf(true) }
    fun load(before: Long) = scope.launch {
        loading = true
        val path = if (user != null) "/api/posts/user?name=$user" else "/api/posts/feed?mode=$mode"
        val r = withContext(Dispatchers.IO) { runCatching { api.gj("$path&before=$before&limit=15").optJSONArray("posts") ?: JSONArray() } }
        r.onSuccess { a -> val n = (0 until a.length()).map { a.getJSONObject(it) }; posts = posts + n; more = n.size == 15 }.onFailure { msg = uiErr(it) }
        loading = false
    }
    LaunchedEffect(mode, refresh, user) { load(0) }
    if (msg.isNotEmpty()) Text(msg, color = Color.White)
    if (loading && posts.isEmpty()) LoadingBlock("Cargando publicaciones…")
    else if (posts.isEmpty() && msg.isEmpty()) Text(if (mode == "following") "Aun no sigues a nadie, o no han publicado." else "Todavia no hay publicaciones. Se el primero.", color = Color.White.copy(alpha = 0.8f))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(posts, key = { it.optLong("id") }) { p ->
            PostCard(api, me, p, openProfile) { posts = posts.filter { x -> x.optLong("id") != p.optLong("id") } }
        }
        if (posts.isNotEmpty() && (loading || more)) item {
            if (loading) BarLoader(null, Modifier.padding(vertical = 8.dp)) else Btn("VER MAS", Blue) { load(posts.last().optLong("id")) }
        }
    }
}

@Composable
private fun PostCard(api: ChatApi, me: String, p0: JSONObject, openProfile: (String) -> Unit, removed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var p by remember(p0) { mutableStateOf(p0) }
    var showComments by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val id = p.optLong("id"); val author = p.optString("author")
    fun act(path: String, b: JSONObject = JSONObject(), after: (JSONObject) -> Unit = { p = it.optJSONObject("post") ?: p }) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { api.pj(path, b.put("id", id)) } }
        r.onSuccess(after).onFailure { note = uiErr(it) }
    }
    Column(Modifier.fillMaxWidth().card(Color.White, 14.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clickable { openProfile(author) }, verticalAlignment = Alignment.CenterVertically) {
            AvatarImg(api, p.optString("avatar"), author, 36.dp); Spacer(Modifier.width(8.dp))
            Column { Text("@$author", color = Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(ago(p.optLong("ts")), color = Muted, fontSize = 11.sp) }
        }
        if (p.optString("text").isNotEmpty()) Text(p.optString("text"), color = Ink, fontSize = 15.sp)
        val md = p.optJSONArray("media") ?: JSONArray()
        for (i in 0 until md.length()) { val m = md.getJSONObject(i)
            MediaBlock(api, m.optString("kind"), m.optString("id"), m.optString("name"), m.optLong("size"), false, 380.dp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (p.optBoolean("liked")) "❤️ " else "🤍 ") + p.optInt("likes"), Modifier.clickable { act("/api/posts/like") }.padding(end = 18.dp, top = 4.dp, bottom = 4.dp), color = Ink, fontWeight = FontWeight.Bold)
            Text("💬 ${p.optInt("comments")}", Modifier.clickable { showComments = true }.padding(end = 18.dp, top = 4.dp, bottom = 4.dp), color = Ink, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (author != me) Text("REPORTAR", Modifier.clickable { act("/api/report", JSONObject().put("kind", "post").put("reason", "reportado")) { note = "Reporte enviado" } }.padding(4.dp), color = Muted, fontSize = 11.sp)
            if (author == me) Text("BORRAR", Modifier.clickable { act("/api/posts/delete") { removed() } }.padding(4.dp), color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (note.isNotEmpty()) Text(note, color = Red, fontSize = 12.sp)
    }
    if (showComments) CommentsDialog(api, id, { showComments = false }) { p = it }
}

@Composable
private fun CommentsDialog(api: ChatApi, postId: Long, onDismiss: () -> Unit, updated: (JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    fun load() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { api.gj("/api/posts/comments?id=$postId").optJSONArray("comments") ?: JSONArray() } }
            .onSuccess { a -> list = (0 until a.length()).map { a.getJSONObject(it) } }
    }
    LaunchedEffect(Unit) { load() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.card(Color.White, 16.dp).padding(16.dp).heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("COMENTARIOS", color = Blue, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("Sin comentarios todavia.", color = Muted)
                list.forEach { c -> Text("@${c.optString("author")}  ${c.optString("text")}", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(text, { text = it.take(500) }, "Escribe un comentario", Modifier.weight(1f))
                Btn("ENVIAR", Red) { if (text.isNotBlank()) scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { api.pj("/api/posts/comment", JSONObject().put("id", postId).put("text", text)) } }
                    r.onSuccess { updated(it.getJSONObject("post")); text = ""; load() }.onFailure { err = uiErr(it) } } }
            }
        }
    }
}

@Composable
private fun ComposeDialog(api: ChatApi, onDismiss: () -> Unit, posted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val ups = remember { mutableStateListOf<OutUpload>() }
    var showAttach by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }      // publicando
    var err by remember { mutableStateOf("") }
    val uploading = ups.any { it.phase == "prep" || it.phase == "up" }
    fun publish() {
        if (busy || uploading) return
        if (ups.any { it.phase == "err" }) { err = "Hay archivos con error: reintenta o quitalos"; return }
        err = ""
        scope.launch {
            busy = true
            val ids = ups.filter { it.phase == "done" }.map { it.uploadId }
            val r = withContext(Dispatchers.IO) { runCatching { api.pj("/api/posts/create", JSONObject().put("text", text).put("media", JSONArray(ids))) } }
            busy = false; r.onSuccess { posted() }.onFailure { err = uiErr(it) }
        }
    }
    Dialog(onDismissRequest = { ups.forEach { it.cancelled = true }; onDismiss() }) {
        Column(Modifier.card(Color.White, 16.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NUEVA PUBLICACION", color = Blue, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Box(Modifier.fillMaxWidth().heightIn(min = 110.dp).card(Color(0xFFF1F4FA), 10.dp).padding(12.dp)) {
                if (text.isEmpty()) Text("¿Que quieres compartir?", color = Muted)
                BasicTextField(text, { text = it.take(2000) }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = Ink, fontSize = 15.sp), cursorBrush = SolidColor(Blue))
            }
            ups.forEach { u ->
                Column(Modifier.fillMaxWidth().card(Color(0xFFF1F4FA), 10.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(iconFor(u.kind) + "  " + u.name, Modifier.weight(1f), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("✕", Modifier.clickable { u.cancelled = true; ups.remove(u) }.padding(6.dp), color = Red, fontWeight = FontWeight.Bold)
                    }
                    when (u.phase) {
                        "done" -> Text("✓ Listo", color = Color(0xFF059669), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        "err" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(u.err, Modifier.weight(1f), color = Red, fontSize = 12.sp)
                            Text("REINTENTAR", Modifier.clickable { startPostUpload(ctx, api, u) }.padding(6.dp), color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        else -> ProgressLine(if (u.phase == "prep") "Preparando…" else "Subiendo", if (u.phase == "up") u.frac else null)
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color = Red, fontSize = 12.sp)
            if (busy) BarLoader(null, track = Chip)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("＋ FOTO / VIDEO / AUDIO", Blue) { if (!busy) showAttach = true }
                Btn(if (busy) "PUBLICANDO…" else if (uploading) "ESPERA…" else "PUBLICAR", if (uploading) Muted else Red) { publish() }
            }
        }
    }
    if (showAttach) AttachMenu({ showAttach = false }, false,
        onPicked = { uri ->
            val fi = fileInfo(ctx, uri)
            val o = OutUpload(System.nanoTime(), uri, fi.name, kindOf(fi.mime), fi.size, "", 0L)
            ups.add(o); startPostUpload(ctx, api, o)
        },
        onLocation = { _, _ -> }, onError = { err = it }, onBusy = { })
}

@Composable
private fun ProfileView(api: ChatApi, me: String, name: String, openProfile: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var prof by remember(name) { mutableStateOf<JSONObject?>(null) }
    var tick by remember(name) { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var bio by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(name, tick) {
        runCatching { withContext(Dispatchers.IO) { api.gj("/api/profile?name=$name") } }
            .onSuccess { prof = it; bio = it.optString("bio") }.onFailure { msg = uiErr(it) }
    }
    fun saveProfile(avatar: String) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { api.pj("/api/profile", JSONObject().put("bio", bio).put("avatar", avatar)) } }
        r.onSuccess { editing = false; tick++ }.onFailure { msg = uiErr(it) }
    }
    var avUp by remember { mutableStateOf<Float?>(null) }
    val avatarPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            avUp = 0f
            val r = withContext(Dispatchers.IO) { runCatching { api.upload(ctx, uri, { f -> avUp = f }) } }
            avUp = null
            r.onSuccess { saveProfile(it.getString("id")) }.onFailure { msg = uiErr(it) }
        }
    }
    val p = prof
    Column(Modifier.fillMaxSize()) {
        if (p != null) Column(Modifier.fillMaxWidth().card(Color.White, 14.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImg(api, p.optString("avatar"), name, 64.dp); Spacer(Modifier.width(12.dp))
                Column { Text("@$name", color = Ink, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("${p.optInt("posts")} posts · ${p.optInt("followers")} seguidores · ${p.optInt("following")} siguiendo", color = Muted, fontSize = 12.sp) }
            }
            avUp?.let { ProgressLine("Subiendo foto…", it) }
            if (editing) { Field(bio, { bio = it.take(200) }, "Tu biografia", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("GUARDAR", Red) { saveProfile("") }; Btn("CAMBIAR FOTO", Blue) { avatarPick.launch("image/*") } } }
            else if (p.optString("bio").isNotEmpty()) Text(p.optString("bio"), color = Ink, fontSize = 14.sp)
            if (p.optBoolean("me")) { if (!editing) Btn("EDITAR PERFIL", Blue) { editing = true } }
            else Btn(if (p.optBoolean("isFollowing")) "SIGUIENDO ✓" else "SEGUIR", if (p.optBoolean("isFollowing")) Muted else Red) { scope.launch {
                withContext(Dispatchers.IO) { runCatching { api.pj("/api/follow", JSONObject().put("name", name).put("on", !p.optBoolean("isFollowing"))) } }; tick++ } }
        }
        if (msg.isNotEmpty()) Text(msg, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Feed(api, me, "all", tick, name, openProfile)
    }
}

private fun ago(ts: Long): String {
    val s = System.currentTimeMillis() / 1000 - ts
    return when { s < 60 -> "ahora"; s < 3600 -> "hace ${s / 60} min"; s < 86400 -> "hace ${s / 3600} h"; else -> "hace ${s / 86400} d" }
}
