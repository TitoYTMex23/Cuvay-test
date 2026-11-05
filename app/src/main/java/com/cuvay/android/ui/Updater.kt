package com.cuvay.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Baja el APK a la cache de la app (con progreso 0..1). Corre en un hilo de IO. */
fun downloadApk(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
    require(url.startsWith("http")) { "enlace de descarga no configurado" }
    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
    val tmp = File(dir, "cuvay.part"); val out = File(dir, "cuvay.apk")
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 30000
    check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
    val total = c.contentLengthLong
    c.inputStream.use { inp ->
        tmp.outputStream().use { o ->
            val buf = ByteArray(64 * 1024); var done = 0L
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                o.write(buf, 0, n); done += n
                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }
    check(tmp.length() > 100_000) { "archivo incompleto" }
    out.delete(); check(tmp.renameTo(out)) { "no se pudo guardar" }
    return out
}

/** Abre el instalador del sistema. Devuelve false si falta el permiso "instalar apps desconocidas" (lo abre). */
fun installApk(ctx: Context, apk: File): Boolean {
    if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}
