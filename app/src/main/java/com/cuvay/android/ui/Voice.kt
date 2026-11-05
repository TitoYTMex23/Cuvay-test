package com.cuvay.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================================================
//  MENSAJES DE VOZ (estilo WhatsApp)
//  - Mantén pulsado el microfono para grabar, suelta para enviar, desliza a la izquierda para cancelar.
//  - Se envian como un audio normal del chat (kind "audio") con nombre voz_FECHA_Ns.m4a;
//    ese nombre es lo que hace que se vea como nota de voz y no como un audio cualquiera.
// =============================================================================

/** Solo suena una nota de voz a la vez: al dar play a otra, la anterior se pausa. */
object VoicePlayback { var pauseCurrent: (() -> Unit)? = null }

class VoiceRec(private val ctx: Context) {
    var active by mutableStateOf(false)
    var ms by mutableStateOf(0L)
    var slideCancel by mutableStateOf(false)
    private var mr: MediaRecorder? = null
    private var file: File? = null
    private var t0 = 0L

    fun tick() { if (active) ms = System.currentTimeMillis() - t0 }

    fun start(): Boolean {
        VoicePlayback.pauseCurrent?.invoke()
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val f = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(48000)      // voz clara y liviana (~350 KB por minuto)
            r.setMaxDuration(10 * 60 * 1000)
            r.setOutputFile(f.path)
            r.prepare(); r.start()
            mr = r; file = f; t0 = System.currentTimeMillis(); ms = 0; slideCancel = false; active = true
            true
        } catch (e: Exception) {
            runCatching { r.release() }; f.delete(); false
        }
    }

    /** Termina la grabacion. Devuelve (archivo, segundos) o null si se cancelo o fue demasiado corta. */
    fun finish(send: Boolean): Pair<File, Int>? {
        val r = mr; val f = file
        val dur = System.currentTimeMillis() - t0
        mr = null; file = null; active = false; slideCancel = false
        var ok = true
        if (r != null) { ok = runCatching { r.stop() }.isSuccess; runCatching { r.release() } }
        if (f == null) return null
        if (!send || !ok || dur < 800) { f.delete(); return null }
        return f to ((dur + 500) / 1000).toInt().coerceAtLeast(1)
    }
}

@Composable
fun rememberVoiceRec(): VoiceRec {
    val ctx = LocalContext.current
    val rec = remember { VoiceRec(ctx) }
    LaunchedEffect(rec.active) { while (rec.active) { rec.tick(); delay(100) } }
    DisposableEffect(Unit) { onDispose { if (rec.active) rec.finish(false) } }
    return rec
}

/** Sube la nota de voz y la manda como mensaje de audio. Bloqueante: llamar desde IO. */
fun ChatApi.sendVoice(room: String, f: File, sec: Int, reply: Long): CMsg {
    val name = "voz_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_${sec}s.m4a"
    val c = URL(root + "/api/upload").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 120000
    c.setRequestProperty("X-Token", token)
    c.setRequestProperty("Content-Type", "audio/mp4")
    c.setRequestProperty("X-Filename", URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
    c.setFixedLengthStreamingMode(f.length())
    f.inputStream().use { i -> c.outputStream.use { o -> i.copyTo(o, 32 * 1024) } }
    val code = c.responseCode
    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
    val j = runCatching { JSONObject(txt) }.getOrDefault(JSONObject())
    if (code !in 200..299) throw ChatError(j.optString("error", "error"), j.optString("error", "Error $code"))
    return sendMedia(room, "audio", j.optString("id"), "", reply)
}

// ---------------------------------------------------------------------------
//  Boton de microfono + barra de grabacion
// ---------------------------------------------------------------------------
@Composable
fun MicButton(rec: VoiceRec, busy: Boolean, onSend: (File, Int) -> Unit, onNote: (String) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val send by rememberUpdatedState(onSend)
    val note by rememberUpdatedState(onNote)
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        note(if (ok) "Listo. Mantén pulsado el micrófono para grabar." else "Sin permiso de micrófono no se pueden grabar notas de voz.")
    }
    val scale by animateFloatAsState(if (rec.active) 1.35f else 1f, label = "mic")
    val bg = if (rec.active && rec.slideCancel) Color(0xFF55607A) else Red

    Box(Modifier.size(48.dp).scale(scale).clip(CircleShape).background(bg)
        .pointerInput(busy) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (busy) { waitForUpOrCancellation(); return@awaitEachGesture }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    perm.launch(Manifest.permission.RECORD_AUDIO)
                    waitForUpOrCancellation(); return@awaitEachGesture
                }
                if (!rec.start()) { note("No se pudo iniciar la grabación"); waitForUpOrCancellation(); return@awaitEachGesture }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val limit = 110.dp.toPx()
                var cancel = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    cancel = (ch.position.x - down.position.x) < -limit
                    rec.slideCancel = cancel
                    if (!ch.pressed) break
                    ch.consume()
                }
                val res = rec.finish(!cancel)
                if (res != null) send(res.first, res.second)
                else if (!cancel) note("Mantén pulsado para grabar (muy corto)")
            }
        }, contentAlignment = Alignment.Center) {
        Text(if (busy) "…" else "🎤", color = Color.White, fontSize = 22.sp)
    }
}

/** Reemplaza la barra de escribir mientras grabas: punto rojo parpadeando, tiempo y aviso de cancelar. */
@Composable
fun RecordingBar(rec: VoiceRec, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    Row(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(Red.copy(alpha = a)))
        Spacer(Modifier.width(10.dp))
        Text(fmtDur(rec.ms), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(if (rec.slideCancel) "Suelta para cancelar" else "‹  Desliza para cancelar",
            color = if (rec.slideCancel) Color(0xFFFF8A95) else Soft, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
//  Burbuja de nota de voz: play, ondas con progreso, tiempo y velocidad
// ---------------------------------------------------------------------------
private fun waveform(seed: String, n: Int = 32): List<Float> {
    var h = seed.hashCode().toLong() and 0xffffffffL
    return List(n) { h = (h * 1103515245L + 12345L) and 0x7fffffffL; 0.22f + (h % 1000) / 1000f * 0.78f }
}

@Composable
fun VoiceBubble(url: String, name: String, mine: Boolean) {
    val total0 = remember(name) { (Regex("_(\\d+)s\\.").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0) * 1000 }
    var mp by remember(url) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var pos by remember(url) { mutableStateOf(0) }
    var dur by remember(url) { mutableStateOf(total0) }
    var speed by remember(url) { mutableStateOf(1f) }
    val bars = remember(url) { waveform(url) }
    DisposableEffect(url) { onDispose { runCatching { mp?.release() } } }
    LaunchedEffect(playing) {
        while (playing) { pos = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(pos); delay(80) }
    }

    fun applySpeed(p: MediaPlayer) {
        if (Build.VERSION.SDK_INT >= 23) runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }
    }
    fun toggle() {
        val p = mp
        if (p != null && playing) { p.pause(); playing = false; return }
        VoicePlayback.pauseCurrent?.invoke()
        VoicePlayback.pauseCurrent = { runCatching { mp?.pause() }; playing = false }
        failed = false
        if (p == null) {
            loading = true
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                setOnPreparedListener { q -> loading = false; dur = q.duration.coerceAtLeast(1); q.start(); playing = true; applySpeed(q) }
                setOnCompletionListener { playing = false; pos = 0 }
                setOnErrorListener { _, _, _ -> loading = false; playing = false; failed = true; mp = null; true }
                runCatching { setDataSource(url); prepareAsync() }.onFailure { loading = false; failed = true }
            }
        } else { p.start(); playing = true; applySpeed(p) }
    }

    val strong = if (mine) Color.White else Blue
    val weak = if (mine) Color(0x73FFFFFF) else Color(0xFFCBD3E6)
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Row(Modifier.widthIn(min = 230.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(strong).clickable { toggle() }, contentAlignment = Alignment.Center) {
            Text(if (loading) "…" else if (playing) "❚❚" else "▶", color = if (mine) Blue else Color.White,
                fontSize = if (playing) 14.sp else 18.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().height(30.dp).pointerInput(mp, dur) {
                detectTapGestures { o ->
                    val p = mp
                    if (p != null && !loading && dur > 0) { val t = (o.x / size.width * dur).toInt().coerceIn(0, dur); p.seekTo(t); pos = t }
                }
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                bars.forEachIndexed { i, h ->
                    Box(Modifier.weight(1f).height((30f * h).dp).clip(RoundedCornerShape(2.dp))
                        .background(if ((i + 1f) / bars.size <= frac + 0.001f && (playing || pos > 0)) strong else weak))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (failed) "No se pudo reproducir" else fmtDur((if (playing || pos > 0) pos else dur).toLong()),
                    color = if (failed) Color(0xFFFF8A95) else (if (mine) Color(0xFFBFD0FF) else Muted),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (playing || speed != 1f) Text(if (speed == 1f) "1x" else if (speed == 1.5f) "1.5x" else "2x",
                    Modifier.clip(RoundedCornerShape(8.dp)).background(weak).clickable {
                        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
                        mp?.let { if (playing) applySpeed(it) }
                    }.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = if (mine) Color.White else Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
