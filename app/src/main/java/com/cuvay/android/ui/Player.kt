package com.cuvay.android.ui

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Reproductor propio: MediaPlayer + TextureView (no SurfaceView, asi no parpadea ni se
 * pinta encima al hacer scroll dentro de la lista). Sirve para video y audio.
 * [cover] es la miniatura: en audio se muestra como portada, en video mientras prepara.
 */
@Composable
fun InlinePlayer(uri: Uri, video: Boolean, cover: ImageBitmap? = null) {
    val ctx = LocalContext.current
    var ready by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var pos by remember(uri) { mutableStateOf(0) }
    var dur by remember(uri) { mutableStateOf(1) }
    var vw by remember(uri) { mutableStateOf(0) }
    var vh by remember(uri) { mutableStateOf(0) }
    var drag by remember(uri) { mutableStateOf<Float?>(null) }
    var surface by remember(uri) { mutableStateOf<Surface?>(null) }

    val mp = remember(uri) {
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build())
            setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h }
            setOnPreparedListener { p ->
                dur = p.duration.coerceAtLeast(1); ready = true
                p.start(); playing = true
            }
            setOnCompletionListener { playing = false; pos = dur }
            setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
        }
    }
    // Prepara en segundo plano (no bloquea la UI).
    LaunchedEffect(mp) {
        runCatching { mp.setDataSource(ctx, uri); mp.prepareAsync() }.onFailure { failed = true }
    }
    // Conecta/desconecta la superficie del TextureView.
    LaunchedEffect(surface) { runCatching { mp.setSurface(surface) } }
    DisposableEffect(mp) { onDispose { runCatching { mp.release() } } }
    // Pausa al salir de la app.
    DisposableEffect(mp) {
        val owner = ctx as? LifecycleOwner
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && ready) runCatching { if (mp.isPlaying) { mp.pause(); playing = false } }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    // Avanza la barra.
    LaunchedEffect(playing, drag) {
        while (playing && drag == null) {
            pos = runCatching { mp.currentPosition }.getOrDefault(pos)
            delay(250)
        }
    }

    fun toggle() {
        if (!ready) return
        runCatching {
            if (mp.isPlaying) { mp.pause(); playing = false }
            else {
                if (pos >= dur - 300) { mp.seekTo(0); pos = 0 }
                mp.start(); playing = true
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        val ratio = if (video && vw > 0 && vh > 0) vw.toFloat() / vh else 16f / 9f
        Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 2.4f)).background(Color.Black)
            .pointerInput(ready) { detectTapGestures { toggle() } },
            contentAlignment = Alignment.Center) {
            if (video) {
                AndroidView(
                    factory = { c ->
                        TextureView(c).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    runCatching { mp.setSurface(null) }
                                    surface?.release(); surface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Portada: audio siempre; video solo mientras prepara.
            if (cover != null && (!video || !ready)) {
                Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (!video) {
                Text("AUDIO", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            if (failed) Text("No se pudo reproducir", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Red).padding(horizontal = 12.dp, vertical = 8.dp))
            else if (!ready) Text("CARGANDO...", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp))
            else if (!playing) Text("PLAY", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.background(Red).padding(horizontal = 18.dp, vertical = 12.dp))
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFFEFF2FA)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (playing) "PAUSA" else "PLAY", Modifier.background(Red).clickable { toggle() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val frac = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
                // Barra: toque = saltar; arrastrar = buscar. Alto de 28dp para que sea facil de tocar.
                Box(Modifier.fillMaxWidth().height(28.dp)
                    .pointerInput(ready, dur) {
                        detectTapGestures { o ->
                            if (!ready) return@detectTapGestures
                            val t = (o.x / size.width * dur).toInt().coerceIn(0, dur)
                            runCatching { mp.seekTo(t) }; pos = t
                        }
                    }
                    .pointerInput(ready, dur) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> if (ready) drag = (o.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { drag?.let { f -> val t = (f * dur).toInt(); runCatching { mp.seekTo(t) }; pos = t }; drag = null },
                            onDragCancel = { drag = null },
                            onHorizontalDrag = { change, _ -> if (drag != null) drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                        )
                    },
                    contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFFCBD3E6))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Blue))
                    }
                }
                Text("${fmtDur((frac * dur).toLong())} / ${fmtDur(dur.toLong())}", color = Muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}
