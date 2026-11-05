package com.cuvay.android.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
 * Resolvers de fabrica (los DNS de Cuba). Se cargan UNA sola vez por instalacion, quedan
 * guardados y activos sin reescanear, asi no hay que pegarlos cada vez que se instala la app.
 * Despues el usuario puede quitarlos o poner otros en la pestana Res.: no se vuelven a agregar.
 *
 * Para cambiar la lista de fabrica edita DEFAULT_RESOLVERS. Si algun dia quieres aplicarla de
 * nuevo a todos, sube la version de DEFAULT_RES_KEY (v1 -> v2).
 */
private val DEFAULT_RESOLVERS = listOf(
    "200.55.128.130:53",
    "200.55.128.140:53",
    "200.55.128.230:53",
    "200.55.128.250:53"
)
private const val DEFAULT_RES_KEY = "default_resolvers_v1"

// Mismas claves y rutas que ResolverEdit.kt
private const val PREFS = "cuvay_ui"
private const val SAVED_KEY = "res_saved"
private const val SAVED_NAME = "Mis resolvers"

/** Bloqueante: llamala desde Dispatchers.IO. Reintenta mientras el servidor interno arranca. */
internal fun ensureDefaultResolvers(ctx: Context, api: Api) {
    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(DEFAULT_RES_KEY, false)) return
    val cur = (prefs.getString(SAVED_KEY, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    // Si el usuario ya tiene su propia lista, no se la toco.
    if (cur.isNotEmpty() && !cur.all { it in DEFAULT_RESOLVERS }) {
        prefs.edit().putBoolean(DEFAULT_RES_KEY, true).apply(); return
    }
    repeat(12) {
        val ok = runCatching {
            api.settings()   // lanza si el servidor interno aun no arranco
            prefs.edit().putString(SAVED_KEY, DEFAULT_RESOLVERS.joinToString("\n")).apply()
            val arr = JSONArray(DEFAULT_RESOLVERS)
            api.send("/api/resolvers/bank", JSONObject().put("resolvers", arr).toString())
            api.send("/api/resolvers/lists", JSONObject().put("name", SAVED_NAME).put("resolvers", arr).toString())
            api.send("/api/resolvers/lists/select", JSONObject().put("name", SAVED_NAME).put("noScan", true).toString())
        }.isSuccess
        if (ok) { prefs.edit().putBoolean(DEFAULT_RES_KEY, true).apply(); return }
        Thread.sleep(1500)
    }
}
