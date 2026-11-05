package com.cuvay.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * ENLACE DE FÁBRICA: pega aquí tu enlace cuvay://... completo y la app nueva se conecta sola en su
 * primer arranque. Déjalo "" para desactivarlo. Quien tenga el APK puede sacar la clave de este enlace.
 */
internal const val FACTORY_URI = ""


private fun JSONObject.profs(): JSONArray = optJSONArray("profiles") ?: optJSONArray("Profiles") ?: JSONArray()
private fun JSONObject.s(vararg k: String): String = k.firstNotNullOfOrNull { optString(it, "").ifEmpty { null } } ?: ""

// ---------------- enlaces cuvay:// (mismo formato que la versión original) ----------------
// cuvay://DOMINIO/CLAVE?n=NOMBRE&sk=CLAVE_PUBLICA_DEL_SERVIDOR&d=DOMINIOS_EXTRA&r=RESOLVER,RESOLVER

internal class Access(val domain: String, val key: String, val sk: String = "", val extra: List<String> = emptyList(),
                      val nick: String = "", val resolvers: List<String> = emptyList())

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

internal fun buildUri(a: Access): String {
    val p = mutableListOf<String>()
    if (a.nick.isNotBlank() && a.nick != a.domain) p += "n=" + enc(a.nick.trim().take(32))
    if (a.sk.isNotBlank()) p += "sk=" + a.sk
    if (a.extra.isNotEmpty()) p += "d=" + enc(a.extra.joinToString(",")).replace("%2C", ",")
    if (a.resolvers.isNotEmpty()) p += "r=" + a.resolvers.joinToString(",") { it.removeSuffix(":53") }   // los resolvers van al final
    return "cuvay://" + enc(a.domain) + "/" + enc(a.key) + (if (p.isEmpty()) "" else "?" + p.joinToString("&"))
}

/** Busca un cuvay://... dentro de cualquier texto (un mensaje de WhatsApp entero sirve). */
internal fun parseUri(text: String): Access? = runCatching {
    // Acepta cuvay:// y el viejo thefeed:// (el panel todavia puede publicar enlaces con el prefijo anterior).
    val scheme = listOf("cuvay://", "thefeed://").mapNotNull { sc -> text.indexOf(sc).let { if (it >= 0) it to sc else null } }
        .minByOrNull { it.first }
    require(scheme != null)
    val body = text.substring(scheme.first + scheme.second.length).takeWhile { !it.isWhitespace() }
    val q = body.indexOf('?')
    val path = if (q >= 0) body.substring(0, q) else body
    val params = if (q >= 0) body.substring(q + 1) else ""
    val parts = path.split("/")
    val domain = dec(parts.getOrElse(0) { "" }); val key = dec(parts.getOrElse(1) { "" })
    require(domain.isNotEmpty() && key.isNotEmpty())
    var sk = ""; var extra = listOf<String>(); var nick = ""; var rs = listOf<String>()
    for (kv in params.split("&")) {
        val e = kv.indexOf('='); if (e < 0) continue
        val k = kv.substring(0, e); val v = kv.substring(e + 1)
        when (k) {
            "sk" -> sk = v.trim()
            "n" -> nick = dec(v)
            "d" -> extra = dec(v).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            "r" -> rs = dec(v).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    Access(domain.lowercase(), key, sk, extra, nick, rs)
}.getOrNull()

// ---------- códigos cortos: cuvay-<panel>-<CODIGO> ----------
// "<panel>" sin punto (p. ej. titomx) = el panel de siempre (PANEL); con punto (pepe.example.com) = https://panel.pepe.example.com
private val CODE_RE = Regex("(?i)\\bcuvay-([a-z0-9.]+)-([a-z0-9]{8})\\b")

internal fun hasCode(text: String) = CODE_RE.containsMatchIn(text)

/** Pide al panel los datos de ese código. Bloqueante: llámala desde IO. */
internal fun resolveCode(text: String): Access {
    val m = CODE_RE.find(text) ?: error("no veo ningún código cuvay-…")
    val label = m.groupValues[1].lowercase(); val code = m.groupValues[2].uppercase()
    val host = if ('.' in label) "https://panel.$label" else PANEL
    val c = java.net.URL("$host/api/app/code/$code").openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 10000
    val ok = c.responseCode in 200..299
    val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (!ok) error(runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty().ifBlank { "el panel respondió ${c.responseCode}" })
    val j = JSONObject(body)
    fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    return Access(j.getString("domain"), j.getString("key"), j.optString("sk"), list("extra"), j.optString("nick"), list("resolvers"))
}

/** Crea la config (o reutiliza la igual) y la deja ACTIVA. Bloqueante: llámala desde IO. */
internal fun addConfig(api: Api, a: Access, activate: Boolean = true) {
    val d = a.domain.trim().lowercase(); val k = a.key.trim()
    require(d.isNotEmpty() && k.isNotEmpty()) { "falta el dominio o la clave" }
    fun find(): JSONObject? {
        val l = api.profiles().profs()
        return (0 until l.length()).map { l.getJSONObject(it) }.lastOrNull {
            val c = it.optJSONObject("config") ?: it.optJSONObject("Config")
            c != null && c.s("domain", "Domain") == d && c.s("key", "Key") == k
        }
    }
    if (find() == null) {
        val rs = a.resolvers.map { it.trim() }.filter { it.isNotEmpty() }.map { if (':' in it) it else "$it:53" }
            .ifEmpty { listOf("1.1.1.1:53", "8.8.8.8:53") }
        val cfg = JSONObject().put("domain", d).put("key", k).put("resolvers", JSONArray(rs))
            .put("queryMode", "single").put("rateLimit", 10.0)
        if (a.sk.isNotBlank()) cfg.put("serverKey", a.sk)
        if (a.extra.isNotEmpty()) cfg.put("extraDomains", JSONArray(a.extra))
        api.send("/api/profiles", JSONObject().put("action", "create").put("profile", JSONObject()
            .put("nickname", a.nick.ifBlank { "Cuvay" }).put("config", cfg)).toString())
    }
    val hid = find()?.s("id", "ID") ?: ""
    if (activate && hid.isNotEmpty() && hid != api.profiles().s("active", "Active"))
        api.send("/api/profiles/switch", JSONObject().put("id", hid).toString())
}

/** Borra la config con ese dominio+clave (si existe). Bloqueante: llámala desde IO. */
internal fun removeConfig(api: Api, domain: String, key: String) {
    val l = api.profiles().profs()
    val hit = (0 until l.length()).map { l.getJSONObject(it) }.firstOrNull {
        val c = it.optJSONObject("config") ?: it.optJSONObject("Config")
        c != null && c.s("domain", "Domain") == domain.lowercase() && c.s("key", "Key") == key
    } ?: return
    api.send("/api/profiles", JSONObject().put("action", "delete")
        .put("profile", JSONObject().put("id", hit.s("id", "ID"))).toString())
}

@Composable
private fun CfgRow(name: String, domain: String, active: Boolean, expanded: Boolean, confirming: Boolean,
                   onTap: () -> Unit, onActivate: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).card().clickable(onClick = onTap).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)

            }
            if (active) Text("ACTIVA", color = Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 8.dp))
            Text(if (expanded) "▲" else "▼", color = Muted, fontSize = 12.sp)
        }
        if (expanded) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) Btn("ACTIVAR", Blue) { onActivate() }
            Btn("COMPARTIR", Ink) { onShare() }
            Btn(if (confirming) "¿SEGURO? ELIMINAR" else "ELIMINAR", Red) { onDelete() }
        }
    }
}

/**
 * Pestaña "Fuentes": conexión con tu servidor. Se entra con un enlace cuvay://, o a mano (dominio + clave).
 * Sin una config activa el feed no carga.
 */
@Composable
fun MirrorScreen(api: Api) {
    var tick by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    var cfgDomain by remember { mutableStateOf("") }
    var cfgKey by remember { mutableStateOf("") }
    var cfgRes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun run(label: String, f: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            msg = withContext(Dispatchers.IO) { runCatching { f(); label }.getOrElse { "Error: ${it.message}" } }
            busy = false; tick++
        }
    }
    fun enter(text: String) {
        val a = parseUri(text)
        if (a != null) { link = ""; run("Acceso activado. Espera 1 o 2 minutos y abre Feed.") { addConfig(api, a) } }
        else if (hasCode(text)) { link = ""; run("Acceso activado. Espera 1 o 2 minutos y abre Feed.") { addConfig(api, resolveCode(text)) } }
        else msg = "Error: no veo ningún código cuvay-… ni enlace cuvay:// en ese texto"
    }

    Load(tick, { runCatching { api.profiles() } }) { res ->
        val pf = res.getOrNull()
        val profs = pf?.profs() ?: JSONArray()
        val active = pf?.s("active", "Active") ?: ""

        LazyColumn(Modifier.fillMaxSize()) {
            if (msg.isNotEmpty()) item {
                Text(msg, color = if (msg.startsWith("Error")) Red else Blue, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).card(Color.White, 12.dp).padding(10.dp))
            }
            if (busy) item { BarLoader(null, Modifier.padding(bottom = 6.dp)) }
            if (res.isFailure) item {
                Text("No se pudo leer la lista de configs (${res.exceptionOrNull()?.message}). Aun así puedes crear una abajo.",
                    color = Soft, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            }

            item {
                Section("ENTRAR CON UN CÓDIGO O ENLACE (cuvay-…)")
                Field(link, { link = it }, "pega aquí el código (cuvay-…) o el enlace", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (busy) "…" else "ACTIVAR", Red) { enter(link) }
                    Btn("PEGAR Y ACTIVAR", Blue) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        enter(t)
                    }
                }
            }

            item { Section("MIS CONFIGS (${profs.length()})  ·  toca una para ver acciones") }
            if (profs.length() == 0) item {
                Text("Todavía no tienes ninguna. Pega un enlace arriba o llena el formulario de abajo: sin config el feed no carga.",
                    color = Soft, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            }
            items(profs.length(), key = { i -> "p" + profs.getJSONObject(i).s("id", "ID").ifEmpty { i.toString() } }) { i ->
                val p = profs.getJSONObject(i)
                val id = p.s("id", "ID")
                val cfg = p.optJSONObject("config") ?: p.optJSONObject("Config")
                val dom = cfg?.s("domain", "Domain") ?: ""
                val nick = p.s("nickname", "Nickname")
                val name = if (nick.isBlank() || nick.equals(dom, ignoreCase = true)) "Servidor ${i + 1}" else nick
                CfgRow(name, dom, id == active, open == id, confirm == id,
                    onTap = { open = if (open == id) null else id; confirm = null },
                    onActivate = {
                        open = null
                        run("Config activada. Esperando datos…") {
                            api.send("/api/profiles/switch", JSONObject().put("id", id).toString())
                        }
                    },
                    onShare = {
                        val key = cfg?.s("key", "Key") ?: ""
                        if (dom.isEmpty() || key.isEmpty()) msg = "Error: esta config no tiene clave guardada"
                        else scope.launch {
                            val rs = withContext(Dispatchers.IO) {
                                runCatching {
                                    val a = api.resolverStatus().optJSONArray("resolvers") ?: JSONArray()
                                    (0 until a.length()).map { a.getString(it) }.take(6)   // pocos = enlace corto
                                }.getOrDefault(emptyList())
                            }
                            val extra = cfg?.optJSONArray("extraDomains")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                            val uri = buildUri(Access(dom, key, cfg?.s("serverKey") ?: "", extra, p.s("nickname", "Nickname"), rs))
                            val text = "📡 Entra a Cuvay\nAbre la app → Fuentes → copia este mensaje → PEGAR Y ACTIVAR\n\n$uri"
                            runCatching {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, text), "Compartir acceso").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                    onDelete = {
                        if (confirm != id) confirm = id
                        else {
                            open = null; confirm = null
                            run("Config eliminada") {
                                api.send("/api/profiles", JSONObject().put("action", "delete")
                                    .put("profile", JSONObject().put("id", id)).toString())
                            }
                        }
                    })
            }

            item {
                Section("O A MANO (DOMINIO Y CLAVE DE TU SERVIDOR)")
                Field(cfgDomain, { cfgDomain = it },"dominio de tu servidor", Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Field(cfgKey, { cfgKey = it }, "clave (la misma del servidor)", Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Field(cfgRes, { cfgRes = it }, "resolvers (opcional; vacío = 1.1.1.1 y 8.8.8.8)", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Btn(if (busy) "GUARDANDO…" else "GUARDAR Y ACTIVAR", Red) {
                    val d = cfgDomain; val k = cfgKey; val r = cfgRes.split(Regex("[\\s,;]+"))
                    if (d.isBlank() || k.isBlank()) msg = "Error: falta el dominio o la clave"
                    else run("Config guardada. Espera 1 o 2 minutos y abre Feed.") { addConfig(api, Access(d, k, resolvers = r)) }
                }
                Text("Tip: en Ajustes → EXPORTAR guardas todo en un archivo; si reinstalas la app, IMPORTAR lo devuelve. Ese archivo es solo tuyo (lleva tu sesión del chat).",
                    color = Soft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
