package com.cuvay.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.json.JSONObject
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Btn(t: String, bg: Color = Blue, onClick: () -> Unit) =
    Text(t, Modifier.rounded(12.dp).background(if (bg == Ink) Color(0xFF2A3A6B) else bg).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, numeric: Boolean = false) =
    Box(modifier.card(Color.White, 12.dp)) {
        if (value.isEmpty()) Text(hint, Modifier.padding(12.dp), color = Muted, fontSize = 15.sp)
        // Antes el BasicTextField no tenia modifier: medía lo que su texto y solo se
        // podia enfocar tocando ese hueco minimo. Ahora ocupa todo el cuadro.
        BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(12.dp),
            textStyle = TextStyle(color = Ink, fontSize = 15.sp), singleLine = true,
            cursorBrush = SolidColor(Blue),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
    }


// ---------------- AJUSTES ----------------
private fun fmtNum(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@Composable
fun SettingsScreen(api: Api) {
    var tick by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    /** Guarda en el servidor, reaplica el perfil (sin reescanear) y recarga la pantalla. */
    fun save(label: String, body: JSONObject) {
        scope.launch {
            msg = withContext(Dispatchers.IO) {
                runCatching { api.saveSettings(body.toString()); runCatching { api.applyActiveProfile() }; label }
                    .getOrElse { "Error: ${it.message}" }
            }
            tick++
        }
    }
    Load(tick, { Triple(api.profiles(), api.settings(), api.cacheStats()) }) { (pf, st, cache) ->
        var rate by remember(st) { mutableStateOf(fmtNum(st.optDouble("rateLimit", 10.0))) }
        var tmo by remember(st) { mutableStateOf(fmtNum(st.optDouble("timeout", 0.0))) }
        var sct by remember(st) { mutableStateOf(st.optInt("scatter", 0).toString()) }
        val qm = st.optString("queryMode", "single")
        var showDev by remember { mutableStateOf(false) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (msg.isNotEmpty()) Text(msg, color = if (msg.startsWith("Error")) Red else Blue, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().card(Color.White, 12.dp).padding(10.dp))
            Section("CONEXIÓN")
            Text("Modo de consulta", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("SINGLE", if (qm == "single") Blue else Muted) { if (qm != "single") save("Modo: single", JSONObject().put("queryMode", "single")) }
                Btn("DOUBLE", if (qm == "double") Blue else Muted) { if (qm != "double") save("Modo: double", JSONObject().put("queryMode", "double")) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Límite de velocidad", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 4.dp))
            Field(rate, { rate = it }, "ej. 10", Modifier.fillMaxWidth(), numeric = true)
            Spacer(Modifier.height(6.dp))
            Text("Timeout (segundos, 0 = por defecto)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 4.dp))
            Field(tmo, { tmo = it }, "ej. 5", Modifier.fillMaxWidth(), numeric = true)
            Spacer(Modifier.height(6.dp))
            Text("Dispersión (scatter, 0 = por defecto)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 4.dp))
            Field(sct, { sct = it }, "ej. 2", Modifier.fillMaxWidth(), numeric = true)
            Spacer(Modifier.height(8.dp))
            Btn("GUARDAR CONEXIÓN", Red) {
                val body = JSONObject()
                val r = rate.trim().toDoubleOrNull(); val t = tmo.trim().toDoubleOrNull(); val sc = sct.trim().toIntOrNull()
                if (r == null || t == null || sc == null || r < 0 || t < 0 || sc < 0) msg = "Error: revisa los números"
                else { body.put("rateLimit", r).put("timeout", t).put("scatter", sc); save("Conexión guardada", body) }
            }
            Section("PANTALLA")
            Item("Idioma", st.optString("lang", "-"), "")
            Section("ALMACENAMIENTO")
            Item("Total en caché", fmtSize(cache.optLong("total")), "")
            val cs = cache.optJSONArray("caches")
            if (cs != null) for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i); Item(c.optString("label"), "", fmtSize(c.optLong("bytes")))
            }
            Btn("LIMPIAR CACHÉ", Red) { scope.launch(Dispatchers.IO) { runCatching { api.clearCache() }; tick++ } }
            BackupSection(api) { msg = it; tick++ }
            AlertsSection()
            DownloadRouteSection(api)
            Section("ACERCA DE")
            Item("Cuvay", "Versión " + com.cuvay.android.BuildConfig.VERSION_NAME, "")
            Spacer(Modifier.height(8.dp))
            DeveloperCard { showDev = true }
            if (showDev) DeveloperProfileDialog { showDev = false }
            Spacer(Modifier.height(20.dp))

        }
    }
}

@Composable
fun Section(t: String) =
    Text(t, Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp), color = Soft, fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
