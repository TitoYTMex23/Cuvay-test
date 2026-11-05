package com.cuvay.android.ui

import android.content.Context
import org.json.JSONObject

/*
 * Valores de velocidad de fábrica para las descargas por DNS.
 *
 * El núcleo Go trae "Límite de velocidad" = 10 bloques/seg (~4.5 KB/s). Aquí se
 * suben UNA sola vez por instalación. Si el usuario ya lo había subido a mano
 * (> 10) o lo cambia después en Ajus. > CONEXIÓN, se respeta.
 *
 * Cambia los tres números para afinar. Si algún día quieres volver a aplicar
 * otros valores a todos, súbele la versión a SPEED_DEFAULTS_KEY (v1 -> v2).
 */
internal const val SPEED_RATE = 60.0     // bloques por segundo (Límite de velocidad)
internal const val SPEED_SCATTER = 3     // resolvers por bloque (Dispersión)
internal const val SPEED_TIMEOUT = 6.0   // segundos por consulta (Timeout)
private const val SPEED_DEFAULTS_KEY = "speed_defaults_v1"

/** Bloqueante: llámala desde Dispatchers.IO. Reintenta mientras el servidor interno arranca. */
internal fun ensureSpeedDefaults(ctx: Context, api: Api) {
    val prefs = ctx.getSharedPreferences("cuvay_ui", Context.MODE_PRIVATE)
    if (prefs.getBoolean(SPEED_DEFAULTS_KEY, false)) return
    repeat(12) {
        val done = runCatching {
            val cur = api.settings()   // lanza si el servidor interno aún no arrancó
            if (cur.optDouble("rateLimit", 10.0) <= 10.0) {
                api.saveSettings(
                    JSONObject()
                        .put("rateLimit", SPEED_RATE)
                        .put("scatter", SPEED_SCATTER)
                        .put("timeout", SPEED_TIMEOUT)
                        .toString()
                )
                runCatching { api.applyActiveProfile() }   // sin config aún: no pasa nada, la tomará al crearse
            }
        }.isSuccess
        if (done) { prefs.edit().putBoolean(SPEED_DEFAULTS_KEY, true).apply(); return }
        Thread.sleep(1500)
    }
}
