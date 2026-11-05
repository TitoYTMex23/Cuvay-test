package com.cuvay.android.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cuvay.android.AndroidBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// =====================================================================
//  Vias de descarga de archivos:
//   TURBO = relay del servidor (rapida, necesita internet con datos)
//   NUBE  = relay de GitHub, por el Worker de Cloudflare (rapida, archivos pequenos)
//   DNS   = por consultas DNS (lenta, sirve sin datos ni wifi con cuenta)
//   EDU   = relay rapido pasando por el proxy de la red universitaria.
//           La cuenta NO va en la app: se la pide al servidor (panel) al elegir esta via.
//  Todo lo que arma la URL de descarga (Api.Media.url()) pasa por DlConfig.
// =====================================================================

enum class DlRoute(val label: String, val hint: String, val tag: String, val note: String) {
    AUTO("AUTOMÁTICO", "Recomendado. La app elige sola la mejor vía para cada archivo.",
        "AUTOMÁTICO", "La app elige la mejor vía por ti."),
    TURBO("TURBO", "Directa desde el servidor de Cuvay. Rápida. Gasta datos.",
        "TURBO", "Directa desde el servidor. Rápida. Gasta datos."),
    NUBE("NUBE", "Por la nube. Rápida, ideal para archivos pequeños. Gasta datos.",
        "NUBE", "Por la nube. Rápida, para archivos pequeños. Gasta datos."),
    DNS("LENTA ", "Para cuando no hay datos ni wifi con cuenta. Muy lenta, pero llega.",
        "LENTA", "No gasta datos. Muy lenta, pero llega."),
    EDU("UNIVERSIDAD (PROXY)", "Usa el proxy de la red universitaria. La cuenta la da el servidor.",
        "UNIVERSIDAD", "Rápida y gratis, por el proxy de la universidad.")
}

data class EduProxy(val host: String, val user: String, val pass: String) {
    /** http://usuario:clave@host:puerto (usuario y clave codificados). */
    fun url(): String {
        val h = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val u = URLEncoder.encode(user, "UTF-8")
        val p = URLEncoder.encode(pass, "UTF-8")
        return if (user.isBlank()) "http://$h" else "http://$u:$p@$h"
    }
}

object DlConfig {
    private const val PREFS = "cuvay_ui"
    private const val K_ROUTE = "dl_route"
    private const val K_ASK = "dl_ask"
    private const val K_PHOTO = "photo_route"

    /** Via para las fotos del feed (AUTO = sigue la via habitual, con paso a DNS si falla). */
    @Volatile var photoRoute: DlRoute = DlRoute.AUTO
    fun setPhotoRoute(ctx: Context, r: DlRoute) {
        photoRoute = r
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_PHOTO, r.name).apply()
    }
    /** Aplica la via de fotos a una foto concreta (se llama una vez por foto). */
    fun applyPhotoRoute(m: Api.Media) { if (photoRoute == DlRoute.AUTO) chosen.remove(key(m)) else chosen[key(m)] = photoRoute }

    @Volatile var route: DlRoute = DlRoute.AUTO
    @Volatile var edu: EduProxy? = null
    /** Base alterna de GitHub (Cloudflare Worker, p. ej. https://gh.midominio.com). Vacia = api.github.com directo. La pone el panel. */
    @Volatile var relayBase: String = ""
    /** Lo pone CuvayApp al iniciar: sirve para cambiar el proxy del nucleo justo antes de cada descarga. */
    @Volatile var api: Api? = null
    /** true = antes de descargar un archivo se pregunta por que via. false = se usa la via habitual. */
    @Volatile var askBefore: Boolean = true

    /** Via elegida para cada archivo (en esta sesion). Tiene prioridad sobre la via habitual. */
    private val chosen = ConcurrentHashMap<String, DlRoute>()

    /** Vias que ya fallaron para cada archivo (para no dar vueltas en circulo). */
    private val tried = ConcurrentHashMap<String, MutableSet<DlRoute>>()
    fun triedFor(m: Api.Media): Set<DlRoute> = tried[key(m)]?.toSet() ?: emptySet()
    fun markTried(m: Api.Media, r: DlRoute) { tried.getOrPut(key(m)) { ConcurrentHashMap.newKeySet() }.add(r) }
    fun resetTried(m: Api.Media) { tried.remove(key(m)); chosen.remove(key(m)); autoFiles.remove(key(m)) }

    /** Archivos para los que se eligio AUTOMATICO en el recuadro (si falla una via, la app prueba otra sola). */
    private val autoFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun markAuto(m: Api.Media) { autoFiles.add(key(m)) }
    fun isAuto(m: Api.Media) = key(m) in autoFiles || (route == DlRoute.AUTO && !askBefore)

    /** Via que de verdad se uso para este archivo, segun el origen que eligio el motor. */
    fun usedRoute(m: Api.Media): DlRoute? = when (m.source) {
        "http" -> DlRoute.TURBO
        "fast" -> if (effectiveRoute(m) == DlRoute.EDU) DlRoute.EDU else DlRoute.NUBE
        "slow" -> DlRoute.DNS
        else -> null
    }

    private fun key(m: Api.Media) = "${m.crc}:${m.size}"
    fun choose(m: Api.Media, r: DlRoute) { chosen[key(m)] = r }
    fun effectiveRoute(m: Api.Media): DlRoute = chosen[key(m)] ?: route
    fun isForcedDns(m: Api.Media) = chosen[key(m)] == DlRoute.DNS
    fun forceDns(m: Api.Media) = choose(m, DlRoute.DNS)

    /** Vias que de verdad existen para ESTE archivo. */
    fun availableRoutes(m: Api.Media): List<DlRoute> {
        val gh = m.relays.getOrNull(1) == true
        val http = m.relays.getOrNull(2) == true
        val l = mutableListOf<DlRoute>()
        if (gh) l += DlRoute.NUBE
        if (http) l += DlRoute.TURBO
        if (dnsAvailable(m)) l += DlRoute.DNS
        if (edu != null && gh) l += DlRoute.EDU   // la universidad solo pasa por GitHub y solo si el servidor dio la cuenta
        return l.sortedByDescending { it == route }
    }

    fun dnsAvailable(m: Api.Media) = m.relays.getOrNull(0) == true && m.ch in 10000..60000 && m.blk > 0

    fun load(ctx: Context) {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_ROUTE, "AUTO")
        // "FAST" era el nombre anterior de la via rapida: pasa a TURBO.
        route = runCatching { DlRoute.valueOf(if (s == "FAST") "TURBO" else (s ?: "AUTO")) }.getOrDefault(DlRoute.AUTO)
        askBefore = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ASK, true)
        photoRoute = runCatching { DlRoute.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_PHOTO, "AUTO") ?: "AUTO") }
            .getOrDefault(DlRoute.AUTO).let { if (it == DlRoute.EDU) DlRoute.AUTO else it }
    }

    fun setAsk(ctx: Context, v: Boolean) {
        askBefore = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ASK, v).apply()
    }

    fun save(ctx: Context, r: DlRoute) {
        route = r
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_ROUTE, r.name).apply()
    }

    /** Origen para el motor: "http" | "fast" | "slow" | null (no descargable). */
    fun pickSource(m: Api.Media): String? {
        val http = m.relays.getOrNull(2) == true
        val gh = m.relays.getOrNull(1) == true
        val dns = dnsAvailable(m)
        return when (effectiveRoute(m)) {
            DlRoute.AUTO -> if (gh) "fast" else if (http) "http" else if (dns) "slow" else null   // GitHub primero: ahorra el VPS
            DlRoute.DNS -> if (dns) "slow" else if (http) "http" else if (gh) "fast" else null
            DlRoute.TURBO -> if (http) "http" else if (gh) "fast" else if (dns) "slow" else null
            DlRoute.NUBE -> if (gh) "fast" else if (http) "http" else if (dns) "slow" else null
            // UNIVERSIDAD: solo GitHub pasa por el proxy. Sin cuenta recibida NUNCA se sale directo (gastaria datos): DNS.
            DlRoute.EDU -> if (edu == null) (if (dns) "slow" else null) else if (gh) "fast" else if (dns) "slow" else null
        }
    }

    /** Parametro extra para el motor cuando la via es la universidad (solo en descargas rapidas). */
    fun proxyParam(m: Api.Media, source: String?): String {
        val e = edu
        if (effectiveRoute(m) != DlRoute.EDU || e == null || source == null || source == "slow") return ""
        return "&proxy=" + URLEncoder.encode(e.url(), "UTF-8")
    }

    /**
     * Le dice al nucleo Go por que proxy y por que base salen TODAS las descargas rapidas (tambien las de fondo).
     * Con otra via, o sin cuenta, quita el proxy. Corre en un hilo de IO.
     */
    fun applyProxy(api: Api) = applyProxyFor(api, route)

    /** Igual, pero para una via concreta (la que el usuario eligio para ese archivo). */
    fun applyProxyFor(api: Api, r: DlRoute) {
        val u = if (r == DlRoute.EDU) edu?.url().orEmpty() else ""
        runCatching { api.send("/api/relay/proxy", JSONObject().put("url", u).put("base", relayBase).toString()) }
    }

    /**
     * Pide la cuenta de la universidad al servidor (panel, /api/app/config -> "edu").
     * Corre en un hilo de IO. Devuelve el mensaje para mostrar al usuario.
     */
    fun refreshEdu(ctx: Context): String {
        // Con track=true el panel reconoce el dispositivo (necesita saber si esta aprobado para darle la cuenta).
        val o = runCatching { fetchRemote(ctx) }.getOrNull()
            ?: return "No se pudo hablar con el servidor. Reintenta cuando tengas internet."
        relayBase = o.optString("relay_base").trim()
        val e = o.optJSONObject("edu")
        if (e == null || !e.optBoolean("enabled") || e.optString("proxy").isBlank()) {
            edu = null
            return "El servidor todavía no tiene la cuenta de la universidad. Cuando se agregue en el panel, vuelve a tocar esta opción."
        }
        edu = EduProxy(e.optString("proxy"), e.optString("user"), e.optString("pass"))
        val note = e.optString("note")
        return "Cuenta recibida del servidor: " + e.optString("proxy") + if (note.isNotBlank()) "\n$note" else ""
    }
}

/** Seccion de Ajustes: elegir la via de descarga. */
@Composable
fun DownloadRouteSection(api: Api) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(DlConfig.route) }
    var eduMsg by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf(DlConfig.askBefore) }

    Section("DESCARGAS")
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)
        .card(if (ask) Color(0xFFDCE6FF) else Color.White, 12.dp)
        .clickable { ask = !ask; DlConfig.setAsk(ctx, ask) }
        .padding(12.dp)) {
        Text("PREGUNTAR ANTES DE DESCARGAR" + if (ask) "  ✓" else "", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Text(if (ask) "Cada archivo te deja elegir la vía (AUTOMÁTICO, TURBO, NUBE o LENTA por DNS)."
             else "Usa tu vía habitual. Si falla, la app te propone otra que sí tenga el archivo.",
            color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    Section("TU VÍA HABITUAL")
    DlRoute.values().forEach { r ->
        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .card(if (route == r) Color(0xFFDCE6FF) else Color.White, 12.dp)
            .clickable {
                route = r
                DlConfig.save(ctx, r)
                if (r == DlRoute.EDU) {
                    eduMsg = "Pidiendo la cuenta al servidor…"
                    scope.launch {
                        eduMsg = withContext(Dispatchers.IO) { DlConfig.refreshEdu(ctx).also { DlConfig.applyProxy(api) } }
                    }
                } else {
                    eduMsg = ""
                    scope.launch(Dispatchers.IO) { DlConfig.applyProxy(api) }
                }
            }
            .padding(12.dp)) {
            Text(r.label + if (route == r) "  ✓" else "", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(r.hint, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (route == DlRoute.EDU && eduMsg.isNotEmpty())
        Text(eduMsg, Modifier.padding(start = 4.dp, bottom = 4.dp), color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

/**
 * Aviso bajo una descarga que fallo: busca otra via donde SI este el archivo y la propone con buen tono.
 * Con la via AUTOMATICA (y sin preguntar) prueba sola las vias rapidas; DNS siempre se pregunta (es lenta).
 * [onRestart] se llama despues de relanzar la descarga (para que la tarjeta vuelva a "running").
 */
@Composable
fun DlFallbackBar(f: Api.Media, id: String, bridge: AndroidBridge?, namePrefix: String = "", ext: String = ".bin", onRestart: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dismissed by remember(id) { mutableStateOf(false) }
    val failed = DlConfig.usedRoute(f)
    if (bridge == null || dismissed || failed == null) return
    val alts = DlConfig.availableRoutes(f).filter { it != failed && it != DlRoute.EDU && it !in DlConfig.triedFor(f) }

    fun retry(r: DlRoute) {
        DlConfig.markTried(f, failed)
        DlConfig.choose(f, r)
        scope.launch {
            withContext(Dispatchers.IO) { DlConfig.api?.let { DlConfig.applyProxyFor(it, r) } }
            bridge.startMediaDownload(id, f.url(), namePrefix + f.name.ifEmpty { "$id$ext" }, f.size, f.crc, f.sha)
            onRestart()
        }
    }

    // Modo automatico: si hay otra via rapida con el archivo, la prueba sola (una vez por via).
    val auto = DlConfig.isAuto(f)
    val next = alts.firstOrNull { it != DlRoute.DNS }
    if (auto && next != null) {
        LaunchedEffect(id, failed) { retry(next) }
        Text("Probando por ${next.tag}…", Modifier.padding(top = 6.dp), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        return
    }
    if (alts.isEmpty()) return

    fun nameOf(r: DlRoute) = when (r) { DlRoute.TURBO -> "el servidor (TURBO)"; DlRoute.NUBE -> "la nube"; DlRoute.DNS -> "DNS"; else -> r.tag }
    val first = alts.first()
    val msg = when (first) {
        DlRoute.DNS -> "Por ${failed.tag} no se pudo. Todavía lo puedo intentar por DNS: no gasta datos, pero va despacito."
        else -> "Por ${failed.tag} no lo encontré 😅, pero ese archivo sí está en ${nameOf(first)}. ¿Lo bajo por ahí?"
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).card(Color(0xFFFFF3CD), 10.dp).padding(10.dp)) {
        Text(msg, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            alts.take(2).forEachIndexed { i, r ->
                Btn(if (i == 0) "SÍ, POR ${r.tag}" else "POR ${r.tag}", if (i == 0) Blue else Ink) { retry(r) }
            }
            Btn("AHORA NO", Ink) { dismissed = true }
        }
    }
}

// ---------------------------------------------------------------------
//  Preguntar ANTES de descargar
// ---------------------------------------------------------------------

/** Estado del dialogo "¿como quieres descargarlo?" de una tarjeta. Crear con rememberDlAsk(). */
class DlAsk {
    var pending by mutableStateOf<Api.Media?>(null)
    var action: (() -> Unit)? = null

    /**
     * Pide la via y luego ejecuta [go]. Si no hay nada que elegir (una sola via, la pregunta esta apagada
     * o se esta reanudando una descarga pausada) ejecuta [go] directo.
     */
    fun request(f: Api.Media, resume: Boolean = false, go: () -> Unit) {
        if (!resume) DlConfig.resetTried(f)
        if (resume || !DlConfig.askBefore || DlConfig.availableRoutes(f).size < 2) { go(); return }
        action = go
        pending = f
    }
}

@Composable
fun rememberDlAsk(): DlAsk = remember { DlAsk() }

/** Poner una vez dentro de la tarjeta: dibuja el dialogo cuando hay una pregunta pendiente. */
@Composable
fun DlAskHost(ask: DlAsk) {
    val scope = rememberCoroutineScope()
    val f = ask.pending ?: return
    DlChooseDialog(f,
        onPick = { r ->
            val go = ask.action
            ask.pending = null; ask.action = null
            DlConfig.choose(f, r)
            if (r == DlRoute.AUTO) DlConfig.markAuto(f)
            scope.launch {
                // El nucleo Go usa un proxy global: se ajusta a la via elegida justo antes de arrancar.
                withContext(Dispatchers.IO) { DlConfig.api?.let { DlConfig.applyProxyFor(it, r) } }
                go?.invoke()
            }
        },
        onCancel = { ask.pending = null; ask.action = null })
}

@Composable
fun DlChooseDialog(f: Api.Media, onPick: (DlRoute) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().card(Color.White, 20.dp).padding(18.dp)) {
            Text("¿Cómo quieres descargarlo?", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text((if (f.name.isNotEmpty()) f.name + "  ·  " else "") + fmtSize(f.size),
                Modifier.padding(top = 4.dp, bottom = 12.dp), color = Muted, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            (listOf(DlRoute.AUTO) + DlConfig.availableRoutes(f)).forEach { r ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(Chip, 12.dp)
                    .clickable { onPick(r) }.padding(12.dp)) {
                    Text(r.tag + if (r == DlConfig.route) "  ·  tu vía habitual" else "",
                        color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text(r.note, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Btn("CANCELAR", Ink) { onCancel() }
        }
    }
}

/** Dialogo del feed: "¿Por donde bajo las fotos?" con cada via explicada en palabras simples. */
@Composable
fun PhotoRouteDialog(current: DlRoute, onPick: (DlRoute) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().card(Color.White, 20.dp).padding(18.dp)) {
            Text("¿Por dónde bajo las fotos?", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("Elige cómo se descargan las fotos del feed. Si no sabes, deja AUTOMÁTICO.",
                Modifier.padding(top = 4.dp, bottom = 12.dp), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            listOf(DlRoute.AUTO, DlRoute.TURBO, DlRoute.NUBE, DlRoute.DNS).forEach { r ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card(if (r == current) Color(0xFFDCE6FF) else Chip, 12.dp)
                    .clickable { onPick(r) }.padding(12.dp)) {
                    Text(r.tag + if (r == current) "  ✓" else "", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text(r.note, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Btn("CERRAR", Ink) { onCancel() }
        }
    }
}
