package com.cuvay.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

// Mismas rutas que Backup.kt (listas con nombre del servidor Go).
private const val RL = "/api/resolvers/lists"
private const val RL_SEL = "/api/resolvers/lists/select"
private const val SAVED_NAME = "Mis resolvers"
private const val SPREFS = "cuvay_ui"   // el respaldo de Ajustes ya incluye estas preferencias
private const val SKEY = "res_saved"

private val hostRe = Regex("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$")

/** Acepta muchos a la vez separados por espacio, coma, punto y coma o salto de línea. */
private fun parseIps(s: String): List<String> =
    s.split(Regex("[\\s,;]+")).map { it.trim() }
        .filter { it.isNotEmpty() && hostRe.matches(it) }
        .map { if (':' in it) it else "$it:53" }.distinct()

private fun loadSaved(p: SharedPreferences): List<String> =
    (p.getString(SKEY, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Guarda la lista en el servidor (y opcionalmente la deja activa SIN reescanear). */
private fun pushList(api: Api, l: List<String>, select: Boolean) {
    if (l.isEmpty()) return
    api.send("/api/resolvers/bank", JSONObject().put("resolvers", JSONArray(l)).toString())
    api.send(RL, JSONObject().put("name", SAVED_NAME).put("resolvers", JSONArray(l)).toString())
    if (select) api.send(RL_SEL, JSONObject().put("name", SAVED_NAME).put("noScan", true).toString())
}

@Composable
private fun Chip2(t: String, c: Color) =
    Text(t, Modifier.padding(start = 6.dp).rounded(8.dp).background(c).padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResRow(addr: String, sub: String, active: Boolean, saved: Boolean, checked: Boolean, selecting: Boolean,
                   onTap: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)
        .card(if (checked) Color(0xFFDCE6FF) else Color.White, 14.dp)
        .combinedClickable(onClick = onTap, onLongClick = onLong).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selecting) {
            Box(Modifier.size(22.dp).rounded(6.dp).background(if (checked) Blue else Chip), contentAlignment = Alignment.Center) {
                if (checked) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(addr, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (saved) Chip2("GUARDADO", Blue)
        if (active) Chip2("ACTIVO", Color(0xFF059669))
    }
}

/**
 * Resolver: lista guardada (persistente), selección en masa y agregar muchos a la vez.
 * - Tocar NO borra nada. Mantén pulsado para entrar en modo selección.
 * - Los guardados viven en preferencias + lista con nombre en el servidor, activada con noScan.
 */
@Composable
fun ResolverScreen(api: Api) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(SPREFS, Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(loadSaved(prefs)) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var tick by remember { mutableStateOf(0) }
    var newIp by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist(l: List<String>) { saved = l; prefs.edit().putString(SKEY, l.joinToString("\n")).apply() }
    fun act(label: String, f: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            msg = withContext(Dispatchers.IO) { runCatching { f(); label }.getOrElse { "Error: ${it.message}" } }
            busy = false; selected = emptySet(); tick++
        }
    }

    Load(tick, { api.resolverStatus() }) { o ->
        val activeArr = o.optJSONArray("resolvers") ?: JSONArray()
        val active = (0 until activeArr.length()).map { activeArr.getString(it) }
        val activeSet = active.toSet()
        val board = o.optJSONArray("scoreboard") ?: JSONArray()
        val scores = (0 until board.length()).associate { board.getJSONObject(it).optString("addr") to board.getJSONObject(it) }
        val all = (saved + active).distinct()
        val selecting = selected.isNotEmpty()
        val inactive = saved.filter { it !in activeSet }

        LazyColumn(Modifier.fillMaxSize()) {
            if (busy) item { BarLoader(null, Modifier.padding(bottom = 6.dp)) }
            if (msg.isNotEmpty()) item {
                Text(msg, color = if (msg.startsWith("Error")) Red else Blue, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).card(Color.White, 12.dp).padding(10.dp))
            }
            item {
                Section("AGREGAR (PEGA VARIOS: ESPACIO, COMA O LÍNEA)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(newIp, { newIp = it }, "8.8.8.8 1.1.1.1:53 …", Modifier.weight(1f))
                    Btn("GUARDAR", Red) {
                        val add = parseIps(newIp)
                        if (add.isEmpty()) msg = "Error: no veo ninguna IP válida"
                        else {
                            val merged = (saved + add).distinct(); persist(merged); newIp = ""
                            act("Guardados ${add.size} (total ${merged.size})") { pushList(api, merged, select = true) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("RESCAN", Blue) { act("Reescaneo iniciado") { api.send("/api/rescan") } }
                    Btn("PUNTAJES", Ink) { act("Puntajes reiniciados") { api.send("/api/resolvers/reset-stats") } }
                    Btn("↻", Ink) { tick++ }
                }
                if (inactive.isNotEmpty() && !selecting) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().card(Color(0xFFFFF3CD), 12.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${inactive.size} guardados están inactivos", Modifier.weight(1f), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Btn("RESTAURAR", Blue) { act("Restaurados ${saved.size}") { pushList(api, saved, select = true) } }
                    }
                }
                Section(if (selecting) "${selected.size} SELECCIONADOS" else "RESOLVERS (${all.size})  ·  mantén pulsado para seleccionar")
                if (selecting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn(if (selected.size == all.size) "NINGUNO" else "TODOS", Ink) { selected = if (selected.size == all.size) emptySet() else all.toSet() }
                        Btn("GUARDAR", Blue) {
                            val merged = (saved + selected).distinct(); val n = selected.size; persist(merged)
                            act("Guardados $n") { pushList(api, merged, select = false) }
                        }
                        Btn("QUITAR", Red) {
                            val rm = selected; val rest = saved.filter { it !in rm }; persist(rest)
                            act("Quitados ${rm.size}") {
                                rm.filter { it in activeSet }.forEach { a ->
                                    api.send("/api/resolvers/remove", JSONObject().put("addr", a).toString())
                                }
                                pushList(api, rest, select = true)
                            }
                        }
                        Btn("✕", Ink) { selected = emptySet() }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(all, key = { it }) { a ->
                val sc = scores[a]
                ResRow(a, if (sc != null) "%d ms  ·  puntaje %.2f".format(sc.optDouble("avgMs").toInt(), sc.optDouble("score")) else "",
                    a in activeSet, a in saved, a in selected, selecting,
                    onTap = { if (selecting) selected = if (a in selected) selected - a else selected + a },
                    onLong = { selected = if (a in selected) selected - a else selected + a })
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
