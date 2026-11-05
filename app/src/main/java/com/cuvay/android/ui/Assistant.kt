package com.cuvay.android.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

// ---- IA real (Gemini via tu servidor cuvi-ai). El mismo valor que CUVI_APP_TOKEN en /etc/cuvi-ai.env ----
internal const val AI_TOKEN = "eb92fbf02a29e267057b381021fd743777add8bd74d6a8fb"

private data class AiMsg(val mine: Boolean, val text: String)

/** Llama a POST {PANEL}/api/ai/ask. Corre en hilo IO. La key de Gemini nunca esta en la app. */
private fun askCuvi(ctx: Context, q: String, screen: String, hist: List<AiMsg>): String {
    val h = JSONArray()
    hist.takeLast(6).forEach { h.put(JSONObject().put("r", if (it.mine) "u" else "b").put("t", it.text.take(500))) }
    val body = JSONObject().put("q", q).put("screen", screen).put("hist", h).toString()
    val c = URL("$PANEL/api/ai/ask").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 45000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Cuvi-Token", AI_TOKEN)
        c.setRequestProperty("X-Device", deviceId(ctx))
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode != 200) error("http ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("a").ifBlank { "No supe qué responder 😅" }
    } finally { c.disconnect() }
}

// =====================================================================
//  MOTOR DE AUDIO TODOTERRENO (STREAM_MUSIC + PCM + FALLBACK + TTS)
//  Suena en cualquier móvil y emulador usando el volumen multimedia.
// =====================================================================

private enum class SoundFx {
    // Sonidos base
    KICK, SPARKLE, JUMP, FIESTA, POP,
    // Nuevos sonidos propios sintetizados
    LASER_DECO, DOMINO_SLAM, GUITAR_CHORD, SALSABEAT,
    COFFEE_SIP, BOXING_BELL, DJ_SCRATCH, FIREWORK_BOOM,
    CHEER_FANFARE, AMBIENT_CHIME, CLAVES
}

private class CuviAudioEngine(context: Context) {
    @JvmField var enabled: Boolean = true
    @JvmField var musicEnabled: Boolean = false
    private val appCtx = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var musicJob: Job? = null

    private val mediaAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    init {
        runCatching {
            // Si el volumen multimedia del teléfono está en 0, lo sube suavemente a un nivel audible
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (cur == 0 && max > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.45f).toInt().coerceAtLeast(1), 0)
                }
            }
        }
        runCatching {
            tts = TextToSpeech(appCtx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { tts?.setAudioAttributes(mediaAttrs) }
                    val candidates = listOf(
                        Locale("es", "CU"),
                        Locale("es", "US"),
                        Locale("es", "MX"),
                        Locale("es", "ES"),
                        Locale("es"),
                        Locale.getDefault()
                    )
                    for (loc in candidates) {
                        val r = tts?.setLanguage(loc)
                        if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                            break
                        }
                    }
                    tts?.setPitch(1.28f)      // Voz simpática de robotito cubano
                    tts?.setSpeechRate(1.06f)
                    ttsReady = true
                    pendingSpeech?.let { msg ->
                        pendingSpeech = null
                        speak(msg)
                    }
                }
            }
        }
    }

    fun speak(text: String) {
        if (!enabled) return
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        runCatching {
            val clean = text.replace(Regex("""[^\p{L}\p{N}\s¡!¿?,.:]"""), "")
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "cuvi_${System.currentTimeMillis()}")
        }
    }

    fun toggleMusicLoop(active: Boolean) {
        musicEnabled = active
        musicJob?.cancel()
        if (active && enabled) {
            musicJob = audioScope.launch {
                val pattern = listOf(
                    SoundFx.SALSABEAT,
                    SoundFx.AMBIENT_CHIME,
                    SoundFx.GUITAR_CHORD,
                    SoundFx.FIESTA
                )
                var step = 0
                while (isActive && musicEnabled && enabled) {
                    play(pattern[step % pattern.size])
                    step++
                    delay(2600)
                }
            }
        }
    }

    fun play(fx: SoundFx) {
        if (!enabled) return
        audioScope.launch {
            val ok = runCatching {
                val sampleRate = 22050
                val samples: ShortArray = when (fx) {
                    SoundFx.KICK -> synthSweep(sampleRate, 190.0, 48.0, 160)
                    SoundFx.JUMP -> synthSweep(sampleRate, 260.0, 820.0, 200)
                    SoundFx.POP -> synthSweep(sampleRate, 540.0, 1020.0, 85)
                    SoundFx.SPARKLE -> synthArpeggio(sampleRate, doubleArrayOf(523.25, 659.25, 783.99, 1046.50), 70)
                    SoundFx.FIESTA -> synthArpeggio(sampleRate, doubleArrayOf(440.0, 554.37, 659.25, 880.0, 659.25), 80)
                    SoundFx.LASER_DECO -> synthFmLaser(sampleRate, 920.0, 240.0, 210)
                    SoundFx.DOMINO_SLAM -> synthDominoCapicua(sampleRate)
                    SoundFx.GUITAR_CHORD -> synthCubanTresChord(sampleRate, doubleArrayOf(261.63, 329.63, 392.00, 523.25, 659.25), 75)
                    SoundFx.SALSABEAT -> synthArpeggio(sampleRate, doubleArrayOf(196.0, 261.63, 329.63, 392.0, 329.63, 261.63), 65)
                    SoundFx.COFFEE_SIP -> synthArpeggio(sampleRate, doubleArrayOf(349.23, 440.0, 523.25, 698.46, 880.0, 1046.50), 52)
                    SoundFx.BOXING_BELL -> synthBellAndPunch(sampleRate)
                    SoundFx.DJ_SCRATCH -> synthDjScratch(sampleRate)
                    SoundFx.FIREWORK_BOOM -> synthFirework(sampleRate)
                    SoundFx.CHEER_FANFARE -> synthArpeggio(sampleRate, doubleArrayOf(523.25, 523.25, 523.25, 659.25, 783.99, 1046.50), 85)
                    SoundFx.AMBIENT_CHIME -> synthArpeggio(sampleRate, doubleArrayOf(659.25, 783.99, 987.77, 1318.51), 90)
                    SoundFx.CLAVES -> {   // clave de son 3-2: cinco toquecitos de madera
                        val click = synthSweep(sampleRate, 2500.0, 2050.0, 42)
                        val unit = sampleRate * 75 / 1000
                        val total = unit * 12 + click.size
                        val out = ShortArray(total)
                        intArrayOf(0, 3, 6, 10, 12).forEach { pos ->
                            val st = pos * unit
                            for (k in click.indices) if (st + k < total) out[st + k] = click[k]
                        }
                        out
                    }
                }
                playPcmTodoterreno(sampleRate, samples)
            }.getOrDefault(false)

            // Fallback de emergencia con ToneGenerator si el hardware bloquea PCM
            if (!ok) {
                runCatching {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    delay(140)
                    tg.release()
                }
            }
        }
    }

    private fun synthSweep(sr: Int, fStart: Double, fEnd: Double, durationMs: Int): ShortArray {
        val count = (sr * durationMs) / 1000
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val prog = i.toDouble() / count
            val freq = fStart + (fEnd - fStart) * prog
            val env = sin(prog * PI) * (1.0 - prog * 0.4)
            phase += 2.0 * PI * freq / sr
            val wave = sin(phase) * 0.8 + sin(phase * 2.0) * 0.2
            out[i] = (wave * env * 19500).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun synthArpeggio(sr: Int, notes: DoubleArray, noteMs: Int): ShortArray {
        val perNote = (sr * noteMs) / 1000
        val out = ShortArray(perNote * notes.size)
        var idx = 0
        for (freq in notes) {
            var phase = 0.0
            for (i in 0 until perNote) {
                val prog = i.toDouble() / perNote
                val env = (1.0 - prog).pow(1.25)
                phase += 2.0 * PI * freq / sr
                val wave = (sin(phase) * 0.72 + sin(phase * 2.0) * 0.28)
                out[idx++] = (wave * env * 18500).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    private fun synthFmLaser(sr: Int, fStart: Double, fEnd: Double, durationMs: Int): ShortArray {
        val count = (sr * durationMs) / 1000
        val out = ShortArray(count)
        var phase = 0.0
        var modPhase = 0.0
        for (i in 0 until count) {
            val prog = i.toDouble() / count
            val baseFreq = fStart + (fEnd - fStart) * prog
            modPhase += 2.0 * PI * 38.0 / sr
            val freq = baseFreq + sin(modPhase) * 140.0 * (1.0 - prog)
            val env = (1.0 - prog).pow(1.1)
            phase += 2.0 * PI * freq / sr
            val cristal = sin(phase) * 0.7 + sin(phase * 2.5) * 0.3
            out[i] = (cristal * env * 18500).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun synthDominoCapicua(sr: Int): ShortArray {
        val slamSamples = (sr * 85) / 1000
        val chord = synthArpeggio(sr, doubleArrayOf(587.33, 739.99, 880.0, 1174.66), 55)
        val out = ShortArray(slamSamples + chord.size)
        var phase = 0.0
        for (i in 0 until slamSamples) {
            val prog = i.toDouble() / slamSamples
            val env = (1.0 - prog).pow(2.2)
            phase += 2.0 * PI * (310.0 - 210.0 * prog) / sr
            val click = (Random.nextDouble() * 2.0 - 1.0) * 0.55 + sin(phase) * 0.45
            out[i] = (click * env * 22000).toInt().coerceIn(-32767, 32767).toShort()
        }
        System.arraycopy(chord, 0, out, slamSamples, chord.size)
        return out
    }

    private fun synthCubanTresChord(sr: Int, freqs: DoubleArray, strumGapMs: Int): ShortArray {
        val totalMs = 380
        val count = (sr * totalMs) / 1000
        val out = ShortArray(count)
        val gapSamples = (sr * strumGapMs) / 1000
        freqs.forEachIndexed { nIdx, freq ->
            val start = (nIdx * gapSamples / 2).coerceAtMost(count - 1)
            var phase = 0.0
            for (i in start until count) {
                val localProg = (i - start).toDouble() / (count - start).coerceAtLeast(1)
                val env = (1.0 - localProg).pow(1.6)
                phase += 2.0 * PI * freq / sr
                val pluck = sin(phase) * 0.55 + sin(phase * 2.003) * 0.30 + sin(phase * 3.0) * 0.15
                val prev = out[i].toInt()
                out[i] = (prev + (pluck * env * 7500).toInt()).coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    private fun synthBellAndPunch(sr: Int): ShortArray {
        val count = (sr * 320) / 1000
        val out = ShortArray(count)
        var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
        for (i in 0 until count) {
            val prog = i.toDouble() / count
            val env = (1.0 - prog).pow(1.7)
            p1 += 2.0 * PI * 820.0 / sr
            p2 += 2.0 * PI * 1345.0 / sr
            p3 += 2.0 * PI * 2120.0 / sr
            val bell = (sin(p1) * 0.5 + sin(p2) * 0.3 + sin(p3) * 0.2) * env
            out[i] = (bell * 20000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun synthDjScratch(sr: Int): ShortArray {
        val count = (sr * 260) / 1000
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val prog = i.toDouble() / count
            val freq = if (prog < 0.45) 280.0 + prog * 1450.0 else 930.0 - (prog - 0.45) * 1200.0
            val env = sin(prog * PI)
            phase += 2.0 * PI * freq / sr
            val w = sin(phase) * 0.8 + sin(phase * 3.0) * 0.2
            out[i] = (w * env * 19500).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun synthFirework(sr: Int): ShortArray {
        val whistleCount = (sr * 160) / 1000
        val boomCount = (sr * 220) / 1000
        val out = ShortArray(whistleCount + boomCount)
        var phase = 0.0
        for (i in 0 until whistleCount) {
            val prog = i.toDouble() / whistleCount
            val freq = 380.0 + prog * 1100.0
            phase += 2.0 * PI * freq / sr
            out[i] = (sin(phase) * 15000).toInt().toShort()
        }
        var pSpark = 0.0
        for (i in 0 until boomCount) {
            val prog = i.toDouble() / boomCount
            val env = (1.0 - prog).pow(1.5)
            pSpark += 2.0 * PI * (1200.0 - prog * 700.0) / sr
            val noise = (Random.nextDouble() * 2.0 - 1.0) * 0.55 + sin(pSpark) * 0.45
            out[whistleCount + i] = (noise * env * 20500).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun playPcmTodoterreno(sampleRate: Int, buffer: ShortArray): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val byteCount = maxOf(buffer.size * 2, minBuf)

        val track = AudioTrack.Builder()
            .setAudioAttributes(mediaAttrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(byteCount)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return false
        }
        track.play()
        track.write(buffer, 0, buffer.size)
        Thread.sleep((buffer.size * 1000L / sampleRate) + 120L)
        runCatching { track.stop() }
        track.release()
        return true
    }

    fun release() {
        runCatching {
            musicJob?.cancel()
            tts?.stop()
            tts?.shutdown()
        }
    }
}

@Composable
private fun AiChatView(tab: Tab, inChannel: Boolean, msgs: MutableList<AiMsg>, audio: CuviAudioEngine, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) }        // tono: 0 normal, 1 bromista, 2 profe, 3 cortito
    var typingIdx by remember { mutableIntStateOf(-1) }  // respuesta de Cuvi que se está "escribiendo"
    var shown by remember { mutableIntStateOf(0) }
    val modeLabels = remember { listOf("😎 Normal", "😂 Bromista", "📚 Profe", "⚡ Cortito") }
    val modeColors = remember { listOf(CbAzulVivo, CbOro, CbPalma, CbRojo) }
    val cuviShape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    val meShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)

    // Efecto de máquina de escribir en la respuesta nueva (tócala para verla completa)
    LaunchedEffect(typingIdx) {
        if (typingIdx in msgs.indices) {
            val len = msgs[typingIdx].text.length
            val step = if (len > 500) 4 else 2
            shown = 0
            while (shown < len && typingIdx >= 0) {
                shown = minOf(len, shown + step)
                delay(24)
            }
            typingIdx = -1
        }
    }
    LaunchedEffect(msgs.size, busy, shown / 8) { scroll.scrollTo(scroll.maxValue) }

    fun send(preset: String? = null) {
        val q = (preset ?: input).trim()
        if (q.isEmpty() || busy) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        audio.play(SoundFx.POP)
        val before = msgs.toList()
        msgs.add(AiMsg(true, q)); input = ""; busy = true; typingIdx = -1
        val screen = if (inChannel) "Canal" else tab.name
        // El tono se le pide a Cuvi dentro de la pregunta; en el historial queda solo lo que escribiste
        val asked = when (mode) {
            1 -> "(Responde con humor cubano, bromista y cortito) $q"
            2 -> "(Explícame como un profe paciente, paso a paso y claro) $q"
            3 -> "(Responde en una o dos frases cortas) $q"
            else -> q
        }
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                runCatching { askCuvi(ctx, asked, screen, before) }
                    .getOrElse { "No pude conectarme 😕. Revisa tu conexión (esta función gasta datos) e intenta otra vez." }
            }
            msgs.add(AiMsg(false, a)); busy = false
            typingIdx = msgs.lastIndex
            audio.play(SoundFx.SPARKLE)
            audio.speak(cbSpeakable(a))
        }
    }

    fun retry() {
        if (busy) return
        val lastQ = msgs.lastOrNull { it.mine }?.text ?: return
        if (msgs.isNotEmpty() && !msgs.last().mine) msgs.removeAt(msgs.lastIndex)
        val idx = msgs.indexOfLast { it.mine }
        if (idx >= 0) msgs.removeAt(idx)
        send(lastQ)
    }

    fun copyText(t: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Cuvi", t))
        }
        audio.play(SoundFx.POP)
    }

    Column {
        // ---------- Barra superior ----------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CbIconToggle("‹", false, CbOro, 32.dp, 20.sp) {
                audio.play(SoundFx.POP)
                onBack()
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Charla con Cuvi", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = Display)
                Text(
                    if (busy) "pensando…" else "en línea · usa internet",
                    color = if (busy) CbOro else CbPalma, fontSize = 10.sp
                )
            }
            CbPill(modeLabels[mode], true, modeColors[mode], small = true) {
                mode = (mode + 1) % 4
                audio.play(SoundFx.POP)
            }
            if (msgs.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                CbIconToggle("🧹", false, CbOro, 32.dp, 13.sp) {
                    if (!busy) {
                        msgs.clear()
                        typingIdx = -1
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        CbFlagLine(Modifier, 3.dp)
        Spacer(Modifier.height(8.dp))

        // ---------- Conversación ----------
        Column(Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 240.dp).verticalScroll(scroll)) {
            if (msgs.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, CbOro.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(12.dp)
                ) {
                    CbStarBadge(34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("¡Qué bolá, asere! Soy Cuvi 🇨🇺", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "Tu asistente cubana con IA. Pregúntame de la app o toca una sugerencia. " +
                                "Uso internet, así que gasto datos.",
                            color = CbTexto2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
            msgs.forEachIndexed { i, m ->
                if (m.mine) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            m.text,
                            Modifier.widthIn(max = 250.dp).clip(meShape)
                                .background(Brush.linearGradient(listOf(CbAzul, Color(0xFF1D52DB))))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = Color.White, fontSize = 13.sp, lineHeight = 18.sp
                        )
                    }
                } else {
                    val typing = i == typingIdx
                    val vis = if (typing) m.text.take(shown) else m.text
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        CbStarBadge(26.dp)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                cbMarkdown(vis, CbRojo, Color(0x1F000000)),
                                Modifier.widthIn(max = 246.dp).clip(cuviShape)
                                    .background(Brush.linearGradient(listOf(CbCrema, Color(0xFFFFE9BF))))
                                    .border(1.dp, CbOro, cuviShape)
                                    .clickable {
                                        if (typing) {
                                            typingIdx = -1
                                        } else {
                                            audio.speak(cbSpeakable(m.text))
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                color = CbNoche, fontSize = 13.sp, lineHeight = 19.sp
                            )
                            if (i == msgs.lastIndex && !busy && !typing) {
                                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    CbPill("🔊 Escuchar", false, CbOro, small = true) { audio.speak(cbSpeakable(m.text)) }
                                    CbPill("📋 Copiar", false, CbAzulVivo, small = true) { copyText(m.text) }
                                    CbPill("🔁 Otra vez", false, CbPalma, small = true) { retry() }
                                }
                            }
                        }
                    }
                }
            }
            if (busy) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CbStarBadge(26.dp)
                    Spacer(Modifier.width(6.dp))
                    Row(
                        Modifier.clip(cuviShape).background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CbTypingDots(CbOro)
                        Spacer(Modifier.width(8.dp))
                        Text("Cuvi está pensando", color = CbTexto2, fontSize = 11.sp)
                    }
                }
            }
        }

        // ---------- Sugerencias ----------
        val chips = remember(tab, inChannel) { aiSuggestions(tab, inChannel) }
        if (msgs.isEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                chips.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { sg -> CbPill(sg, false, CbOro, Modifier.weight(1f)) { send(sg) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chips.forEach { sg -> CbPill(sg, false, CbOro, small = true) { send(sg) } }
            }
        }

        // ---------- Escribir ----------
        val inShape = RoundedCornerShape(20.dp)
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(inShape)
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.5.dp, if (focused) CbOro else Color.White.copy(alpha = 0.14f), inShape)
                .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (input.isEmpty()) Text("Escríbele a Cuvi…", color = Color(0xFF6F82AD), fontSize = 13.sp)
                BasicTextField(
                    input, { input = it.take(500) },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(CbOro), maxLines = 3
                )
            }
            CbSendButton(enabled = !busy && input.isNotBlank()) { send() }
        }
    }
}

// =====================================================================
//  Cuvi 3D Fashion Cubano + Mascota IK + Decorador Autónomo 3D Total
// =====================================================================

private val BotNavy = Color(0xFF0B1330)
private val BotPanel = Color(0xFF111C3A)
private val BotCyan = Color(0xFF3DE0FF)
private val BotGreen = Color(0xFF2EE6A6)
private val CubaBlue = Color(0xFF002A8F)
private val CubaRed = Color(0xFFCF142B)
private val CubaGold = Color(0xFFFFD166)
private val NeonPink = Color(0xFFFF499E)
private val NeonViolet = Color(0xFF8C52FF)

enum class CuviAvatarStyle(val label: String) {
    AUTO_DUO("🎭 Dúo 3D"),
    CYBER_3D("🤖 Cyber 3D"),
    CLASSIC_IK("🕺 Clásico IK")
}

enum class CuviAction(
    val label: String,
    val bubble: String,
    val voice: String,
    val banner3D: String
) {
    DECORATE("✨ Decorar", "¡Poniendo la app bien linda en 3D! ✨🇨🇺", "Mira cómo te decoro la aplicación en tres de", "¡ARTE 3D!"),
    SOCCER("⚽ Fútbol", "¡Dominando el balón a lo campeón! ⚽🔥", "¡Toma golazo cubano!", "¡GOOOL! ⚽"),
    DOMINO_CAPICUA("🁣 Capicúa", "¡Me pegué con doble nueve, Capicúa! 🁣🔥", "¡Agua, que me pegué con capicúa asere!", "¡CAPICÚA!"),
    TRES_CUBANO("🎸 Tres 3D", "¡Sonando el Tres Cubano con sabrosura! 🎸🎶", "Escucha cómo suena este tres cubano", "¡AZÚCAR! 🎶"),
    CAFECITO_TURBO("☕ Cafecito", "¡Un cafecito cubano bien cargado y espumoso! ☕⚡", "Un cafecito bien fuerte para activar el turbo", "¡TURBO CAFÉ!"),
    DJ_FIESTA("🎧 DJ 3D", "¡Mezclando reparto y son en la consola 3D! 🎧💿", "Tremendo piquete en la consola tres de", "¡DJ CUVI!"),
    ALMENDRON_3D("🚗 Almendrón", "¡Paseando por la app en mi Almendrón 3D! 🚗💨", "Dando un voltio en el almendrón volador", "¡ALMENDRÓN!"),
    BOXEO_CUBANO("🥊 Boxeo", "¡Tirando combinaciones olímpicas en 3D! 🥊🥇", "Con la escuela cubana de boxeo, campeón", "¡CAMPEÓN! 🥊"),
    FIREWORKS_3D("🎆 Fuegos 3D", "¡Iluminando toda la pantalla con fuegos 3D! 🎆✨", "Tremendo espectáculo de luces por toda la pantalla", "¡FIESTA 3D!"),
    HEAD_HAND("🤔 Cabeza", "¡Chacho, qué invento más duro! 🤔😂", "Chacho, qué invento más duro asere", "¡QUÉ DURO!"),
    JUMP_SPIN("🤸 Brinco 3D", "¡Súper salto acrobático 3D a otro nivel! 🚀", "Súper salto acrobático en tres de", "¡GIRO 360°!"),
    WALK("🚶 Pasear", "Dando una vueltecita por la app… 👟", "Dando una vueltecita por aquí", "¡PASEANDO!"),
    DANCE("🕺 Fiesta", "¡Con tremendo piquete cubano! 🎶🕺", "Con tremendo piquete cubano", "¡SABROSURA!"),
    IDLE("🇨🇺 Saludo", "¡Qué bolá, asere! 🇨🇺", "Qué bolá asere", "¡QUÉ BOLÁ! 🇨🇺")
}

private enum class FxKind {
    SOCCER_BALL, CUBA_STAR, CUBA_FLAG, NEON_ORB, MUSIC_NOTE, RING_3D, CRYSTAL_3D,
    DOMINO_TILE, COFFEE_CUP, FIREWORK_SPARK, HEART_GEM, LIGHTNING_BOLT, VINYL_DISC, BOXING_GLOVE
}

/** 13 Decoraciones persistentes 3D que Cuvi va instalando por los bordes, esquinas y fondo de la app */
private enum class DecoType {
    GEM_3D, PALM_3D, BUBBLE_3D, STAR_EMBLEM, NEON_FLOWER,
    CUBE_HOLO_3D, PYRAMID_PRISM_3D, TORUS_PORTAL_3D, DOMINO_3D,
    ALMENDRON_NEON_3D, MARACAS_3D, CROWN_3D, HEART_3D
}

private class AmbientDeco3D(
    val xFrac: Float,
    val yFrac: Float,
    val type: DecoType,
    val color: Color,
    val scale: Float,
    val phaseOffset: Float,
    @JvmField var spawnProgress: Float = 0f
)

private class LaserBeam3D(
    val from: Offset,
    val to: Offset,
    val color: Color,
    @JvmField var life: Float = 1f
)

private class ShockwaveRing3D(
    val center: Offset,
    val color: Color,
    val maxRadius: Float,
    @JvmField var progress: Float = 0f
)

private class TrailOrb3D(
    val pos: Offset,
    val color: Color,
    @JvmField var life: Float = 1f
)

private class Particle3D(
    @JvmField var x: Float,
    @JvmField var y: Float,
    @JvmField var vx: Float,
    @JvmField var vy: Float,
    @JvmField var z: Float,
    @JvmField var vz: Float,
    @JvmField var rot: Float,
    @JvmField var vRot: Float,
    @JvmField var tilt3D: Float,
    @JvmField var vTilt: Float,
    val kind: FxKind,
    val color: Color,
    @JvmField var life: Float = 1f,
    val decay: Float = 0.008f
)

private data class Help(val title: String, val body: String)

private fun screenHelp(tab: Tab, inChannel: Boolean): Help = when {
    inChannel -> Help(
        "Dentro de un canal",
        "Aquí ves las publicaciones del canal.\n\n" +
                "• ARCHIVO / VIDEO / AUDIO: toca DESCARGAR y la app te pregunta por qué vía bajarlo.\n" +
                "• Las fotos se bajan solas (o al tocarlas, según el botón FOTOS).\n" +
                "• Toca una foto para verla grande; pellizca para acercar.\n" +
                "• Con la flecha ‹ de arriba vuelves a la lista."
    )
    tab == Tab.Feed -> Help(
        "Feed",
        "Esta es la pantalla principal: la lista de canales y las publicaciones.\n\n" +
                "• ACTUALIZAR: busca publicaciones nuevas ahora.\n" +
                "• AUTO: SI: se actualiza solo cada tantos segundos.\n" +
                "• FOTOS: AUTO / AL TOCAR: si las fotos se bajan solas o cuando las tocas.\n" +
                "• FOTOS POR: eliges por qué vía se bajan las fotos.\n" +
                "• Toca un canal para abrirlo."
    )
    tab == Tab.Mirror -> Help(
        "Fuentes",
        "Aquí manejas de dónde sale el contenido (canales y espejos).\n\n" +
                "Si no ves publicaciones en el Feed, revisa que tengas fuentes puestas aquí."
    )
    tab == Tab.Chat -> Help(
        "Chat",
        "Mensajería de la comunidad.\n\n" +
                "• ONLINE: chat por internet con usuario y clave; puedes editar, borrar y anclar tus mensajes.\n" +
                "• OFFLINE: chat por DNS, sin gastar datos. Los mensajes llegan despacio, cada pocos minutos."
    )
    tab == Tab.Resolver -> Help(
        "Resolvers",
        "Los resolvers son los servidores DNS por los que sale la conexión.\n\n" +
                "• Puedes seleccionar varios a la vez.\n" +
                "• Los que guardes se quedan guardados.\n" +
                "• Si el feed se queda en «Conectando», prueba con otros resolvers."
    )
    tab == Tab.Red -> Help(
        "Red",
        "Estado de la conexión de la app.\n\n" +
                "Si algo no carga, mira aquí primero si hay conexión."
    )
    else -> Help(
        "Ajustes",
        "Aquí cambias cómo funciona la app.\n\n" +
                "• La vía de descarga habitual.\n" +
                "• Si la app pregunta antes de descargar.\n" +
                "• «Detrás de TitoMX»: quién hizo la app."
    )
}

private val topics = listOf(
    Help(
        "Descargar archivos",
        "1. Toca DESCARGAR en el archivo.\n" +
                "2. Elige la vía:\n" +
                "   • TURBO: rápida, gasta datos.\n" +
                "   • NUBE: rápida, para archivos pequeños, gasta datos.\n" +
                "   • LENTA (DNS): no gasta datos, pero va muy despacio.\n" +
                "   • AUTOMÁTICO: la app elige por ti.\n" +
                "3. Si una vía falla, la app te propone otra."
    ),
    Help(
        "Fotos",
        "El botón «FOTOS POR» del Feed decide cómo se bajan las fotos.\n\n" +
                "Sin megas, elige LENTA: llega despacio, pero no gasta datos.\n" +
                "Si una foto falla, sale un recuadro amarillo para probar otra vía."
    ),
    Help(
        "Sin datos",
        "Si no tienes megas:\n" +
                "• Pon FOTOS POR en LENTA.\n" +
                "• Al descargar un archivo elige LENTA.\n" +
                "• El Chat OFFLINE funciona por DNS."
    ),
    Help(
        "Si algo falla",
        "• «No se pudo bajar»: toca para reintentar o elige otra vía.\n" +
                "• Se queda en «Conectando»: revisa Resolvers.\n" +
                "• La foto no sale: toca el recuadro para ver el aviso de otra vía."
    )
)

// ---- Adornos cubanos ORDENADOS + Cuvi IA con más gracia ----
private val RailTypes = listOf(DecoType.PALM_3D, DecoType.STAR_EMBLEM, DecoType.ALMENDRON_NEON_3D, DecoType.MARACAS_3D, DecoType.DOMINO_3D)
private val RailColors = listOf(BotGreen, CubaGold, BotCyan, CubaRed, Color.White)

private val cubanLines = listOf(
    "¡Qué bolá, asere! 🇨🇺", "Un cafecito y seguimos ☕", "Más cubano que la palma real 🌴",
    "Toca a Cuvi y conversamos 💬", "Cuba linda, siempre en el corazón ❤️",
    "Asere = amigo. ¡Ya aprendiste una! 🤝", "¿Ya viste la palabra cubana del día? 🗣️",
    "Una descarga siempre cae bien 🎷", "Con el Malecón de fondo todo se ve mejor 🌊",
    "Pregúntame lo que quieras, que yo resuelvo 😉"
)

private val cubanDichos = listOf(
    "Más cubano que la palma real. 🌴",
    "A falta de pan, casabe.",
    "Al mal tiempo, buena cara. 😄",
    "Sin prisa pero sin pausa, como dice el guajiro. 🐢",
    "No hay mal que por bien no venga.",
    "El que no tiene de congo, tiene de carabalí.",
    "Un cafecito a tiempo lo arregla todo. ☕",
    "Honrar honra. — José Martí",
    "Ser culto es el único modo de ser libre. — José Martí",
    "Cultivo una rosa blanca, en julio como en enero, para el amigo sincero que me da su mano franca. — José Martí",
    "Camarón que se duerme, se lo lleva la corriente. 🦐",
    "Cuando el río suena, agua lleva. 🌊",
    "Hay que echar pa'lante, asere. 💪",
    "Con el café no se juega. ☕",
    "Aquí el que no corre, vuela. 🏃",
    "Del dicho al hecho hay un buen trecho."
)

private fun screenTips(tab: Tab, inChannel: Boolean): List<String> = when {
    inChannel -> listOf("Toca una foto para verla grande 🔍", "¿Sin megas? Descarga por LENTA 🐢", "Si una vía falla, prueba otra 🔁")
    tab == Tab.Feed -> listOf("Activa AUTO y se actualiza solo 🔄", "FOTOS: AL TOCAR te ahorra megas 📶", "Toca un canal para abrirlo 📂")
    tab == Tab.Mirror -> listOf("Si el Feed está vacío, revisa las fuentes 🧭")
    tab == Tab.Chat -> listOf("El chat OFFLINE no gasta datos 💬", "Puedes anclar tus mensajes 📌")
    tab == Tab.Resolver -> listOf("Puedes marcar varios resolvers 🧭", "Si dice «Conectando», prueba otros 🔌")
    tab == Tab.Red -> listOf("Si algo no carga, mira aquí primero 🌐")
    else -> listOf("Aquí eliges tu vía de descarga habitual ⚙️")
}

private fun aiSuggestions(tab: Tab, inChannel: Boolean): List<String> {
    val screen = when {
        inChannel -> listOf("¿Cómo bajo este archivo?", "Sin megas, ¿qué hago?")
        tab == Tab.Feed -> listOf("¿Qué vía de descarga es mejor?", "¿Cómo ahorro megas?")
        tab == Tab.Chat -> listOf("¿Qué es el chat OFFLINE?", "¿Cómo uso el chat?")
        tab == Tab.Resolver -> listOf("¿Qué son los resolvers?", "Se queda en Conectando")
        tab == Tab.Mirror -> listOf("¿Qué son las fuentes?", "No veo publicaciones")
        tab == Tab.Red -> listOf("¿Cómo sé si hay conexión?")
        else -> listOf("¿Qué puedo cambiar aquí?")
    }
    return screen + listOf("Cuéntame un chiste cubano 😄", "Dime un dicho cubano 🇨🇺")
}

private fun cubanGreeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h < 6 -> "¡Qué madrugada, asere! 🌙"
        h < 12 -> "¡Buenos días, mi gente! ☀️"
        h < 19 -> "¡Buenas tardes, asere! 🌴"
        else -> "¡Buenas noches, mi gente! 🌙"
    }
}

/** Franjita de la bandera (azul / blanco / rojo) que baja despacito por los dos bordes: queda fuera del contenido. */
private fun DrawScope.drawCubanEdgeBorder(w: Float, h: Float, timeSec: Float) {
    val bar = 3.dp.toPx()
    val seg = 28.dp.toPx()
    val colors = listOf(Color(0xFF3A6BFF), Color.White, CubaRed, Color.White)
    val n = (h / seg).toInt() + 2
    val shift = (timeSec * 10.dp.toPx()) % (seg * colors.size)
    for (i in -colors.size..n) {
        val y = i * seg + shift
        val c = colors[((i % colors.size) + colors.size) % colors.size].copy(alpha = 0.55f)
        drawRect(c, Offset(0f, y), Size(bar, seg))
        drawRect(c, Offset(w - bar, y), Size(bar, seg))
    }
}

// =====================================================================
//  ESCENA 3D "PRO": fondo con profundidad + capas de lujo (sin tapar texto)
// =====================================================================

/** Nivel de efectos 3D de toda la app: 0 = apagado, 1 = suave, 2 = completo. Se guarda en las preferencias. */
object CuvayFx {
    var level by mutableIntStateOf(2)
    private var loaded = false
    fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        level = ctx.getSharedPreferences("cuvay_ui", Context.MODE_PRIVATE).getInt("fx_level", 2).coerceIn(0, 2)
    }
    fun setLevel(ctx: Context, v: Int) {
        level = v.coerceIn(0, 2)
        ctx.getSharedPreferences("cuvay_ui", Context.MODE_PRIVATE).edit().putInt("fx_level", level).apply()
    }
}

private const val FlyPeriod = 18f
private val SceneColors = listOf(CubaGold, Color.White, CubaRed, Color(0xFF4F86FF), BotCyan, BotGreen)

/** Numero pseudo-aleatorio estable en [0,1): sirve para colocar cosas sin guardar estado. */
private fun hash01(a: Int): Float {
    val x = sin(a * 12.9898f) * 43758.547f
    return x - floor(x)
}

/** Aura dorada detras de Cuvi y un "escenario" en el piso con arcos que giran. */
private fun DrawScope.drawMascotStage(rect: androidx.compose.ui.geometry.Rect, timeSec: Float) {
    if (rect.width <= 1f) return
    val cx = rect.center.x
    val cy = rect.center.y
    val pulse = 0.85f + 0.15f * sin(timeSec * 2.4f)
    val r = rect.height * 0.62f * pulse
    drawCircle(
        Brush.radialGradient(listOf(CubaGold.copy(alpha = 0.26f), BotCyan.copy(alpha = 0.14f), Color.Transparent), center = Offset(cx, cy), radius = r),
        radius = r, center = Offset(cx, cy)
    )
    val gy = rect.bottom - rect.height * 0.06f
    val rw = rect.width * 0.46f
    val rh = rect.width * 0.12f
    drawOval(Color.Black.copy(alpha = 0.25f), topLeft = Offset(cx - rw, gy - rh), size = Size(rw * 2f, rh * 2f))
    for (k in 0 until 3) {
        drawArc(
            color = (if (k == 1) CubaRed else CubaGold).copy(alpha = 0.85f),
            startAngle = timeSec * 70f + k * 120f, sweepAngle = 52f, useCenter = false,
            topLeft = Offset(cx - rw, gy - rh), size = Size(rw * 2f, rh * 2f),
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/** Cintas de adornos cubanos que suben por la izquierda y bajan por la derecha, siempre alineadas en el margen. */
private fun DrawScope.drawEdgeConveyor(w: Float, topPx: Float, bottomPx: Float, timeSec: Float) {
    val gap = 70.dp.toPx()
    val span = bottomPx - topPx
    if (span < gap * 2f) return
    val n = (span / gap).toInt()
    val total = n * gap
    val r = 8.dp.toPx()
    for (side in 0..1) {
        val x = if (side == 0) 10.dp.toPx() else w - 10.dp.toPx()
        val dir = if (side == 0) -1f else 1f
        val off = timeSec * 8.dp.toPx() * dir
        for (i in 0 until n) {
            val pos = (((i * gap + off) % total) + total) % total
            val edge = min(pos, total - pos) / (gap * 0.9f)
            val a = (0.95f * edge).coerceIn(0f, 0.95f)
            if (a < 0.03f) continue
            val bob = sin(timeSec * 2f + i * 0.9f + side) * 1.5.dp.toPx()
            withTransform({ translate(x + bob, topPx + pos) }) {
                when ((i + side * 3) % 7) {
                    0 -> drawStar3D(Offset.Zero, r, CubaGold, a)
                    1 -> drawMiniCubanFlag3D(Offset(-r, -r * 0.6f), Size(r * 2f, r * 1.2f), timeSec * 3f + i, a)
                    2 -> drawHeartGem3D(r, 1f + 0.1f * sin(timeSec * 3f + i), CubaRed, a)
                    3 -> drawDominoTile3D(r, timeSec * 1.6f + i, Color.White, a)
                    4 -> drawOctahedronGem3D(r, timeSec * 1.8f + i, 0.4f, BotCyan, a)
                    5 -> drawMusicNote3D(Offset.Zero, r * 1.5f, Color(0xFF6FA0FF), a)
                    else -> drawMiniAlmendron3D(radius = r * 1.3f, bounce = 0f, color = CubaGold, alpha = a)
                }
            }
        }
    }
}

/** Destellos ✦ que aparecen y se apagan por toda la pantalla (sin estado: todo sale del tiempo). */
private fun DrawScope.drawGlints(w: Float, h: Float, timeSec: Float, count: Int) {
    for (i in 0 until count) {
        val period = 2.4f + hash01(i * 7 + 1) * 2.6f
        val t = timeSec + hash01(i * 13 + 5) * 20f
        val cyc = floor(t / period).toInt()
        val p = t / period - floor(t / period)
        val s = sin(PI.toFloat() * p)
        val x = w * (0.04f + 0.92f * hash01(cyc * 31 + i * 17 + 3))
        val y = h * (0.04f + 0.92f * hash01(cyc * 47 + i * 29 + 11))
        val r = (3f + 5f * hash01(i * 5 + 9)).dp.toPx() * s
        val col = when (i % 3) { 0 -> Color.White; 1 -> CubaGold; else -> Color(0xFF9DC2FF) }
        val a = (0.85f * s).coerceIn(0f, 1f)
        drawCircle(col.copy(alpha = a * 0.22f), radius = r * 1.7f, center = Offset(x, y))
        drawLine(col.copy(alpha = a), Offset(x - r, y), Offset(x + r, y), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(col.copy(alpha = a), Offset(x, y - r), Offset(x, y + r), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Cada 18 s pasa algo: estrella fugaz, un almendrón que cruza por abajo, o fuegos artificiales. */
private fun DrawScope.drawFlyBy(w: Float, h: Float, timeSec: Float, bottomPx: Float) {
    val ph = timeSec % FlyPeriod
    val cyc = floor(timeSec / FlyPeriod).toInt()
    when (cyc % 3) {
        0 -> {
            val dur = 1.8f
            if (ph > dur) return
            val p = ph / dur
            val sx = w * 1.08f
            val sy = h * (0.22f + 0.20f * hash01(cyc + 3))
            val ex = -w * 0.08f
            val ey = sy + h * 0.28f
            fun at(q: Float) = Offset(sx + (ex - sx) * q, sy + (ey - sy) * q)
            val fade = sin(PI.toFloat() * p).coerceIn(0f, 1f)
            for (k in 0 until 16) {
                val q = (p - k * 0.014f).coerceIn(0f, 1f)
                val a = (1f - k / 16f) * fade
                drawCircle(Color.White.copy(alpha = a * 0.75f), radius = (5.5f - k * 0.28f).coerceAtLeast(1f).dp.toPx(), center = at(q))
            }
            drawStar3D(at(p), 11.dp.toPx(), CubaGold, 0.95f * fade)
        }
        1 -> {
            val dur = 3.4f
            if (ph > dur) return
            val p = ph / dur
            val x = -40.dp.toPx() + (w + 80.dp.toPx()) * p
            val y = bottomPx - 26.dp.toPx()
            for (k in 1..10) {
                val a = (1f - k / 11f) * 0.5f
                drawCircle(CubaGold.copy(alpha = a), radius = (4.5f - k * 0.3f).coerceAtLeast(1f).dp.toPx(),
                    center = Offset(x - 22.dp.toPx() - k * 9.dp.toPx(), y + 6.dp.toPx() + sin(timeSec * 10f + k) * 2.dp.toPx()))
            }
            drawMiniAlmendron3D(radius = 20.dp.toPx(), bounce = sin(timeSec * 16f) * 1.5.dp.toPx(), color = CubaRed, centerOverride = Offset(x, y), alpha = 0.95f)
        }
        else -> {
            val dur = 2.8f
            if (ph > dur) return
            val palette = listOf(CubaGold, Color.White, CubaRed, Color(0xFF6FA0FF))
            for (b in 0 until 3) {
                val q = (ph - b * 0.55f) / 1.5f
                if (q < 0f || q > 1f) continue
                val cx = w * (0.22f + 0.56f * hash01(cyc * 5 + b * 7 + 1))
                val cy = h * (0.20f + 0.25f * hash01(cyc * 3 + b * 11 + 2))
                val col = palette[(cyc + b) % palette.size]
                val e = 1f - (1f - q) * (1f - q)
                val rr = 70.dp.toPx() * (0.7f + 0.3f * hash01(b + 4))
                val a = (1f - q).coerceIn(0f, 1f)
                if (q < 0.2f) drawCircle(Color.White.copy(alpha = (1f - q / 0.2f) * 0.6f), radius = 14.dp.toPx() * (0.5f + q * 3f), center = Offset(cx, cy))
                for (k in 0 until 18) {
                    val ang = k * (2f * PI.toFloat() / 18f) + b
                    val px = cx + cos(ang) * rr * e
                    val py = cy + sin(ang) * rr * e + 26.dp.toPx() * q * q
                    drawCircle(col.copy(alpha = a), radius = 2.6.dp.toPx() * (1f - 0.5f * q), center = Offset(px, py))
                    drawCircle(Color.White.copy(alpha = a * 0.7f), radius = 1.1.dp.toPx(), center = Offset(px, py))
                }
            }
        }
    }
}

/**
 * Escena 3D de FONDO (va detras de las tarjetas, asi que nunca tapa texto ni botones):
 * auroras con los colores de la bandera, estrellitas, luces bokeh y objetos cubanos 3D flotando con profundidad.
 * Se dibuja a ~30 fps para no cansar el telefono.
 */
@Composable
fun CuvayScene3D() {
    val ctx = LocalContext.current
    remember { CuvayFx.ensure(ctx) }
    val level = CuvayFx.level
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(level > 0) {
        if (level <= 0) return@LaunchedEffect
        var t0 = 0L
        var last = 0L
        while (isActive) {
            withFrameNanos { ns ->
                if (t0 == 0L) t0 = ns
                if (ns - last >= 33_000_000L) { last = ns; tick = ns - t0 }
            }
        }
    }
    if (level <= 0) return
    Canvas(Modifier.fillMaxSize()) {
        val t = (tick / 1_000_000_000.0).toFloat()
        val w = size.width
        val h = size.height

        fun glow(cx: Float, cy: Float, r: Float, c: Color, a: Float) {
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy)
            )
        }
        // 1. Auroras (azul, rojo y un toque dorado)
        glow(w * (0.12f + 0.10f * sin(t * 0.21f)), h * (0.16f + 0.05f * cos(t * 0.17f)), w * 0.85f, Color(0xFF2F6BFF), 0.30f)
        glow(w * (0.90f + 0.05f * cos(t * 0.19f)), h * (0.80f + 0.06f * sin(t * 0.23f)), w * 0.90f, CubaRed, 0.26f)
        glow(w * 0.55f, h * (0.48f + 0.08f * sin(t * 0.13f)), w * 0.65f, CubaGold, 0.07f)

        // 2. Estrellitas titilando
        if (level >= 2) {
            for (i in 0 until 42) {
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (0.8f + hash01(i) * 1.6f) + i))
                drawCircle(
                    Color.White.copy(alpha = 0.55f * tw),
                    radius = (0.6f + 1.2f * hash01(i * 7 + 3)).dp.toPx(),
                    center = Offset(w * hash01(i * 3 + 1), h * hash01(i * 5 + 2))
                )
            }
        }

        // 3. Luces bokeh que suben despacito
        val bokeh = if (level >= 2) 9 else 4
        for (i in 0 until bokeh) {
            val sp = 0.012f + 0.02f * hash01(i * 3 + 8)
            val prog = (t * sp + hash01(i * 5 + 1)) % 1f
            val x = w * (0.08f + 0.84f * hash01(i * 7 + 2)) + sin(t * 0.5f + i) * 10.dp.toPx()
            val y = h * (1.08f - 1.16f * prog)
            val rr = (14f + 30f * hash01(i * 11 + 6)).dp.toPx()
            val a = 0.10f * sin(PI.toFloat() * prog)
            val c = SceneColors[i % SceneColors.size]
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = a * 0.5f), c.copy(alpha = a), Color.Transparent), center = Offset(x, y), radius = rr),
                radius = rr, center = Offset(x, y)
            )
        }

        // 4. Objetos cubanos 3D flotando con profundidad
        val n = if (level >= 2) 16 else 6
        for (i in 0 until n) {
            val z = 0.55f + 0.45f * hash01(i * 11 + 4)
            val x = w * (0.07f + 0.86f * hash01(i * 3 + 7)) + sin(t * 0.35f * z + i * 1.3f) * 16.dp.toPx()
            val y = h * (0.08f + 0.86f * ((i + 0.5f) / n)) + cos(t * 0.30f * z + i * 1.7f) * 12.dp.toPx()
            val rad = (9f + 17f * z).dp.toPx()
            val a = 0.28f + 0.42f * z
            val c = SceneColors[(i * 5 + 2) % SceneColors.size]
            withTransform({ translate(x, y); rotate(sin(t * 0.3f + i) * 12f, Offset.Zero) }) {
                when (i % 9) {
                    0 -> withTransform({ scale(abs(cos(t * 0.9f + i)).coerceIn(0.35f, 1f), 1f, Offset.Zero) }) {
                        drawStar3D(Offset.Zero, rad, CubaGold, a)
                    }
                    1 -> drawMiniCubanFlag3D(Offset(-rad, -rad * 0.6f), Size(rad * 2f, rad * 1.2f), t * 3f + i, a)
                    2 -> drawOctahedronGem3D(rad * 0.8f, t * 1.4f + i, sin(t + i) * 0.5f, c, a)
                    3 -> drawDominoTile3D(rad * 0.8f, t * 1.2f + i, Color.White, a)
                    4 -> drawHeartGem3D(rad * 0.8f, 1f + 0.1f * sin(t * 3f + i), CubaRed, a)
                    5 -> drawHoloCube3D(rad * 0.7f, t * 1.1f + i, t * 1.5f + i, c, a)
                    6 -> drawMiniAlmendron3D(radius = rad, bounce = sin(t * 4f + i) * 1.5.dp.toPx(), color = c, alpha = a)
                    7 -> drawMaracasPair3D(rad * 0.8f, sin(t * 6f + i) * 14f, c, a)
                    8 -> drawMusicNote3D(Offset.Zero, rad * 1.2f, c, a)
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun BoxScope.CuvayAssistant(tab: Tab, inChannel: Boolean, onGoChat: () -> Unit) {
    val ctx = LocalContext.current
    val audio = remember { CuviAudioEngine(ctx) }
    DisposableEffect(Unit) { onDispose { audio.release() } }

    var hidden by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<Help?>(null) }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var roamTargetX by remember { mutableStateOf(0f) }
    var roamTargetY by remember { mutableStateOf(0f) }
    val animRoamX by animateFloatAsState(roamTargetX, tween(2100, easing = FastOutSlowInEasing), label = "rx")
    val animRoamY by animateFloatAsState(roamTargetY, tween(2100, easing = FastOutSlowInEasing), label = "ry")

    var aiMode by remember { mutableStateOf(false) }
    val aiMsgs = remember { mutableStateListOf<AiMsg>() }

    var action by remember { mutableStateOf(CuviAction.DECORATE) }
    var avatarStyle by remember { mutableStateOf(CuviAvatarStyle.AUTO_DUO) }
    var mascotTick by remember { mutableIntStateOf(0) }
    var bubbleText by remember { mutableStateOf<String?>(null) }
    var screenFxEnabled by remember { mutableStateOf(CuvayFx.level > 0) }
    var soundEnabled by remember { mutableStateOf(true) }
    var musicLoopEnabled by remember { mutableStateOf(false) }
    var screenSize by remember { mutableStateOf(IntSize(1080, 2200)) }

    val particles = remember { mutableStateListOf<Particle3D>() }
    val ambientDecos = remember { mutableStateListOf<AmbientDeco3D>() }
    val laserBeams = remember { mutableStateListOf<LaserBeam3D>() }
    val shockwaves = remember { mutableStateListOf<ShockwaveRing3D>() }
    val trailOrbs = remember { mutableStateListOf<TrailOrb3D>() }
    var frameTick by remember { mutableLongStateOf(0L) }
    val uiDensity = LocalDensity.current
    val statusTopPx = WindowInsets.statusBars.getTop(uiDensity)
    val imeOpenNow = WindowInsets.ime.getBottom(uiDensity) > 0
    val railY = statusTopPx + with(uiDensity) { 30.dp.toPx() }
    var decoSlot by remember { mutableIntStateOf(0) }
    val greeting = remember { cubanGreeting() }
    val navBottomPx = WindowInsets.navigationBars.getBottom(uiDensity)
    var mascotRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    LaunchedEffect(CuvayFx.level) { screenFxEnabled = CuvayFx.level > 0 }

    if (hidden) return

    val totalDx = dx + (if (open) 0f else animRoamX)
    val totalDy = dy + (if (open) 0f else animRoamY)

    fun botOrigin(): Offset {
        val ox = (screenSize.width - 140f + totalDx).coerceIn(70f, (screenSize.width - 70f).coerceAtLeast(100f))
        val oy = (screenSize.height - 200f + totalDy).coerceIn(110f, (screenSize.height - 90f).coerceAtLeast(150f))
        return Offset(ox, oy)
    }

    fun spawnShockwave(at: Offset? = null, color: Color = BotCyan, maxR: Float = 170f) {
        if (!screenFxEnabled) return
        if (shockwaves.size >= 10) shockwaves.removeAt(0)
        shockwaves.add(ShockwaveRing3D(center = at ?: botOrigin(), color = color, maxRadius = maxR))
    }

    fun spawnBurst(kind: FxKind? = null, count: Int = 12, at: Offset? = null) {
        if (!screenFxEnabled) return
        val orig = at ?: botOrigin()
        val palette = listOf(CubaRed, CubaGold, Color.White, Color(0xFF4F86FF), BotGreen)
        repeat((count * 0.6f).roundToInt().coerceAtLeast(1)) {
            val k = kind ?: FxKind.values().random()
            val angle = Random.nextFloat() * (-PI.toFloat() * 0.95f) - 0.1f
            val speed = when (k) {
                FxKind.SOCCER_BALL -> Random.nextFloat() * 15f + 12f
                FxKind.FIREWORK_SPARK -> Random.nextFloat() * 14f + 6f
                else -> Random.nextFloat() * 11f + 5f
            }
            particles.add(
                Particle3D(
                    x = orig.x + Random.nextFloat() * 38f - 19f,
                    y = orig.y - 30f,
                    vx = cos(angle) * speed * (if (Random.nextBoolean()) 1f else -1f),
                    vy = sin(angle) * speed - 5f,
                    z = if (k == FxKind.SOCCER_BALL) 0.78f else Random.nextFloat() * 0.75f + 0.45f,
                    vz = Random.nextFloat() * 0.026f + 0.008f,
                    rot = Random.nextFloat() * 360f,
                    vRot = (Random.nextFloat() - 0.5f) * 15f,
                    tilt3D = Random.nextFloat() * 6.28f,
                    vTilt = Random.nextFloat() * 0.15f + 0.05f,
                    kind = k,
                    color = palette.random(),
                    life = 1f,
                    decay = if (k == FxKind.SOCCER_BALL) 0.0040f else 0.0080f
                )
            )
        }
    }

    /** Cuvi va colocando, en orden, un adorno cubano en la barrita de arriba: palma, estrella, almendrón, maracas, dominó. */
    fun plantNewDecoration3D(playSound: Boolean = true) {
        if (!screenFxEnabled) return
        val slot = decoSlot % RailTypes.size
        decoSlot++
        val xFrac = 0.56f + slot * 0.09f
        ambientDecos.removeAll { abs(it.xFrac - xFrac) < 0.001f }
        ambientDecos.add(
            AmbientDeco3D(
                xFrac = xFrac, yFrac = 0f, type = RailTypes[slot], color = RailColors[slot],
                scale = 0.72f, phaseOffset = slot * 1.3f, spawnProgress = 0.05f
            )
        )
        val w = screenSize.width.toFloat().coerceAtLeast(400f)
        spawnShockwave(at = Offset(w * xFrac, railY), color = RailColors[slot], maxR = 60f)
        if (playSound) audio.play(SoundFx.LASER_DECO)
    }

    fun triggerActionEffects(act: CuviAction, speakVoice: Boolean = false) {
        action = act
        mascotTick++
        bubbleText = "${act.banner3D} · ${act.bubble}"
        if (speakVoice) audio.speak(act.voice)

        when (act) {
            CuviAction.DECORATE -> {
                plantNewDecoration3D(playSound = true)
                spawnBurst(FxKind.CRYSTAL_3D, 8)
            }
            CuviAction.SOCCER -> {
                audio.play(SoundFx.KICK)
                spawnShockwave(color = CubaGold, maxR = 160f)
                spawnBurst(FxKind.SOCCER_BALL, count = 3)
                spawnBurst(FxKind.CUBA_STAR, count = 8)
            }
            CuviAction.DOMINO_CAPICUA -> {
                audio.play(SoundFx.DOMINO_SLAM)
                spawnShockwave(color = CubaRed, maxR = 210f)
                spawnBurst(FxKind.DOMINO_TILE, count = 9)
                spawnBurst(FxKind.CUBA_STAR, count = 6)
            }
            CuviAction.TRES_CUBANO -> {
                audio.play(SoundFx.GUITAR_CHORD)
                spawnBurst(FxKind.MUSIC_NOTE, count = 10)
                spawnBurst(FxKind.HEART_GEM, count = 6)
            }
            CuviAction.CAFECITO_TURBO -> {
                audio.play(SoundFx.COFFEE_SIP)
                spawnShockwave(color = BotCyan, maxR = 175f)
                spawnBurst(FxKind.COFFEE_CUP, count = 7)
                spawnBurst(FxKind.LIGHTNING_BOLT, count = 8)
            }
            CuviAction.DJ_FIESTA -> {
                audio.play(SoundFx.DJ_SCRATCH)
                spawnShockwave(color = NeonPink, maxR = 190f)
                spawnBurst(FxKind.VINYL_DISC, count = 7)
                spawnBurst(FxKind.MUSIC_NOTE, count = 8)
            }
            CuviAction.ALMENDRON_3D -> {
                audio.play(SoundFx.SALSABEAT)
                spawnBurst(FxKind.CUBA_FLAG, count = 6)
                spawnBurst(FxKind.RING_3D, count = 7)
            }
            CuviAction.BOXEO_CUBANO -> {
                audio.play(SoundFx.BOXING_BELL)
                spawnShockwave(color = CubaRed, maxR = 180f)
                spawnBurst(FxKind.BOXING_GLOVE, count = 7)
                spawnBurst(FxKind.CUBA_STAR, count = 8)
            }
            CuviAction.FIREWORKS_3D -> {
                audio.play(SoundFx.FIREWORK_BOOM)
                val w = screenSize.width.toFloat().coerceAtLeast(400f)
                val h = screenSize.height.toFloat().coerceAtLeast(800f)
                repeat(3) {
                    val fwCenter = Offset(w * (0.2f + Random.nextFloat() * 0.6f), h * (0.15f + Random.nextFloat() * 0.35f))
                    spawnShockwave(at = fwCenter, color = listOf(CubaGold, BotCyan, NeonPink).random(), maxR = 180f)
                    spawnBurst(FxKind.FIREWORK_SPARK, count = 12, at = fwCenter)
                }
            }
            CuviAction.JUMP_SPIN -> {
                audio.play(SoundFx.JUMP)
                spawnShockwave(color = BotGreen, maxR = 165f)
                spawnBurst(FxKind.CUBA_FLAG, count = 5)
                spawnBurst(FxKind.CRYSTAL_3D, count = 8)
            }
            CuviAction.DANCE -> {
                audio.play(SoundFx.FIESTA)
                spawnBurst(FxKind.MUSIC_NOTE, count = 9)
                spawnBurst(FxKind.RING_3D, count = 6)
            }
            CuviAction.HEAD_HAND -> {
                audio.play(SoundFx.POP)
                spawnBurst(FxKind.NEON_ORB, count = 7)
            }
            else -> {
                audio.play(SoundFx.AMBIENT_CHIME)
                spawnBurst(FxKind.CUBA_STAR, count = 6)
            }
        }
    }

    // Sembrar las primeras decoraciones 3D y sonar bienvenida al abrir la app
    LaunchedEffect(Unit) {
        delay(350)
        audio.play(SoundFx.CHEER_FANFARE)
        audio.speak("¡Qué bolá, asere! Soy Cuvi, tu asistente cubana")
        repeat(5) { idx ->
            plantNewDecoration3D(playSound = idx == 0)
            delay(260)
        }
    }

    val tabNow by rememberUpdatedState(tab)
    val inChNow by rememberUpdatedState(inChannel)
    // Ciclo tranquilo: cada ~9 s Cuvi hace algo suave (saludo, baile, tres...) y cada 3ra vez suelta un consejo de la pantalla.
    LaunchedEffect(screenFxEnabled) {
        val calm = listOf(
            CuviAction.IDLE, CuviAction.DANCE, CuviAction.WALK, CuviAction.TRES_CUBANO,
            CuviAction.JUMP_SPIN, CuviAction.CAFECITO_TURBO, CuviAction.DOMINO_CAPICUA, CuviAction.ALMENDRON_3D
        )
        var idx = 0
        while (isActive) {
            delay(9000)
            if (open) continue
            idx++
            if (idx % 3 == 0) {
                bubbleText = "💡 " + (screenTips(tabNow, inChNow) + cubanLines).random()
                audio.play(SoundFx.AMBIENT_CHIME)
                delay(4200)
                bubbleText = null
                continue
            }
            val next = calm[idx % calm.size]
            val maxRx = (screenSize.width * 0.45f).coerceAtLeast(120f)
            val maxRy = (screenSize.height * 0.08f).coerceAtLeast(60f)
            when (next) {
                CuviAction.WALK, CuviAction.ALMENDRON_3D -> {
                    roamTargetX = -Random.nextFloat() * maxRx
                    roamTargetY = -Random.nextFloat() * maxRy
                }
                else -> {
                    roamTargetX = -Random.nextFloat() * (maxRx * 0.3f)
                    roamTargetY = 0f
                }
            }
            triggerActionEffects(next, speakVoice = (idx % 6 == 1))
            delay(2800)
            bubbleText = null
        }
    }

    // Al cambiar de pestaña o entrar a un canal mientras ojea la app, Cuvi embellece la pantalla nueva
    LaunchedEffect(tab, inChannel) {
        if (screenFxEnabled) {
            action = CuviAction.DECORATE
            plantNewDecoration3D(playSound = true)
            spawnBurst(FxKind.CUBA_STAR, count = 4)
        }
    }

    // Motor físico a 60fps para partículas 3D, balones con rebote, ondas 3D, estelas y decoraciones
    LaunchedEffect(Unit) {
        var trailCounter = 0
        var t0 = 0L
        var lastDraw = 0L
        while (isActive) {
            withFrameNanos {
                if (t0 == 0L) t0 = it
                if (it - lastDraw >= 30_000_000L) { lastDraw = it; frameTick = it - t0 }
                val w = screenSize.width.toFloat().coerceAtLeast(400f)
                val h = screenSize.height.toFloat().coerceAtLeast(800f)

                trailCounter++
                if (screenFxEnabled && trailCounter % 4 == 0 && (abs(animRoamX - roamTargetX) > 6f || abs(animRoamY - roamTargetY) > 6f)) {
                    if (trailOrbs.size >= 22) trailOrbs.removeAt(0)
                    trailOrbs.add(
                        TrailOrb3D(
                            pos = botOrigin() + Offset(Random.nextFloat() * 20f - 10f, 30f),
                            color = listOf(BotCyan, CubaGold, BotGreen, NeonPink).random(),
                            life = 1f
                        )
                    )
                }

                ambientDecos.forEach { d ->
                    if (d.spawnProgress < 1f) d.spawnProgress = (d.spawnProgress + 0.045f).coerceAtMost(1f)
                }

                val beamIter = laserBeams.iterator()
                while (beamIter.hasNext()) {
                    val b = beamIter.next()
                    b.life -= 0.042f
                    if (b.life <= 0f) beamIter.remove()
                }

                val waveIter = shockwaves.iterator()
                while (waveIter.hasNext()) {
                    val sw = waveIter.next()
                    sw.progress += 0.035f
                    if (sw.progress >= 1f) waveIter.remove()
                }

                val trailIter = trailOrbs.iterator()
                while (trailIter.hasNext()) {
                    val tr = trailIter.next()
                    tr.life -= 0.032f
                    if (tr.life <= 0f) trailIter.remove()
                }

                if (particles.isNotEmpty()) {
                    val iter = particles.iterator()
                    while (iter.hasNext()) {
                        val p = iter.next()
                        p.x += p.vx
                        p.y += p.vy
                        if (p.kind == FxKind.SOCCER_BALL) {
                            p.vy += 0.42f
                            if (p.x < 45f) { p.x = 45f; p.vx = abs(p.vx) * 0.86f }
                            if (p.x > w - 45f) { p.x = w - 45f; p.vx = -abs(p.vx) * 0.86f }
                            if (p.y > h - 90f) { p.y = h - 90f; p.vy = -abs(p.vy) * 0.80f }
                            if (p.y < 60f) { p.y = 60f; p.vy = abs(p.vy) * 0.76f }
                        } else {
                            p.vy += 0.07f
                            p.vx *= 0.992f
                        }
                        p.z += p.vz
                        if (p.z > 1.85f || p.z < 0.45f) p.vz = -p.vz
                        p.rot += p.vRot
                        p.tilt3D += p.vTilt
                        p.life -= p.decay
                        if (p.life <= 0f) iter.remove()
                    }
                }
            }
        }
    }

    // ---- CAPA 3D DE PANTALLA COMPLETA (No intercepta toques: embellece toda la app) ----
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { if (it.width > 0 && it.height > 0) screenSize = it }
    ) {
        val timeSec = (frameTick / 1_000_000_000.0).toFloat()

        if (screenFxEnabled) {
            // 1. Franjita de la bandera por los bordes (fuera del contenido)
            drawCubanEdgeBorder(size.width, size.height, timeSec)

            // 2. Guirnalda Superior 3D de Luces Neón Cubanas
            drawTopFestiveGarland3D(size.width, timeSec)

            // 3. Capas "pro": aura de Cuvi, cintas de adornos en los bordes, destellos y visitas relampago
            drawMascotStage(mascotRect, timeSec)
            if (CuvayFx.level >= 2) {
                drawEdgeConveyor(size.width, statusTopPx + 118.dp.toPx(), size.height - navBottomPx - 6.dp.toPx(), timeSec)
                drawGlints(size.width, size.height, timeSec, 12)
            }
            drawFlyBy(size.width, size.height, timeSec, size.height - navBottomPx)

            // 4. Estelas 3D de movimiento de Cuvi
            trailOrbs.forEach { tr ->
                val a = tr.life.coerceIn(0f, 1f)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = a * 0.8f), tr.color.copy(alpha = a * 0.45f), Color.Transparent), center = tr.pos, radius = 16.dp.toPx() * a),
                    radius = 16.dp.toPx() * a,
                    center = tr.pos
                )
            }

            // 5. Ondas de choque 3D en perspectiva
            shockwaves.forEach { sw ->
                val a = (1f - sw.progress).coerceIn(0f, 1f)
                val r = sw.maxRadius * sw.progress
                withTransform({
                    translate(sw.center.x, sw.center.y)
                    scale(1f, 0.52f, Offset.Zero)
                }) {
                    drawCircle(sw.color.copy(alpha = a * 0.65f), radius = r, center = Offset.Zero, style = Stroke(width = 4.dp.toPx() * a))
                    drawCircle(Color.White.copy(alpha = a * 0.45f), radius = r * 0.72f, center = Offset.Zero, style = Stroke(width = 1.8.dp.toPx() * a))
                }
            }

            // 6. Adornos cubanos ordenados en una barrita junto al título
            val railOn = !(imeOpenNow && tab == Tab.Chat)
            val plateFrom = if (inChannel) 0.70f else 0.52f
            if (railOn && ambientDecos.isNotEmpty()) {
                val ph = 40.dp.toPx()
                val plateTop = Offset(size.width * plateFrom, railY - ph / 2f)
                val plateSize = Size(size.width * (0.97f - plateFrom), ph)
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(CubaBlue.copy(alpha = 0.55f), BotNavy.copy(alpha = 0.55f), CubaRed.copy(alpha = 0.40f)),
                        startX = size.width * plateFrom, endX = size.width * 0.97f
                    ),
                    topLeft = plateTop, size = plateSize, cornerRadius = CornerRadius(ph / 2f, ph / 2f)
                )
                drawRoundRect(
                    color = CubaGold.copy(alpha = 0.45f),
                    topLeft = plateTop, size = plateSize, cornerRadius = CornerRadius(ph / 2f, ph / 2f),
                    style = Stroke(1.dp.toPx())
                )
            }
            ambientDecos.forEach { d ->
                if (!railOn || d.xFrac < plateFrom + 0.03f) return@forEach
                val cx = size.width * d.xFrac
                val cy = railY + sin(timeSec * 2.2f + d.phaseOffset) * 2.5.dp.toPx()
                val sc = d.scale * d.spawnProgress
                withTransform({
                    translate(cx, cy)
                    scale(sc, sc, Offset.Zero)
                }) {
                    when (d.type) {
                        DecoType.GEM_3D -> drawOctahedronGem3D(
                            radius = 18.dp.toPx(),
                            angleY = timeSec * 1.8f + d.phaseOffset,
                            angleX = sin(timeSec + d.phaseOffset) * 0.5f,
                            color = d.color
                        )
                        DecoType.PALM_3D -> drawTropicalPalm3D(
                            radius = 26.dp.toPx(),
                            sway = sin(timeSec * 2f + d.phaseOffset) * 14f,
                            color = d.color
                        )
                        DecoType.BUBBLE_3D -> drawGlassBubble3D(
                            radius = 16.dp.toPx(),
                            color = d.color
                        )
                        DecoType.STAR_EMBLEM -> {
                            val squash = abs(cos(timeSec * 2f + d.phaseOffset)).coerceIn(0.3f, 1f)
                            withTransform({ scale(squash, 1f, Offset.Zero) }) {
                                drawStar3D(Offset.Zero, 16.dp.toPx(), d.color, 0.85f)
                            }
                        }
                        DecoType.NEON_FLOWER -> drawNeonFlower3D(
                            radius = 16.dp.toPx(),
                            rotDeg = timeSec * 45f + d.phaseOffset * 30f,
                            color = d.color
                        )
                        DecoType.CUBE_HOLO_3D -> drawHoloCube3D(
                            radius = 16.dp.toPx(),
                            angleX = timeSec * 1.4f + d.phaseOffset,
                            angleY = timeSec * 1.9f + d.phaseOffset,
                            color = d.color
                        )
                        DecoType.PYRAMID_PRISM_3D -> drawPyramidPrism3D(
                            radius = 18.dp.toPx(),
                            angleY = timeSec * 1.7f + d.phaseOffset,
                            color = d.color
                        )
                        DecoType.TORUS_PORTAL_3D -> drawTorusPortal3D(
                            radius = 19.dp.toPx(),
                            timeSec = timeSec + d.phaseOffset,
                            color = d.color
                        )
                        DecoType.DOMINO_3D -> drawDominoTile3D(
                            radius = 17.dp.toPx(),
                            tilt = timeSec * 1.6f + d.phaseOffset,
                            color = d.color
                        )
                        DecoType.ALMENDRON_NEON_3D -> drawMiniAlmendron3D(
                            radius = 22.dp.toPx(),
                            bounce = sin(timeSec * 5f + d.phaseOffset) * 2.dp.toPx(),
                            color = d.color
                        )
                        DecoType.MARACAS_3D -> drawMaracasPair3D(
                            radius = 18.dp.toPx(),
                            shake = sin(timeSec * 8f + d.phaseOffset) * 16f,
                            color = d.color
                        )
                        DecoType.CROWN_3D -> drawRoyalCrown3D(
                            radius = 18.dp.toPx(),
                            spin = timeSec * 1.8f + d.phaseOffset,
                            color = d.color
                        )
                        DecoType.HEART_3D -> drawHeartGem3D(
                            radius = 16.dp.toPx(),
                            pulse = 1f + 0.14f * sin(timeSec * 4f + d.phaseOffset),
                            color = d.color
                        )
                    }
                }
            }

            // 7. Rayos holográficos 3D cuando Cuvi instala una decoración
            laserBeams.forEach { b ->
                val a = b.life.coerceIn(0f, 1f)
                drawLine(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = a), b.color.copy(alpha = a), Color.Transparent), start = b.from, end = b.to),
                    start = b.from,
                    end = b.to,
                    strokeWidth = (6.dp.toPx() * a),
                    cap = StrokeCap.Round
                )
                drawCircle(Color.White.copy(alpha = a), radius = 14.dp.toPx() * (1.2f - a), center = b.to, style = Stroke(width = 2.dp.toPx()))
            }

            // 8. Partículas y objetos 3D volando por toda la pantalla
            particles.forEach { p ->
                val alpha = p.life.coerceIn(0f, 1f)
                val scale3d = p.z.coerceIn(0.4f, 2.0f)
                val perspectiveSquash = abs(cos(p.tilt3D)).coerceIn(0.28f, 1f)

                withTransform({
                    translate(p.x, p.y)
                    rotate(p.rot, Offset.Zero)
                    scale(scale3d, scale3d, Offset.Zero)
                }) {
                    when (p.kind) {
                        FxKind.SOCCER_BALL -> {
                            drawCircle(
                                Brush.radialGradient(listOf(BotCyan.copy(alpha = alpha * 0.45f), Color.Transparent)),
                                radius = 34.dp.toPx(),
                                center = Offset(-p.vx * 1.2f, -p.vy * 1.2f)
                            )
                            drawSoccerBall3D(Offset.Zero, 26.dp.toPx(), p.rot, alpha)
                        }
                        FxKind.CUBA_FLAG -> {
                            withTransform({ scale(perspectiveSquash, 1f, Offset.Zero) }) {
                                drawMiniCubanFlag3D(Offset(-22.dp.toPx(), -14.dp.toPx()), Size(44.dp.toPx(), 28.dp.toPx()), p.tilt3D, alpha)
                            }
                        }
                        FxKind.CUBA_STAR -> {
                            withTransform({ scale(perspectiveSquash, 1f, Offset.Zero) }) {
                                drawStar3D(Offset.Zero, 15.dp.toPx(), p.color, alpha)
                            }
                        }
                        FxKind.CRYSTAL_3D -> {
                            drawOctahedronGem3D(14.dp.toPx(), p.tilt3D, p.rot * 0.02f, p.color, alpha)
                        }
                        FxKind.NEON_ORB -> {
                            drawGlassBubble3D(15.dp.toPx(), p.color, alpha)
                        }
                        FxKind.RING_3D -> {
                            withTransform({ scale(1f, perspectiveSquash, Offset.Zero) }) {
                                drawCircle(p.color.copy(alpha = alpha * 0.85f), radius = 18.dp.toPx(), center = Offset.Zero, style = Stroke(width = 3.5.dp.toPx()))
                                drawCircle(Color.White.copy(alpha = alpha * 0.6f), radius = 12.dp.toPx(), center = Offset.Zero, style = Stroke(width = 1.5.dp.toPx()))
                            }
                        }
                        FxKind.MUSIC_NOTE -> {
                            drawMusicNote3D(Offset.Zero, 16.dp.toPx(), p.color, alpha)
                        }
                        FxKind.DOMINO_TILE -> {
                            drawDominoTile3D(15.dp.toPx(), p.tilt3D, p.color, alpha)
                        }
                        FxKind.COFFEE_CUP -> {
                            drawCoffeeCupParticle3D(14.dp.toPx(), p.color, alpha)
                        }
                        FxKind.FIREWORK_SPARK -> {
                            drawFireworkSpark3D(16.dp.toPx(), p.color, alpha)
                        }
                        FxKind.HEART_GEM -> {
                            drawHeartGem3D(14.dp.toPx(), 1f, p.color, alpha)
                        }
                        FxKind.LIGHTNING_BOLT -> {
                            drawLightningBolt3D(16.dp.toPx(), p.color, alpha)
                        }
                        FxKind.VINYL_DISC -> {
                            withTransform({ scale(1f, perspectiveSquash, Offset.Zero) }) {
                                drawVinylDisc3D(16.dp.toPx(), p.color, alpha)
                            }
                        }
                        FxKind.BOXING_GLOVE -> {
                            drawBoxingGloveParticle3D(15.dp.toPx(), CubaRed, alpha)
                        }
                    }
                }
            }
        }
    }

    // ---- Panel Desplegable: el Malecón de Cuvi ----
    AnimatedVisibility(
        open,
        Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 148.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f)
    ) {
        val panelShape = RoundedCornerShape(26.dp)
        val panelScroll = rememberScrollState()
        val panelMaxH = (LocalConfiguration.current.screenHeightDp - 190).coerceAtLeast(320).dp
        val hourNow = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
        var tricks by remember { mutableStateOf(false) }
        var palabra by remember { mutableStateOf(cbPalabraDelDia()) }
        val p = page
        Column(
            Modifier.width(332.dp).clip(panelShape)
                .background(Brush.verticalGradient(listOf(CbNoche2, CbNoche)))
                .border(1.5.dp, Brush.verticalGradient(listOf(CbOro.copy(alpha = 0.85f), CbOro.copy(alpha = 0.25f))), panelShape)
                .then(if (aiMode) Modifier else Modifier.heightIn(max = panelMaxH).verticalScroll(panelScroll))
        ) {
            if (aiMode) {
                // ---------- Charla con la IA ----------
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
                    AiChatView(tab, inChannel, aiMsgs, audio) { aiMode = false }
                }
            } else if (p != null) {
                // ---------- Página de ayuda / dicho ----------
                Column(Modifier.padding(14.dp)) {
                    CbFlagLine(Modifier.padding(bottom = 10.dp), 3.dp)
                    Text(p.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Display)
                    Column(
                        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.06f)).padding(12.dp)
                    ) {
                        Text(cbMarkdown(p.body), color = Color(0xFFDCE6FA), fontSize = 13.sp, lineHeight = 19.sp)
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbPill("‹  Volver", false, CbOro) { audio.play(SoundFx.POP); page = null }
                        CbPill("🔊 Escuchar", false, CbPalma) { audio.speak(cbSpeakable(p.body)) }
                    }
                }
            } else {
                // ---------- Cabecera: el Malecón con Cuvi ----------
                Box(Modifier.fillMaxWidth().height(88.dp)) {
                    CbMalecon(Modifier.fillMaxSize(), hour = hourNow)
                    // Ritmo / sonido / efectos 3D, sobre el cielo
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 10.dp)
                            .clip(RoundedCornerShape(18.dp)).background(CbNoche.copy(alpha = 0.55f)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CbIconToggle(if (musicLoopEnabled) "🎵" else "🎶", musicLoopEnabled, CbRojo, 30.dp, 13.sp) {
                            musicLoopEnabled = !musicLoopEnabled
                            audio.toggleMusicLoop(musicLoopEnabled)
                        }
                        CbIconToggle(if (soundEnabled) "🔊" else "🔇", soundEnabled, CbOro, 30.dp, 13.sp) {
                            soundEnabled = !soundEnabled
                            audio.enabled = soundEnabled
                            if (!soundEnabled) {
                                musicLoopEnabled = false
                                audio.toggleMusicLoop(false)
                            } else {
                                audio.play(SoundFx.POP)
                            }
                        }
                        val fxLevel = CuvayFx.level
                        CbPill(when (fxLevel) { 2 -> "✨ FULL"; 1 -> "✨ SUAVE"; else -> "3D OFF" }, fxLevel > 0, CbPalma, small = true) {
                            val next = (CuvayFx.level + 2) % 3
                            CuvayFx.setLevel(ctx, next)
                            screenFxEnabled = next > 0
                            if (next == 0) {
                                particles.clear()
                                ambientDecos.clear()
                                shockwaves.clear()
                                trailOrbs.clear()
                            } else if (ambientDecos.isEmpty()) {
                                repeat(5) { idx -> plantNewDecoration3D(playSound = idx == 0) }
                            }
                        }
                    }
                    // Cuvi con su aro dorado, parada en el Malecón
                    Box(
                        Modifier.align(Alignment.BottomStart).offset(x = 14.dp, y = 32.dp).size(68.dp)
                            .clip(CircleShape).background(CbNoche).border(2.5.dp, CbOro, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarStyle == CuviAvatarStyle.CLASSIC_IK) {
                            CuvayMascotHead(Modifier.size(58.dp))
                        } else {
                            Cuvi3DCharacter(Modifier.size(60.dp), action = action, animated = true, miniAvatar = true)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 92.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cuvi", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, fontFamily = Display)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "IA cubana", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(CbRojo).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(CbPalma))
                            Spacer(Modifier.width(5.dp))
                            Text(greeting, color = CbTexto2, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }

                // ---------- Menú ----------
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                    CbMenuCard("🤖", "Pregúntale a Cuvi", "IA real con voz · usa internet", CbAzulVivo, highlighted = true) {
                        audio.play(SoundFx.POP)
                        aiMode = true
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CbTile("💬", "Iniciar chat", CbRojo, Modifier.weight(1f).fillMaxHeight()) {
                            audio.play(SoundFx.POP)
                            open = false
                            onGoChat()
                        }
                        CbTile("✨", "Explícame esta pantalla", CbOro, Modifier.weight(1f).fillMaxHeight()) {
                            audio.play(SoundFx.SPARKLE)
                            val h = screenHelp(tab, inChannel)
                            page = h
                            audio.speak(cbSpeakable(h.body))
                        }
                        CbTile("🌴", "Un dicho cubano", CbPalma, Modifier.weight(1f).fillMaxHeight()) {
                            audio.play(SoundFx.CLAVES)
                            val d = cubanDichos.random()
                            page = Help("Dicho cubano 🇨🇺", d)
                            audio.speak(cbSpeakable(d))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    CbPalabraCard(
                        palabra,
                        onSpeak = {
                            audio.play(SoundFx.SPARKLE)
                            audio.speak("${palabra.palabra}. ${palabra.significado} ${palabra.ejemplo}")
                        },
                        onNext = {
                            audio.play(SoundFx.CLAVES)
                            palabra = cbCubanismos.filter { it !== palabra }.random()
                        }
                    )

                    Text(
                        "Guías paso a paso", Modifier.padding(top = 12.dp, bottom = 6.dp),
                        color = CbTexto2, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
                    )
                    val guideIcons = listOf("📥", "🖼️", "📵", "🛠️")
                    topics.chunked(2).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEachIndexed { c, t ->
                                CbPill(guideIcons.getOrElse(r * 2 + c) { "📘" } + " " + t.title, false, CbAzulVivo, Modifier.weight(1f)) {
                                    audio.play(SoundFx.POP)
                                    page = t
                                    audio.speak(cbSpeakable(t.body))
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    // Trucos y disfraces (plegable, para que el menú quede ordenado)
                    CbPill(
                        if (tricks) "🎭 Trucos y disfraces de Cuvi  ▴" else "🎭 Trucos y disfraces de Cuvi  ▾",
                        tricks, CbMar, Modifier.fillMaxWidth()
                    ) {
                        tricks = !tricks
                        audio.play(SoundFx.POP)
                    }
                    AnimatedVisibility(tricks) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CuviAvatarStyle.values().forEach { st ->
                                    CbPill(st.label, avatarStyle == st, CbOro, Modifier.weight(1f), small = true) {
                                        avatarStyle = st
                                        audio.play(SoundFx.SPARKLE)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            CuviAction.values().toList().chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { act ->
                                        CbPill(act.label, action == act, CbAzulVivo, Modifier.weight(1f), small = true) {
                                            triggerActionEffects(act, speakVoice = true)
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }

                    // Pie con la bandera
                    CbFlagLine(Modifier.padding(top = 10.dp), 3.dp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Hecha con cariño en Cuba 🇨🇺", Modifier.weight(1f), color = CbTexto2, fontSize = 10.sp)
                        Text(
                            "✕ Ocultar",
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { hidden = true }.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = CbTexto2, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // ---- Personaje Flotante 3D (Se pasea, decora la app, habla y se puede arrastrar) ----
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 6.dp, bottom = 8.dp)
            .offset { IntOffset(totalDx.roundToInt(), totalDy.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { c, d ->
                    c.consume()
                    roamTargetX = 0f
                    roamTargetY = 0f
                    dx += d.x
                    dy += d.y
                }
            },
        horizontalAlignment = Alignment.End
    ) {
        AnimatedVisibility(
            visible = bubbleText != null && !open,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Text(
                text = bubbleText ?: "",
                color = CbNoche,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 15.sp,
                modifier = Modifier
                    .padding(bottom = 4.dp, end = 6.dp)
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .background(Brush.linearGradient(listOf(CbCrema, Color(0xFFFFE6B0))))
                    .border(1.5.dp, CbRojo, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // Botones flotantes rápidos (Audio / Decorar ahora) junto a la mascota
        Row(
            Modifier.padding(bottom = 2.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (soundEnabled) "🔊" else "🔇",
                Modifier.clip(CircleShape)
                    .background(BotNavy.copy(alpha = 0.82f))
                    .border(1.dp, BotCyan.copy(alpha = 0.5f), CircleShape)
                    .clickable {
                        soundEnabled = !soundEnabled
                        audio.enabled = soundEnabled
                        if (soundEnabled) {
                            audio.play(SoundFx.CHEER_FANFARE)
                            audio.speak("¡Sonido activado, asere!")
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                fontSize = 10.sp
            )
            Text(
                "🇨🇺 Fiesta",
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(BotNavy.copy(alpha = 0.82f))
                    .border(1.dp, CubaGold.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .clickable {
                        val nextAct = CuviAction.values().random()
                        triggerActionEffects(nextAct, speakVoice = true)
                        repeat(2) { plantNewDecoration3D(playSound = false) }
                    }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                color = CubaGold,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black
            )
        }

        Box(
            Modifier
                .size(MASCOT_W, MASCOT_H)
                .onGloballyPositioned { c -> mascotRect = c.boundsInRoot() }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    open = !open
                    if (open) {
                        roamTargetX = 0f
                        roamTargetY = 0f
                        page = null
                        mascotTick++
                        audio.play(SoundFx.CHEER_FANFARE)
                        audio.speak("¡Qué bolá! ¿En qué te ayudo?")
                        spawnShockwave(color = BotCyan, maxR = 160f)
                        spawnBurst(FxKind.CUBA_STAR, 8)
                    } else {
                        val next = CuviAction.values().random()
                        triggerActionEffects(next, speakVoice = true)
                        plantNewDecoration3D(playSound = false)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when (avatarStyle) {
                CuviAvatarStyle.CYBER_3D -> {
                    Cuvi3DCharacter(Modifier.fillMaxSize(), action = action, animated = true, miniAvatar = false)
                }
                CuviAvatarStyle.CLASSIC_IK -> {
                    CuvayMascot(Modifier.fillMaxSize(), mascotTick)
                }
                CuviAvatarStyle.AUTO_DUO -> {
                    val useIk = action in listOf(
                        CuviAction.SOCCER,
                        CuviAction.WALK,
                        CuviAction.DANCE,
                        CuviAction.HEAD_HAND,
                        CuviAction.IDLE
                    )
                    if (useIk) {
                        CuvayMascot(Modifier.fillMaxSize(), mascotTick)
                    } else {
                        Cuvi3DCharacter(Modifier.fillMaxSize(), action = action, animated = true, miniAvatar = false)
                    }
                }
            }
        }
    }
}

/** Compatibilidad: el robot viejo ahora es Cuvi, la mascota cubana (ver CuvayMascot.kt). */
@Composable
fun BotFace(modifier: Modifier, animated: Boolean) {
    if (animated) {
        Cuvi3DCharacter(modifier = modifier, action = CuviAction.IDLE, animated = true, miniAvatar = true)
    } else {
        CuvayMascotHead(modifier)
    }
}

/**
 * CUVI (la chica) ejecutando la acción pedida. Reemplaza al robot 3D: usa el mismo rig/IK
 * de [CuvayMascot] y repite el acto elegido mientras [action] no cambie.
 * El Almendrón conserva su carrito de neón.
 */
@Composable
fun Cuvi3DCharacter(
    modifier: Modifier,
    action: CuviAction,
    animated: Boolean,
    miniAvatar: Boolean = false
) {
    if (miniAvatar) {
        CuvayMascotHead(modifier, animated = animated)
        return
    }
    val act = when (action) {
        CuviAction.DECORATE -> MascotAct.Decorate
        CuviAction.SOCCER -> MascotAct.Kick
        CuviAction.DOMINO_CAPICUA -> MascotAct.Domino
        CuviAction.TRES_CUBANO -> MascotAct.Guitar
        CuviAction.CAFECITO_TURBO -> MascotAct.Cafecito
        CuviAction.DJ_FIESTA -> MascotAct.DjParty
        CuviAction.ALMENDRON_3D -> MascotAct.Walk
        CuviAction.BOXEO_CUBANO -> MascotAct.Boxing
        CuviAction.FIREWORKS_3D -> MascotAct.Cheer
        CuviAction.JUMP_SPIN -> MascotAct.Spin3D
        CuviAction.DANCE -> MascotAct.Dance
        CuviAction.HEAD_HAND -> MascotAct.Head
        CuviAction.WALK -> MascotAct.Walk
        CuviAction.IDLE -> MascotAct.Wave
    }
    Box(modifier) {
        if (action == CuviAction.ALMENDRON_3D) {
            val t = rememberInfiniteTransition(label = "almendron")
            val ph by t.animateFloat(
                0f, (2f * PI).toFloat(),
                infiniteRepeatable(tween(1250, easing = LinearEasing), RepeatMode.Restart),
                label = "ph"
            )
            Canvas(Modifier.fillMaxSize()) {
                drawMiniAlmendron3D(
                    radius = size.width * 0.30f,
                    bounce = sin(ph * 3f) * 4.dp.toPx(),
                    color = NeonPink,
                    centerOverride = Offset(size.width * 0.5f, size.height * 0.90f),
                    alpha = 0.9f
                )
            }
        }
        CuvayMascot(Modifier.fillMaxSize(), forcedAct = act, lockAct = true)
    }
}

// =====================================================================
//  PRIMITIVAS DE DECORACIÓN 3D PARA EMBELLECER TODA LA PANTALLA
// =====================================================================

private fun DrawScope.drawCornerHoloFrames3D(w: Float, h: Float, timeSec: Float) {
    val len = 42.dp.toPx()
    val pad = 8.dp.toPx()
    val pulse = 0.45f + 0.25f * sin(timeSec * 2.5f)
    val c1 = BotCyan.copy(alpha = pulse)
    val c2 = CubaGold.copy(alpha = pulse * 0.85f)
    val sw = 2.2.dp.toPx()

    drawLine(c1, Offset(pad, pad), Offset(pad + len, pad), sw, StrokeCap.Round)
    drawLine(c1, Offset(pad, pad), Offset(pad, pad + len), sw, StrokeCap.Round)
    drawCircle(c2, 3.5.dp.toPx(), Offset(pad, pad))

    drawLine(c1, Offset(w - pad, pad), Offset(w - pad - len, pad), sw, StrokeCap.Round)
    drawLine(c1, Offset(w - pad, pad), Offset(w - pad, pad + len), sw, StrokeCap.Round)
    drawCircle(c2, 3.5.dp.toPx(), Offset(w - pad, pad))

    drawLine(c1, Offset(pad, h - pad), Offset(pad + len, h - pad), sw, StrokeCap.Round)
    drawLine(c1, Offset(pad, h - pad), Offset(pad, h - pad - len), sw, StrokeCap.Round)
    drawCircle(c2, 3.5.dp.toPx(), Offset(pad, h - pad))

    drawLine(c1, Offset(w - pad, h - pad), Offset(w - pad - len, h - pad), sw, StrokeCap.Round)
    drawLine(c1, Offset(w - pad, h - pad), Offset(w - pad, h - pad - len), sw, StrokeCap.Round)
    drawCircle(c2, 3.5.dp.toPx(), Offset(w - pad, h - pad))
}

private fun DrawScope.drawTopFestiveGarland3D(width: Float, timeSec: Float) {
    val segments = 9
    val segW = width / segments
    val palette = listOf(Color(0xFF3A6BFF), Color.White, CubaRed, Color.White)

    for (i in 0 until segments) {
        val x0 = i * segW
        val x1 = (i + 1) * segW
        val midX = (x0 + x1) / 2f
        val sag = 18.dp.toPx() + sin(timeSec * 2f + i) * 4.dp.toPx()

        val cable = Path().apply {
            moveTo(x0, 4.dp.toPx())
            quadraticBezierTo(midX, sag, x1, 4.dp.toPx())
        }
        drawPath(cable, Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.5.dp.toPx()))

        val bulbColor = palette[i % palette.size]
        val pulse = 0.65f + 0.35f * sin(timeSec * 3.5f + i * 0.9f)
        val bulbCenter = Offset(midX, sag * 0.72f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.9f * pulse), bulbColor.copy(alpha = 0.65f * pulse), Color.Transparent),
                center = bulbCenter,
                radius = 12.dp.toPx()
            ),
            radius = 12.dp.toPx(),
            center = bulbCenter
        )
    }
}

private fun DrawScope.drawOctahedronGem3D(
    radius: Float,
    angleY: Float,
    angleX: Float,
    color: Color,
    alpha: Float = 0.85f
) {
    val rawVerts = arrayOf(
        floatArrayOf(0f, -1.25f, 0f),
        floatArrayOf(0f, 1.25f, 0f),
        floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(0f, 0f, -1f)
    )
    val cosY = cos(angleY); val sinY = sin(angleY)
    val cosX = cos(angleX); val sinX = sin(angleX)

    val proj = Array(6) { Offset.Zero }
    for (i in 0 until 6) {
        val vx = rawVerts[i][0]
        val vy = rawVerts[i][1]
        val vz = rawVerts[i][2]
        val x1 = vx * cosY - vz * sinY
        val z1 = vx * sinY + vz * cosY
        val y2 = vy * cosX - z1 * sinX
        val z2 = vy * sinX + z1 * cosX
        val pScale = 2.6f / (2.6f + z2 * 0.35f)
        proj[i] = Offset(x1 * radius * pScale, y2 * radius * pScale)
    }

    val faces = arrayOf(
        intArrayOf(0, 2, 3), intArrayOf(0, 3, 4),
        intArrayOf(1, 2, 3), intArrayOf(1, 3, 4)
    )
    faces.forEachIndexed { idx, f ->
        val p = Path().apply {
            moveTo(proj[f[0]].x, proj[f[0]].y)
            lineTo(proj[f[1]].x, proj[f[1]].y)
            lineTo(proj[f[2]].x, proj[f[2]].y)
            close()
        }
        val shade = if (idx % 2 == 0) 0.42f else 0.24f
        drawPath(p, color.copy(alpha = alpha * shade))
    }

    val edges = arrayOf(
        0 to 2, 0 to 3, 0 to 4, 0 to 5,
        1 to 2, 1 to 3, 1 to 4, 1 to 5,
        2 to 3, 3 to 4, 4 to 5, 5 to 2
    )
    for ((a, b) in edges) {
        drawLine(Color.White.copy(alpha = alpha * 0.75f), proj[a], proj[b], strokeWidth = 1.4.dp.toPx())
    }
}

private fun DrawScope.drawHoloCube3D(
    radius: Float,
    angleX: Float,
    angleY: Float,
    color: Color,
    alpha: Float = 0.85f
) {
    val verts = Array(8) { idx ->
        floatArrayOf(
            if ((idx and 1) == 0) -0.75f else 0.75f,
            if ((idx and 2) == 0) -0.75f else 0.75f,
            if ((idx and 4) == 0) -0.75f else 0.75f
        )
    }
    val cx = cos(angleX); val sx = sin(angleX)
    val cy = cos(angleY); val sy = sin(angleY)
    val proj = Array(8) { Offset.Zero }
    for (i in 0 until 8) {
        val x = verts[i][0]; val y = verts[i][1]; val z = verts[i][2]
        val x1 = x * cy - z * sy
        val z1 = x * sy + z * cy
        val y2 = y * cx - z1 * sx
        val z2 = y * sx + z1 * cx
        val p = 2.8f / (2.8f + z2 * 0.38f)
        proj[i] = Offset(x1 * radius * p, y2 * radius * p)
    }
    val edges = arrayOf(
        0 to 1, 1 to 3, 3 to 2, 2 to 0,
        4 to 5, 5 to 7, 7 to 6, 6 to 4,
        0 to 4, 1 to 5, 2 to 6, 3 to 7
    )
    drawCircle(color.copy(alpha = alpha * 0.25f), radius * 0.75f, Offset.Zero)
    for ((a, b) in edges) {
        drawLine(color.copy(alpha = alpha * 0.85f), proj[a], proj[b], strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
    proj.forEach { pt ->
        drawCircle(Color.White.copy(alpha = alpha * 0.9f), 2.2.dp.toPx(), pt)
    }
}

private fun DrawScope.drawPyramidPrism3D(radius: Float, angleY: Float, color: Color, alpha: Float = 0.85f) {
    val apex = Offset(0f, -radius * 1.15f)
    val basePts = Array(4) { i ->
        val a = angleY + i * (PI.toFloat() / 2f)
        Offset(cos(a) * radius, radius * 0.55f + sin(a) * radius * 0.35f)
    }
    for (i in 0 until 4) {
        val next = basePts[(i + 1) % 4]
        val face = Path().apply {
            moveTo(apex.x, apex.y)
            lineTo(basePts[i].x, basePts[i].y)
            lineTo(next.x, next.y)
            close()
        }
        drawPath(face, color.copy(alpha = alpha * (if (i % 2 == 0) 0.38f else 0.20f)))
        drawLine(Color.White.copy(alpha = alpha * 0.75f), apex, basePts[i], 1.5.dp.toPx())
        drawLine(CubaGold.copy(alpha = alpha * 0.75f), basePts[i], next, 1.5.dp.toPx())
    }
}

private fun DrawScope.drawTorusPortal3D(radius: Float, timeSec: Float, color: Color, alpha: Float = 0.85f) {
    for (ring in 0..2) {
        val r = radius * (1f - ring * 0.24f)
        val rot = timeSec * (if (ring % 2 == 0) 55f else -70f) + ring * 30f
        val squash = 0.42f + 0.25f * abs(sin(timeSec * 1.8f + ring))
        withTransform({
            rotate(rot, Offset.Zero)
            scale(1f, squash, Offset.Zero)
        }) {
            drawCircle(
                if (ring == 1) CubaGold.copy(alpha = alpha * 0.8f) else color.copy(alpha = alpha * 0.8f),
                radius = r,
                center = Offset.Zero,
                style = Stroke(width = 2.2.dp.toPx())
            )
        }
    }
    drawCircle(Color.White.copy(alpha = alpha * 0.9f), radius * 0.18f, Offset.Zero)
}

private fun DrawScope.drawDominoTile3D(radius: Float, tilt: Float, color: Color, alpha: Float = 0.9f) {
    val squash = abs(cos(tilt)).coerceIn(0.35f, 1f)
    val w = radius * 1.15f
    val h = radius * 1.85f
    withTransform({ scale(squash, 1f, Offset.Zero) }) {
        drawRoundRect(
            color.copy(alpha = alpha * 0.7f),
            topLeft = Offset(-w / 2f + 3.dp.toPx(), -h / 2f + 3.dp.toPx()),
            size = Size(w, h),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
        drawRoundRect(
            Color.White.copy(alpha = alpha),
            topLeft = Offset(-w / 2f, -h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
        drawRoundRect(
            CubaGold.copy(alpha = alpha),
            topLeft = Offset(-w / 2f, -h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(1.5.dp.toPx())
        )
        drawLine(Color.Black.copy(alpha = alpha * 0.7f), Offset(-w * 0.4f, 0f), Offset(w * 0.4f, 0f), 1.5.dp.toPx())
        for (dx in intArrayOf(-1, 0, 1)) {
            for (dy in intArrayOf(-1, 1)) {
                drawCircle(CubaBlue.copy(alpha = alpha), radius * 0.09f, Offset(dx * w * 0.25f, -h * 0.24f + dy * h * 0.10f))
                drawCircle(CubaRed.copy(alpha = alpha), radius * 0.09f, Offset(dx * w * 0.25f, h * 0.24f + dy * h * 0.10f))
            }
        }
    }
}

private fun DrawScope.drawMiniAlmendron3D(
    radius: Float,
    bounce: Float,
    color: Color,
    centerOverride: Offset = Offset.Zero,
    alpha: Float = 0.9f
) {
    withTransform({ translate(centerOverride.x, centerOverride.y + bounce) }) {
        val w = radius * 1.6f
        val h = radius * 0.65f
        val body = Path().apply {
            moveTo(-w * 0.52f, h * 0.25f)
            lineTo(-w * 0.48f, -h * 0.18f)
            lineTo(-w * 0.22f, -h * 0.22f)
            lineTo(-w * 0.10f, -h * 0.62f)
            lineTo(w * 0.20f, -h * 0.62f)
            lineTo(w * 0.32f, -h * 0.18f)
            lineTo(w * 0.52f, -h * 0.08f)
            lineTo(w * 0.52f, h * 0.25f)
            close()
        }
        drawPath(body, Brush.horizontalGradient(listOf(color.copy(alpha = alpha), BotCyan.copy(alpha = alpha))))
        drawPath(body, Color.White.copy(alpha = alpha * 0.85f), style = Stroke(1.6.dp.toPx()))
        for (wx in floatArrayOf(-w * 0.30f, w * 0.30f)) {
            drawCircle(Color(0xFF101828).copy(alpha = alpha), radius * 0.22f, Offset(wx, h * 0.28f))
            drawCircle(Color.White.copy(alpha = alpha), radius * 0.12f, Offset(wx, h * 0.28f))
            drawCircle(CubaGold.copy(alpha = alpha), radius * 0.06f, Offset(wx, h * 0.28f))
        }
    }
}

private fun DrawScope.drawMaracasPair3D(radius: Float, shake: Float, color: Color, alpha: Float = 0.88f) {
    for (s in intArrayOf(-1, 1)) {
        withTransform({ rotate(s * 28f + shake * s, Offset.Zero) }) {
            drawLine(CubaGold.copy(alpha = alpha), Offset(0f, radius * 0.8f), Offset(0f, -radius * 0.2f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawOval(
                Brush.radialGradient(listOf(Color.White, color.copy(alpha = alpha), CubaRed.copy(alpha = alpha))),
                topLeft = Offset(-radius * 0.38f, -radius * 0.85f),
                size = Size(radius * 0.76f, radius * 0.95f)
            )
        }
    }
}

private fun DrawScope.drawRoyalCrown3D(radius: Float, spin: Float, color: Color, alpha: Float = 0.9f) {
    val squash = abs(cos(spin)).coerceIn(0.45f, 1f)
    withTransform({ scale(squash, 1f, Offset.Zero) }) {
        val p = Path().apply {
            moveTo(-radius * 0.85f, radius * 0.45f)
            lineTo(-radius * 0.95f, -radius * 0.35f)
            lineTo(-radius * 0.42f, radius * 0.05f)
            lineTo(0f, -radius * 0.65f)
            lineTo(radius * 0.42f, radius * 0.05f)
            lineTo(radius * 0.95f, -radius * 0.35f)
            lineTo(radius * 0.85f, radius * 0.45f)
            close()
        }
        drawPath(p, Brush.verticalGradient(listOf(CubaGold.copy(alpha = alpha), Color(0xFFD9822B).copy(alpha = alpha))))
        drawPath(p, Color.White.copy(alpha = alpha * 0.8f), style = Stroke(1.5.dp.toPx()))
        drawCircle(color.copy(alpha = alpha), radius * 0.14f, Offset(0f, -radius * 0.65f))
        drawCircle(CubaRed.copy(alpha = alpha), radius * 0.12f, Offset(-radius * 0.95f, -radius * 0.35f))
        drawCircle(BotCyan.copy(alpha = alpha), radius * 0.12f, Offset(radius * 0.95f, -radius * 0.35f))
    }
}

private fun DrawScope.drawHeartGem3D(radius: Float, pulse: Float, color: Color, alpha: Float = 0.88f) {
    withTransform({ scale(pulse, pulse, Offset.Zero) }) {
        val p = Path().apply {
            moveTo(0f, radius * 0.75f)
            cubicTo(-radius * 1.1f, radius * 0.1f, -radius * 0.9f, -radius * 0.85f, 0f, -radius * 0.30f)
            cubicTo(radius * 0.9f, -radius * 0.85f, radius * 1.1f, radius * 0.1f, 0f, radius * 0.75f)
            close()
        }
        drawPath(p, Brush.radialGradient(listOf(Color.White.copy(alpha = alpha), color.copy(alpha = alpha), CubaRed.copy(alpha = alpha))))
    }
}

private fun DrawScope.drawCoffeeCupParticle3D(radius: Float, color: Color, alpha: Float) {
    drawRoundRect(
        Color.White.copy(alpha = alpha),
        topLeft = Offset(-radius * 0.65f, -radius * 0.5f),
        size = Size(radius * 1.3f, radius * 1.1f),
        cornerRadius = CornerRadius(radius * 0.25f)
    )
    drawRoundRect(
        color.copy(alpha = alpha),
        topLeft = Offset(-radius * 0.65f, -radius * 0.1f),
        size = Size(radius * 1.3f, radius * 0.32f),
        cornerRadius = CornerRadius(radius * 0.1f)
    )
}

private fun DrawScope.drawFireworkSpark3D(radius: Float, color: Color, alpha: Float) {
    for (i in 0 until 8) {
        val a = i * (PI.toFloat() / 4f)
        drawLine(
            Brush.linearGradient(listOf(Color.White.copy(alpha = alpha), color.copy(alpha = alpha), Color.Transparent)),
            Offset.Zero,
            Offset(cos(a) * radius, sin(a) * radius),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawLightningBolt3D(radius: Float, color: Color, alpha: Float) {
    val p = Path().apply {
        moveTo(radius * 0.2f, -radius)
        lineTo(-radius * 0.45f, radius * 0.05f)
        lineTo(radius * 0.05f, radius * 0.05f)
        lineTo(-radius * 0.25f, radius)
        lineTo(radius * 0.5f, -radius * 0.1f)
        lineTo(0f, -radius * 0.1f)
        close()
    }
    drawPath(p, color.copy(alpha = alpha))
    drawPath(p, Color.White.copy(alpha = alpha * 0.8f), style = Stroke(1.2.dp.toPx()))
}

private fun DrawScope.drawVinylDisc3D(radius: Float, color: Color, alpha: Float) {
    drawCircle(Color(0xFF101828).copy(alpha = alpha), radius = radius, center = Offset.Zero)
    drawCircle(color.copy(alpha = alpha), radius = radius * 0.42f, center = Offset.Zero)
    drawCircle(Color.White.copy(alpha = alpha), radius = radius * 0.14f, center = Offset.Zero)
}

private fun DrawScope.drawBoxingGloveParticle3D(radius: Float, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFF6B7A).copy(alpha = alpha), color.copy(alpha = alpha))),
        radius = radius * 0.85f,
        center = Offset.Zero
    )
    drawRoundRect(
        CubaGold.copy(alpha = alpha),
        topLeft = Offset(-radius * 0.55f, radius * 0.35f),
        size = Size(radius * 1.1f, radius * 0.42f),
        cornerRadius = CornerRadius(radius * 0.15f)
    )
}

private fun DrawScope.drawTropicalPalm3D(radius: Float, sway: Float, color: Color) {
    withTransform({ rotate(sway, Offset(0f, radius)) }) {
        val trunk = Path().apply {
            moveTo(0f, radius)
            quadraticBezierTo(radius * 0.15f, radius * 0.2f, 0f, -radius * 0.2f)
        }
        drawPath(trunk, CubaGold.copy(alpha = 0.7f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

        val angles = listOf(-65f, -30f, 15f, 55f, 95f)
        angles.forEach { a ->
            val rad = a * (PI / 180.0).toFloat()
            val tip = Offset(cos(rad) * radius * 0.95f, -radius * 0.2f + sin(rad) * radius * 0.65f)
            val leaf = Path().apply {
                moveTo(0f, -radius * 0.2f)
                quadraticBezierTo(tip.x * 0.5f, tip.y - radius * 0.25f, tip.x, tip.y)
            }
            drawPath(leaf, color.copy(alpha = 0.78f), style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawGlassBubble3D(radius: Float, color: Color, alpha: Float = 0.8f) {
    drawCircle(
        Brush.radialGradient(
            listOf(Color.Transparent, color.copy(alpha = alpha * 0.35f), Color.White.copy(alpha = alpha * 0.75f)),
            center = Offset.Zero,
            radius = radius
        ),
        radius = radius,
        center = Offset.Zero
    )
    drawOval(
        Color.White.copy(alpha = alpha * 0.85f),
        topLeft = Offset(-radius * 0.55f, -radius * 0.62f),
        size = Size(radius * 0.45f, radius * 0.25f)
    )
}

private fun DrawScope.drawNeonFlower3D(radius: Float, rotDeg: Float, color: Color) {
    withTransform({ rotate(rotDeg, Offset.Zero) }) {
        for (i in 0 until 6) {
            val a = (i * 60.0) * (PI / 180.0)
            val cx = (cos(a) * radius * 0.55f).toFloat()
            val cy = (sin(a) * radius * 0.55f).toFloat()
            drawCircle(color.copy(alpha = 0.55f), radius = radius * 0.42f, center = Offset(cx, cy))
        }
        drawCircle(CubaGold, radius = radius * 0.28f, center = Offset.Zero)
    }
}

private fun DrawScope.drawCubanCap3D(topLeft: Offset, size: Size) {
    val x = topLeft.x
    val y = topLeft.y
    val w = size.width
    val h = size.height

    val crownPath = Path().apply {
        moveTo(x + w * 0.06f, y + h * 0.78f)
        cubicTo(
            x + w * 0.08f, y - h * 0.05f,
            x + w * 0.88f, y - h * 0.05f,
            x + w * 0.90f, y + h * 0.78f
        )
        close()
    }
    drawPath(
        crownPath,
        Brush.linearGradient(
            listOf(Color(0xFF003EC2), CubaBlue, Color(0xFF001858)),
            start = Offset(x, y),
            end = Offset(x + w, y + h)
        )
    )

    drawArc(
        Color.White.copy(alpha = 0.9f),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(x + w * 0.14f, y + h * 0.22f),
        size = Size(w * 0.68f, h * 0.55f),
        style = Stroke(width = h * 0.10f, cap = StrokeCap.Round)
    )

    val tri = Path().apply {
        moveTo(x + w * 0.48f, y + h * 0.08f)
        lineTo(x + w * 0.24f, y + h * 0.76f)
        lineTo(x + w * 0.72f, y + h * 0.76f)
        close()
    }
    drawPath(tri, CubaRed)
    drawStar3D(Offset(x + w * 0.48f, y + h * 0.50f), h * 0.22f, Color.White, 1f)
    drawCircle(CubaRed, radius = h * 0.09f, center = Offset(x + w * 0.48f, y + h * 0.06f))

    val visorPath = Path().apply {
        moveTo(x + w * 0.02f, y + h * 0.74f)
        quadraticBezierTo(x + w * 0.62f, y + h * 0.58f, x + w * 1.08f, y + h * 0.82f)
        quadraticBezierTo(x + w * 0.60f, y + h * 0.96f, x + w * 0.02f, y + h * 0.80f)
        close()
    }
    drawPath(visorPath, Brush.horizontalGradient(listOf(CubaBlue, Color(0xFF001A5C), BotCyan)))
}

private fun DrawScope.drawSoccerBall3D(center: Offset, radius: Float, rotationDeg: Float, alpha: Float) {
    drawCircle(
        Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = alpha),
                Color(0xFFDCE6F2).copy(alpha = alpha),
                Color(0xFF7B8FA8).copy(alpha = alpha)
            ),
            center = Offset(center.x - radius * 0.3f, center.y - radius * 0.3f),
            radius = radius * 1.3f
        ),
        radius = radius,
        center = center
    )

    val rad = rotationDeg * (PI / 180.0).toFloat()
    val cx = center.x + cos(rad) * radius * 0.22f
    val cy = center.y + sin(rad) * radius * 0.18f
    drawRegularPolygon(Offset(cx, cy), radius * 0.34f, sides = 5, rotation = rotationDeg, color = Color(0xFF101828).copy(alpha = alpha))

    for (i in 0 until 5) {
        val a = rad + i * (2f * PI.toFloat() / 5f)
        val px = center.x + cos(a) * radius * 0.72f
        val py = center.y + sin(a) * radius * 0.72f
        drawCircle(Color(0xFF101828).copy(alpha = alpha * 0.85f), radius = radius * 0.20f, center = Offset(px, py))
        drawLine(
            Color(0xFF101828).copy(alpha = alpha * 0.5f),
            Offset(cx, cy),
            Offset(px, py),
            strokeWidth = radius * 0.07f
        )
    }

    drawCircle(
        Color(0xFF0B1330).copy(alpha = alpha * 0.8f),
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.08f)
    )
}

private fun DrawScope.drawMiniCubanFlag3D(topLeft: Offset, size: Size, wavePhase: Float, alpha: Float) {
    val stripeH = size.height / 5f
    val wave = sin(wavePhase) * size.height * 0.10f

    for (i in 0 until 5) {
        val c = if (i % 2 == 0) CubaBlue else Color.White
        val p = Path().apply {
            val y0 = topLeft.y + i * stripeH
            moveTo(topLeft.x, y0 + wave)
            quadraticBezierTo(topLeft.x + size.width * 0.5f, y0 - wave, topLeft.x + size.width, y0)
            lineTo(topLeft.x + size.width, y0 + stripeH)
            quadraticBezierTo(topLeft.x + size.width * 0.5f, y0 + stripeH - wave, topLeft.x, y0 + stripeH + wave)
            close()
        }
        drawPath(p, c.copy(alpha = alpha))
    }

    val tri = Path().apply {
        moveTo(topLeft.x + size.width, topLeft.y)
        lineTo(topLeft.x + size.width, topLeft.y + size.height)
        lineTo(topLeft.x + size.width * 0.42f, topLeft.y + size.height * 0.5f - wave * 0.4f)
        close()
    }
    drawPath(tri, CubaRed.copy(alpha = alpha))
    drawStar3D(
        Offset(topLeft.x + size.width * 0.78f, topLeft.y + size.height * 0.5f),
        size.height * 0.20f,
        Color.White,
        alpha
    )
}

private fun DrawScope.drawSneaker3D(topLeft: Offset, size: Size, isLeft: Boolean) {
    drawRoundRect(
        Brush.horizontalGradient(if (isLeft) listOf(CubaRed, CubaBlue) else listOf(CubaBlue, CubaRed)),
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(size.height * 0.45f)
    )
    drawRoundRect(
        Color.White,
        topLeft = Offset(topLeft.x - size.width * 0.05f, topLeft.y + size.height * 0.62f),
        size = Size(size.width * 1.10f, size.height * 0.38f),
        cornerRadius = CornerRadius(size.height * 0.20f)
    )
}

private fun DrawScope.drawStar3D(center: Offset, radius: Float, color: Color, alpha: Float) {
    val path = Path()
    val innerRadius = radius * 0.42f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else innerRadius
        val angle = (i * 36.0 - 90.0) * (PI / 180.0)
        val px = center.x + (cos(angle) * r).toFloat()
        val py = center.y + (sin(angle) * r).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color.copy(alpha = alpha))
}

private fun DrawScope.drawRegularPolygon(center: Offset, radius: Float, sides: Int, rotation: Float, color: Color) {
    val path = Path()
    for (i in 0 until sides) {
        val angle = (i * (360.0 / sides) + rotation) * (PI / 180.0)
        val px = center.x + (cos(angle) * radius).toFloat()
        val py = center.y + (sin(angle) * radius).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawMusicNote3D(center: Offset, size: Float, color: Color, alpha: Float) {
    val c = color.copy(alpha = alpha)
    drawCircle(c, radius = size * 0.35f, center = Offset(center.x - size * 0.25f, center.y + size * 0.3f))
    drawCircle(c, radius = size * 0.35f, center = Offset(center.x + size * 0.35f, center.y + size * 0.15f))
    drawLine(c, Offset(center.x - size * 0.02f, center.y + size * 0.3f), Offset(center.x - size * 0.02f, center.y - size * 0.55f), strokeWidth = size * 0.16f, cap = StrokeCap.Round)
    drawLine(c, Offset(center.x + size * 0.58f, center.y + size * 0.15f), Offset(center.x + size * 0.58f, center.y - size * 0.70f), strokeWidth = size * 0.16f, cap = StrokeCap.Round)
    drawLine(c, Offset(center.x - size * 0.02f, center.y - size * 0.55f), Offset(center.x + size * 0.58f, center.y - size * 0.70f), strokeWidth = size * 0.22f, cap = StrokeCap.Round)
}