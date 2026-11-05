package com.cuvay.android.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.core.util.Consumer
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AVISOS INSTANTANEOS: publicaciones de canales, mensajes privados y chat global.
 * Un servicio en primer plano (AlertsService) mantiene la conexion viva:
 *  - chat global: long-poll (el servidor contesta en cuanto llega un mensaje)
 *  - privados: revisa cada 3 s
 *  - canales: lee el servidor local cada 10 s y le pide refrescar cada 30 s
 * Con la app en pantalla no suena nada (ya lo estas viendo), pero se actualiza el "ya visto".
 * NotifyWorker (cada ~15 min) queda de respaldo.
 */
object Alerts {
    @Volatile var foreground = false
    var tapTarget by mutableStateOf<String?>(null)

    private const val P = "cuvay_ui"
    const val CH_FEED = "cuvay_feed"
    const val CH_DM = "cuvay_dm"
    const val CH_GLOBAL = "cuvay_global"
    const val CH_SERVICE = "cuvay_service"

    // Ajusta estos tiempos si quieres mas o menos consumo de bateria.
    const val DM_EVERY_MS = 3_000L
    const val FEED_EVERY_MS = 5_000L
    const val FEED_REFRESH_EVERY_MS = 10_000L
    const val GLOBAL_WAIT_S = 25

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
    fun enabled(ctx: Context, k: String) = prefs(ctx).getBoolean("al_on_$k", true)
    fun setEnabled(ctx: Context, k: String, v: Boolean) { prefs(ctx).edit().putBoolean("al_on_$k", v).apply() }

    fun localApi(ctx: Context): Api? =
        prefs(ctx).getString("al_base", null)?.takeIf { it.isNotBlank() }?.let { Api(it) }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_FEED, "Publicaciones de canales", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DM, "Mensajes privados", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_GLOBAL, "Chat global", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Conexión de avisos", NotificationManager.IMPORTANCE_MIN))
    }

    fun show(ctx: Context, ch: String, id: Int, title: String, body: String, target: String = "") {
    val nm = NotificationManagerCompat.from(ctx)
    if (!nm.areNotificationsEnabled()) return
    val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
        if (target.isNotEmpty()) putExtra("cuvay_target", target) // Inyectamos la ruta
    }
    val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val n = NotificationCompat.Builder(ctx, ch)
        .setSmallIcon(ctx.applicationInfo.icon)
        .setContentTitle(title).setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(if (ch == CH_DM) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true).setContentIntent(pi).build()
    runCatching { nm.notify(id, n) }
}

    /** Revision unica (la usa el Worker con la app cerrada). Bloqueante, nunca lanza. */
    fun check(ctx: Context, api: Api?) {
        ensureChannels(ctx)
        runCatching { dm(ctx) }
        runCatching { global(ctx, 0) }
        if (api != null) runCatching { feed(ctx, api) }
    }

    // ---------------- publicaciones de canales ----------------
    private fun summary(m: Api.Msg): String {
        val cap = m.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(140)
        if (cap.isNotEmpty()) return cap
        val ks = m.media.filter { !(it.kind == "IMAGE" && it.name.startsWith("thumb_")) }.map { it.kind }
        return when {
            "VIDEO" in ks -> "🎬 Video"
            "AUDIO" in ks -> "🎵 Audio"
            "PHOTO" in ks || "IMAGE" in ks -> "📷 Foto"
            ks.isNotEmpty() -> "📎 Archivo"
            else -> "Nueva publicación"
        }
    }

    fun feed(ctx: Context, api: Api) {
        if (!enabled(ctx, "feed")) return
        val quiet = false
        val p = prefs(ctx)
        for (c in api.channels()) {
            val key = "al_f_" + c.name.lowercase()
            val msgs = api.messages(c.num)
            val maxId = msgs.maxOfOrNull { it.id } ?: continue
            if (!p.contains(key)) { p.edit().putLong(key, maxId).apply(); continue }   // primera vez: solo marca lo existente
            val last = p.getLong(key, 0)
            val fresh = msgs.filter { it.id > last }
            if (fresh.isEmpty()) continue
            p.edit().putLong(key, maxId).apply()
            if (quiet) continue
            val newest = fresh.maxByOrNull { it.id } ?: continue
            val title = if (fresh.size > 1) "${c.title} · ${fresh.size} nuevas" else c.title
            show(ctx, CH_FEED, 10_000 + c.num, title, summary(newest), "feed:${c.num}")
        }
    }

    // ---------------- chat: privados y global ----------------
    private fun kindText(kind: String, text: String) = when (kind) {
        "text", "sticker" -> text
        "simg" -> "Sticker"
        "image", "photo" -> "📷 Foto"
        "video" -> "🎬 Video"
        "audio" -> "🎵 Audio"
        "location" -> "📍 Ubicación"
        else -> "📎 Archivo"
    }.take(160)

    private fun session(ctx: Context): Pair<ChatApi, ChatSession>? {
        val s = loadSession(ctx)
        return if (s.token.isEmpty()) null else ChatApi(s.url, s.token) to s
    }

    /** Privados: 1 llamada barata. Avisa de hilos con mensaje nuevo que no es tuyo. */
    fun dm(ctx: Context) {
        if (!enabled(ctx, "dm")) return
        val (api, _) = session(ctx) ?: return
        val p = prefs(ctx)
        val quiet = false
        val first = !p.getBoolean("al_dm_init", false)
        for (t in api.threads()) {
            val key = "al_d_" + t.peer.lowercase()
            val last = p.getLong(key, 0)
            if (t.ts > last) p.edit().putLong(key, t.ts).apply()
            if (first || quiet || t.mine || t.deleted || t.unread <= 0 || t.ts <= last) continue
            val title = if (t.unread > 1) "${t.peer} (${t.unread})" else t.peer
            show(ctx, CH_DM, 20_000 + (t.peer.lowercase().hashCode() and 0xFFFF), title, kindText(t.kind, t.text), "dm:${t.peer}")
        }
        if (first) p.edit().putBoolean("al_dm_init", true).apply()
    }

    /** Trae mensajes nuevos; la primera pagina espera hasta [wait] s (long-poll), las demas no. */
    private fun drain(api: ChatApi, after: Long, pages: Int, wait: Int): List<CMsg> {
        val out = mutableListOf<CMsg>(); var cur = after; var w = wait
        repeat(pages) {
            val r = api.poll("global", cur, 0, w)
            w = 0
            if (r.messages.isEmpty()) return out
            out += r.messages; cur = r.messages.maxOf { it.id }
            if (r.messages.size < 60) return out
        }
        return out
    }

    /** Chat global. wait > 0 = long-poll: vuelve al instante cuando llega algo. */
    fun global(ctx: Context, wait: Int) {
        if (!enabled(ctx, "global")) return
        val (api, s) = session(ctx) ?: return
        val p = prefs(ctx)
        val key = "al_g_last"
        if (!p.contains(key)) {   // primera vez: se pone al dia sin avisar
            p.edit().putLong(key, drain(api, 0, 30, 0).maxOfOrNull { it.id } ?: 0L).apply()
            return
        }
        val got = drain(api, p.getLong(key, 0), 5, wait)
        if (got.isEmpty()) return
        p.edit().putLong(key, got.maxOf { it.id }).apply()

        val now = System.currentTimeMillis() / 1000
        val me = s.name.lowercase()
        val fresh = got.filter { !it.deleted && it.from.lowercase() != me && it.ts > now - 900 }
        if (fresh.isEmpty()) return
        val body = fresh.takeLast(4).joinToString("\n") { it.from + ": " + kindText(it.kind, it.text) }
        show(ctx, CH_GLOBAL, 30_000, if (fresh.size > 1) "Chat global · ${fresh.size} mensajes" else "Chat global", body, "global")
    }

    // ---------------- servicio ----------------
    fun startService(ctx: Context) {
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AlertsService::class.java)) }
    }
}

/**
 * Servicio en primer plano que mantiene los avisos al instante, incluso con la pantalla apagada.
 * Muestra una notificacion silenciosa y minima (Android lo exige).
 */
class AlertsService : Service() {
    private var scope: CoroutineScope? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Alerts.ensureChannels(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, Alerts.CH_SERVICE)
            .setSmallIcon(applicationInfo.icon).setContentTitle("Cuvay")
            .setContentText("Avisos activos").setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(pi).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, 77, n, 0x40000000 /* specialUse */)
            else startForeground(77, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }

        if (scope == null) {
            runCatching {
                wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cuvay:alerts").apply { setReferenceCounted(false); acquire() }
            }
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO); scope = sc
            val ctx = applicationContext
            // Chat global: long-poll.
            sc.launch {
                while (isActive) {
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { Alerts.global(ctx, Alerts.GLOBAL_WAIT_S) }.isSuccess
                    // Si fallo o el servidor contesto vacio al instante, espera un poco para no martillar.
                    delay(if (!ok) 5_000 else if (System.currentTimeMillis() - t0 < 1_000) 2_000 else 200)
                }
            }
            // Privados.
            sc.launch {
                while (isActive) {
                    runCatching { Alerts.dm(ctx) }
                    delay(Alerts.DM_EVERY_MS)
                }
            }
            // Canales (servidor local).
            sc.launch {
                delay(15_000)   // deja arrancar el servidor local
                var lastRefresh = 0L
                while (isActive) {
                    val api = Alerts.localApi(ctx)
                    if (api != null) {
                        val now = System.currentTimeMillis()
                        if (Alerts.enabled(ctx, "feed") && now - lastRefresh > Alerts.FEED_REFRESH_EVERY_MS) {
                            lastRefresh = now; runCatching { api.refresh() }
                        }
                        runCatching { Alerts.feed(ctx, api) }
                    }
                    delay(Alerts.FEED_EVERY_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel(); scope = null
        runCatching { wl?.release() }
        super.onDestroy()
    }
}

/** Ponlo UNA vez en CuvayApp: arranca el servicio y avisa si la app esta en pantalla. */

@Composable
fun AlertsHost(api: Api, baseUrl: String) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        val t = (ctx as? android.app.Activity)?.intent?.getStringExtra("cuvay_target")
        if (t != null) { Alerts.tapTarget = t; (ctx as? android.app.Activity)?.intent?.removeExtra("cuvay_target") }
    }
    DisposableEffect(ctx) {
        val activity = ctx as? ComponentActivity
        val listener = Consumer<Intent> { intent ->
            val t = intent.getStringExtra("cuvay_target")
            if (t != null) { Alerts.tapTarget = t; intent.removeExtra("cuvay_target") }
        }
        activity?.addOnNewIntentListener(listener)
        val owner = ctx as? LifecycleOwner
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) Alerts.foreground = true
            else if (e == Lifecycle.Event.ON_STOP) Alerts.foreground = false
        }
        Alerts.foreground = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
        owner?.lifecycle?.addObserver(obs)
        onDispose {
            activity?.removeOnNewIntentListener(listener)
            owner?.lifecycle?.removeObserver(obs)
            Alerts.foreground = false
        }
    }
    LaunchedEffect(Unit) {
        ctx.getSharedPreferences("cuvay_ui", Context.MODE_PRIVATE).edit().putString("al_base", baseUrl).apply()
        Alerts.ensureChannels(ctx)
        Alerts.startService(ctx)
    }
}

/** Seccion "AVISOS" para Ajustes. */
@Composable
fun AlertsSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Section("AVISOS")
    @Composable fun row(label: String, key: String) {
        val on = remember(tick) { Alerts.enabled(ctx, key) }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Btn(if (on) "ACTIVADO" else "APAGADO", if (on) Blue else Muted) { Alerts.setEnabled(ctx, key, !on); tick++ }
        }
    }
    row("Publicaciones de canales", "feed")
    row("Mensajes privados", "dm")
    row("Chat global", "global")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn("PROBAR AVISO", Ink) {
            Alerts.ensureChannels(ctx)
            Alerts.show(ctx, Alerts.CH_DM, 29_999, "Cuvay", "TitoMX Project 🇨🇺")
        }
        Btn("SIN LÍMITE DE BATERÍA", Red) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Text("Para que lleguen al instante con la pantalla apagada, pon Cuvay en «Sin restricciones» de batería.",
        color = Soft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}
