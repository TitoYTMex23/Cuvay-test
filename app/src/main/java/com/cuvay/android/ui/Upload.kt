package com.cuvay.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// =============================================================================
//  Datos del archivo elegido
// =============================================================================
class FileInfo(val name: String, val mime: String, val size: Long)

fun fileInfo(ctx: Context, uri: Uri): FileInfo {
    val cr = ctx.contentResolver
    var name = "archivo"
    var size = -1L
    cr.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) name = c.getString(i) ?: name
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
        }
    }
    if (size < 0) size = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: 0L
    return FileInfo(name, cr.getType(uri) ?: "application/octet-stream", size)
}

fun kindOf(mime: String) = when {
    mime.startsWith("image/") -> "image"
    mime.startsWith("video/") -> "video"
    mime.startsWith("audio/") -> "audio"
    else -> "file"
}

fun iconFor(kind: String) = when (kind) { "image" -> "🖼"; "video" -> "🎬"; "audio" -> "🎵"; else -> "📎" }

/** Limites del servidor (mismos que dice uiErr). Las fotos se comprimen antes de subir, asi que no se revisan. */
private fun limitFor(kind: String): Long = when (kind) { "video" -> 80L shl 20; "audio", "file" -> 30L shl 20; else -> Long.MAX_VALUE }

// =============================================================================
//  Miniatura local (para la vista previa y la burbuja mientras sube)
// =============================================================================
@Composable
fun rememberLocalThumb(uri: Uri, kind: String): ImageBitmap? {
    val ctx = LocalContext.current
    val st = produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (kind) {
                    "image" -> {
                        val cr = ctx.contentResolver
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                        var sample = 1
                        while (maxOf(o.outWidth, o.outHeight) / sample > 900) sample *= 2
                        var bmp: Bitmap? = cr.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                        val rot = cr.openInputStream(uri)?.use {
                            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                else -> 0f
                            }
                        } ?: 0f
                        if (bmp != null && rot != 0f)
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
                        bmp?.asImageBitmap()
                    }
                    "video" -> {
                        val r = MediaMetadataRetriever()
                        try { r.setDataSource(ctx, uri); r.getFrameAtTime(1_000_000)?.asImageBitmap() }
                        finally { runCatching { r.release() } }
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }
    return st.value
}

// =============================================================================
//  Envio en segundo plano con progreso
// =============================================================================
/** Un archivo que se esta subiendo (o que fallo). Sus campos son estado de Compose: la pantalla se actualiza sola. */
class OutUpload(val id: Long, val uri: Uri, val name: String, val kind: String, val size: Long,
                val caption: String, val reply: Long) {
    var phase by mutableStateOf("prep")   // prep (preparando) | up (subiendo) | send (enviando) | done (solo posts) | err
    var frac by mutableStateOf(0f)
    var err by mutableStateOf("")
    var uploadId by mutableStateOf("")    // id del archivo ya subido (para publicaciones)
    @Volatile var cancelled = false
}

/**
 * Scope propio de la app: la subida NO se cancela si sales del chat o cierras el dialogo.
 * (Antes la subida vivia en el scope del menu de adjuntar, que se destruye al cerrarse el menu.)
 */
internal val upScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun doUpload(ctx: Context, api: ChatApi, o: OutUpload): JSONObject {
    var last = 0f
    o.cancelled = false; o.phase = "prep"; o.frac = 0f; o.err = ""
    return api.upload(ctx.applicationContext, o.uri, { f ->
        o.phase = "up"
        if (f >= 1f || f - last >= 0.01f) { last = f; o.frac = f }     // no satura la UI
    }, { o.cancelled })
}

/** Chat: sube el archivo y luego manda el mensaje con su comentario (caption). */
fun startUpload(ctx: Context, api: ChatApi, room: String, o: OutUpload, onDone: (CMsg) -> Unit) {
    upScope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val up = doUpload(ctx, api, o)
                o.phase = "send"; o.frac = 1f
                api.sendMedia(room, up.getString("kind"), up.getString("id"), o.caption, o.reply)
            }
        }
        r.onSuccess { onDone(it) }
            .onFailure { e -> if (!o.cancelled) { o.phase = "err"; o.err = uiErr(e) } }
    }
}

/** Publicaciones: solo sube; al terminar deja uploadId listo y phase = "done". */
fun startPostUpload(ctx: Context, api: ChatApi, o: OutUpload) {
    upScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { doUpload(ctx, api, o) } }
        r.onSuccess { o.uploadId = it.getString("id"); o.phase = "done"; o.frac = 1f }
            .onFailure { e -> if (!o.cancelled) { o.phase = "err"; o.err = uiErr(e) } }
    }
}

// =============================================================================
//  Vista previa estilo Telegram: miniatura grande + comentario + ENVIAR
// =============================================================================
@Composable
fun AttachPreview(uri: Uri, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val info = remember(uri) { fileInfo(ctx, uri) }
    val kind = remember(uri) { kindOf(info.mime) }
    val thumb = rememberLocalThumb(uri, kind)
    var cap by remember { mutableStateOf("") }
    val tooBig = info.size > limitFor(kind)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).card(Color.White, 20.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (kind) { "image" -> "ENVIAR FOTO"; "video" -> "ENVIAR VIDEO"; "audio" -> "ENVIAR AUDIO"; else -> "ENVIAR ARCHIVO" },
                color = Blue, fontWeight = FontWeight.Black, fontSize = 17.sp)
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp).clip(RoundedCornerShape(14.dp)).background(Ink),
                contentAlignment = Alignment.Center) {
                if (thumb != null) Image(thumb, null, Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.Fit)
                else if (kind == "image" || kind == "video") BarLoader(null, Modifier.fillMaxWidth(0.5f))   // decodificando la miniatura
                else Text(iconFor(kind), fontSize = 52.sp)
                if (kind == "video" && thumb != null) Text("▶", color = Color.White, fontSize = 40.sp)
            }
            Text(info.name + "  ·  " + fmtBytes(info.size), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (tooBig) Text("Este archivo pesa demasiado (máximo ${limitFor(kind) shr 20} MB).", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            ChatInput(cap, { cap = it.take(300) }, "Añade un comentario…", Modifier.fillMaxWidth(), maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("CANCELAR", Ink) { onCancel() }
                Btn("ENVIAR", if (tooBig) Muted else Red) { if (!tooBig) onSend(cap.trim()) }
            }
        }
    }
}

// =============================================================================
//  Burbuja "enviando…" con barra de progreso (va en la lista del chat)
// =============================================================================
@Composable
fun UploadBubble(o: OutUpload, onCancel: () -> Unit, onRetry: () -> Unit) {
    val thumb = rememberLocalThumb(o.uri, o.kind)
    val bad = o.phase == "err"
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
        Column(Modifier.widthIn(min = 200.dp, max = 280.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp))
            .background(if (bad) Red else Mine).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillWidth)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(iconFor(o.kind), fontSize = 26.sp); Spacer(Modifier.width(8.dp))
                Text(o.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            }
            if (o.caption.isNotEmpty()) Text(o.caption, color = Color.White, fontSize = 14.sp)
            if (!bad) BarLoader(if (o.phase == "up") o.frac else null, Modifier.padding(horizontal = 2.dp), 5.dp, Color.White, Color(0x44FFFFFF))
            val label = when (o.phase) {
                "prep" -> "Preparando…"
                "up" -> "Subiendo ${(o.frac * 100).toInt()}%" +
                    (if (o.kind != "image" && o.size >= (1L shl 20)) "  ·  ${fmtBytes((o.size * o.frac).toLong())} / ${fmtBytes(o.size)}" else "")
                "send" -> "Enviando…"
                else -> o.err
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = Color(0xFFE0E8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (bad) {
                    Text("REINTENTAR", Modifier.clickable { onRetry() }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Text("QUITAR", Modifier.clickable { onCancel() }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                } else if (o.phase != "send") {
                    Text("✕", Modifier.clickable { onCancel() }.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
