package com.cuvay.android.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Rutas de las listas con nombre (si en tu web.go son otras, cambia solo estas dos).
private const val LISTS = "/api/resolvers/lists"
private const val LISTS_SELECT = "/api/resolvers/lists/select"

private fun JSONObject.obj(vararg k: String): JSONObject? = k.firstNotNullOfOrNull { optJSONObject(it) }
private fun JSONObject.str(vararg k: String): String = k.firstNotNullOfOrNull { optString(it, "").ifEmpty { null } } ?: ""
private fun JSONObject.profs(): JSONArray = optJSONArray("Profiles") ?: optJSONArray("profiles") ?: JSONArray()
/** Identidad de una config: dominio + clave (el ID cambia al crearla de nuevo). */
private fun sig(p: JSONObject): String {
    val c = p.obj("config", "Config")
    return (c?.str("domain", "Domain") ?: "") + "|" + (c?.str("key", "Key") ?: "")
}

/** Respaldo de TODO: ajustes del servidor, configs (perfiles), resolvers y preferencias de pantalla. */
object Backup {
    private const val UI_PREFS = "cuvay_ui"

    fun export(api: Api, ctx: Context): String {
        val o = JSONObject()
        o.put("app", "cuvay"); o.put("version", 1); o.put("date", System.currentTimeMillis())
        o.put("settings", api.settings())
        o.put("profiles", api.profiles())
        val rs = api.resolverStatus()
        o.put("resolvers", rs.optJSONArray("resolvers") ?: JSONArray())          // activos
        o.put("resolverBank", rs.optJSONArray("all") ?: JSONArray())            // banco completo
        runCatching { o.put("resolverLists", JSONObject(api.getRaw("$LISTS?include=resolvers"))) } // listas con nombre
        val ui = JSONObject()
        ctx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) -> if (v != null) ui.put(k, v) }
        o.put("ui", ui)
        return o.toString(2)
    }

    /** Devuelve un resumen de lo que se restauro. Lanza excepcion si el archivo no es valido. */
    fun import(api: Api, ctx: Context, text: String): String {
        val o = JSONObject(text)
        require(o.optString("app") == "cuvay") { "no es un respaldo de Cuvay" }
        val done = mutableListOf<String>()

        o.optJSONObject("ui")?.let { ui ->
            val e = ctx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
            ui.keys().forEach { k ->
                when (val v = ui.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Double -> e.putFloat(k, v.toFloat())
                    is String -> e.putString(k, v)
                }
            }
            e.apply(); done += "pantalla"
        }

        // Configs (perfiles): se crean las que no existan (mismo dominio+clave) y conservan su lista de auto-actualizar y fijados.
        o.optJSONObject("profiles")?.let { bp ->
            val list = bp.profs()
            if (list.length() > 0) {
                val curList = api.profiles().profs()
                val have = (0 until curList.length()).map { sig(curList.getJSONObject(it)) }.toMutableSet()
                val wasEmpty = curList.length() == 0
                var n = 0
                for (i in 0 until list.length()) {
                    val p = list.getJSONObject(i); val sg = sig(p)
                    if (sg == "|" || sg in have) continue
                    api.send("/api/profiles", JSONObject().put("action", "create").put("profile", p).toString())
                    have += sg; n++
                }
                if (n > 0) done += "configs ($n nuevas)"
                // En una instalacion vacia, deja activa la misma config que estaba activa en el respaldo.
                if (wasEmpty && n > 0) runCatching {
                    val activeId = bp.str("Active", "active")
                    val want = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.str("ID", "id") == activeId }
                    if (want != null) {
                        val now = api.profiles().profs()
                        val hit = (0 until now.length()).map { now.getJSONObject(it) }.firstOrNull { sig(it) == sig(want) }
                        if (hit != null) api.send("/api/profiles/switch",
                            JSONObject().put("id", hit.str("ID", "id")).put("skipCheck", true).toString())
                    }
                }
            }
        }

        o.optJSONObject("settings")?.let { s ->
            runCatching { api.saveSettings(s.toString()) }.onFailure {
                // Si el servidor rechaza el objeto completo, manda solo los campos conocidos.
                val m = JSONObject()
                listOf("queryMode", "rateLimit", "timeout", "scatter").forEach { k -> if (s.has(k)) m.put(k, s.get(k)) }
                api.saveSettings(m.toString())
            }
            runCatching { api.applyActiveProfile() }
            done += "ajustes"
        }

        val all = linkedSetOf<String>()
        listOf("resolverBank", "resolvers").forEach { k ->
            o.optJSONArray(k)?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { all += it } }
        }
        if (all.isNotEmpty()) {
            api.send("/api/resolvers/bank", JSONObject().put("resolvers", JSONArray(all.toList())).toString())
            // Listas con nombre: si el respaldo las trae NO se reescanea (el reescaneo pisaria la lista elegida).
            val lo = o.optJSONObject("resolverLists")
            val lists = lo?.optJSONArray("lists")
            val hasLists = (lists?.length() ?: 0) > 0
            var restored = 0
            if (lists != null) for (i in 0 until lists.length()) {
                val l = lists.getJSONObject(i)
                val name = l.optString("name"); val arr = l.optJSONArray("resolvers")
                if (name.isEmpty() || arr == null || arr.length() == 0) continue
                if (runCatching { api.send(LISTS, JSONObject().put("name", name).put("resolvers", arr).toString()) }.isSuccess) restored++
            }
            val sel = lo?.optString("selected").orEmpty()
            if (hasLists && sel.isNotEmpty()) runCatching { api.send(LISTS_SELECT, JSONObject().put("name", sel).put("noScan", true).toString()) }
            val rescan = !hasLists && runCatching { api.send("/api/rescan") }.isSuccess
            done += "resolvers (${all.size}" + when {
                hasLists -> ", $restored listas nuevas)"
                rescan -> ", reescaneando)"
                else -> ")"
            }
        }
        return if (done.isEmpty()) "El archivo no traia datos" else "Restaurado: " + done.joinToString(", ")
    }
}

@Composable
fun BackupSection(api: Api, onResult: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val txt = Backup.export(api, ctx)
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(txt.toByteArray()) }
                    "Respaldo guardado"
                }.getOrElse { "Error: ${it.message}" }
            }
            busy = false
            onResult(r)
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val txt = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    Backup.import(api, ctx, txt)
                }.getOrElse { "Error: ${it.message}" }
            }
            busy = false
            onResult(r)
        }
    }

    Section("RESPALDO")
    Text("Guarda configs, resolvers, ajustes y tu ID del chat en un archivo, o restaura desde uno.",
        color = Soft, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn("EXPORTAR", Blue) {
            val d = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            saver.launch("cuvay-respaldo-$d.json")
        }
        Btn("IMPORTAR", Red) { opener.launch(arrayOf("application/json", "text/plain", "*/*")) }
    }
    if (busy) { Spacer(Modifier.height(8.dp)); ProgressLine("Procesando respaldo…", null, dark = true) }
}
