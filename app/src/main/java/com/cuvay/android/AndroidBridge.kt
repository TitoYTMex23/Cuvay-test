package com.cuvay.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

class AndroidBridge(private val activity: Activity) {

    private val prefs by lazy {
        activity.getSharedPreferences(CuvayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    // Recolor the status bar + gesture/navigation bar to match the web theme so
    // they don't stay black against a light app. colorHex is the resolved
    // --bg2; dark=true means the app is in dark theme (→ light bar icons).
    @JavascriptInterface
    fun setSystemBars(colorHex: String, dark: Boolean) {
        activity.runOnUiThread {
            val color = try {
                Color.parseColor(colorHex.trim())
            } catch (e: Exception) {
                Color.parseColor(if (dark) "#0e1621" else "#f0f2f5")
            }
            val window = activity.window
            @Suppress("DEPRECATION")
            window.statusBarColor = color
            @Suppress("DEPRECATION")
            window.navigationBarColor = color
            // Fill the inset gaps behind the bars too (covers Android 15's
            // enforced edge-to-edge, where the *BarColor setters are ignored).
            activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(color)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            // Light bars (dark icons) in light theme; light icons in dark theme.
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    // ===== App lifecycle (back-button confirmation) =====

    @JavascriptInterface
    fun minimizeApp() {
        Handler(Looper.getMainLooper()).post {
            activity.moveTaskToBack(true)
        }
    }

    @JavascriptInterface
    fun killApp() {
        Handler(Looper.getMainLooper()).post {
            activity.stopService(Intent(activity, CuvayService::class.java))
            activity.finishAndRemoveTask()
            // Belt-and-braces: ensure the JVM exits even if a stray
            // foreground notification or pending Intent keeps the
            // process alive after finishAndRemoveTask().
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    // ===== Language =====

    @JavascriptInterface
    fun setLang(lang: String) {
        prefs.edit().putString(PREF_LANG, lang).apply()
    }

    @JavascriptInterface
    fun getLang(): String {
        // Empty string when never set — the first-run language modal
        // uses this to decide whether to show. A default like "fa"
        // would silently suppress the modal on a fresh install.
        return prefs.getString(PREF_LANG, "") ?: ""
    }

    // ===== Password =====

    @JavascriptInterface
    fun hasPassword(): Boolean {
        return prefs.getString(PREF_PASSWORD_HASH, null) != null
    }

    @JavascriptInterface
    fun setPassword(password: String): Boolean {
        if (password.isEmpty()) return false
        prefs.edit().putString(PREF_PASSWORD_HASH, sha256(password)).apply()
        return true
    }

    @JavascriptInterface
    fun removePassword(currentPassword: String): Boolean {
        val stored = prefs.getString(PREF_PASSWORD_HASH, null) ?: return false
        if (sha256(currentPassword) != stored) return false
        prefs.edit().remove(PREF_PASSWORD_HASH).apply()
        return true
    }

    @JavascriptInterface
    fun checkPassword(password: String): Boolean {
        val stored = prefs.getString(PREF_PASSWORD_HASH, null) ?: return true
        return sha256(password) == stored
    }

    // ===== Media handoff to system apps =====
    // The web frontend calls these for save / open / share when it
    // detects window.Android — WebView can't natively download blob URLs,
    // navigate to blob URLs in new tabs, or do navigator.share with files.

    @JavascriptInterface
    fun openMedia(base64: String, mime: String, filename: String): Boolean {
        return try {
            val uri = writeToCache(base64, filename)
            val resolvedMime = mimeFromFilename(filename, mime)
            openUriWithSystem(uri, resolvedMime, filename)
        } catch (e: Exception) {
            Log.e(TAG, "openMedia failed", e)
            toast("Open failed: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun shareMedia(base64: String, mime: String, filename: String): Boolean {
        return try {
            val uri = writeToCache(base64, filename)
            val resolvedMime = mimeFromFilename(filename, mime)
            shareUriWithSystem(uri, resolvedMime, filename)
        } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun saveMedia(base64: String, mime: String, filename: String): Boolean {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) }
                   catch (e: Exception) { toast("Save failed: bad data"); Log.e(TAG, "saveMedia decode", e); return false }
        val safe = sanitiseFilename(filename)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!hasLegacyStoragePermission()) {
                requestLegacyStoragePermission()
                toast("Storage permission needed — please tap Save again")
                return false
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activity.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFromFilename(safe, mime))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val target = resolver.insert(collection, values)
                if (target == null) {
                    toast("Save failed: no Downloads URI")
                    return false
                }
                resolver.openOutputStream(target).use { os ->
                    if (os == null) {
                        resolver.delete(target, null, null)
                        toast("Save failed: cannot open Downloads")
                        return false
                    }
                    os.write(bytes)
                    os.flush()
                }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(target, done, null, null)
                toast("Saved to Downloads/$safe")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, safe)
                FileOutputStream(out).use { it.write(bytes) }
                toast("Saved to ${out.absolutePath}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveMedia failed", e)
            toast("Save failed: ${e.message}")
            false
        }
    }

    // ── Resumable Multi-File Media Download Engine (up to 2 GB) ──────────
    // Supports pause, resume (HTTP Range: bytes=offset-), cancel, and
    // streaming CRC32 + SHA-256 verification for any file type (ZIP, ZST,
    // APK, MP4, MKV, WebP, AVIF, PDF, etc.) without passing Base64 across JNI.

    private class MediaDownloadTask(
        val id: String,
        @Volatile var url: String,
        @Volatile var filename: String,
        @Volatile var expectedSize: Long,
        @Volatile var expectedCrcHex: String,
        @Volatile var expectedSha256Hex: String
    ) {
        val lock = Any()
        @Volatile var state: String = "idle" // idle | running | paused | verifying | done | error | canceled
        @Volatile var received: Long = 0L
        @Volatile var total: Long = expectedSize
        @Volatile var speedBps: Long = 0L
        @Volatile var detail: String = ""
        @Volatile var crcHex: String = ""
        @Volatile var sha256Hex: String = ""
        @Volatile var crcOk: Boolean = true
        @Volatile var sha256Ok: Boolean = true
        @Volatile var resumable: Boolean = true
        @Volatile var savedFile: File? = null
        @Volatile var savedUri: Uri? = null
        @Volatile var mime: String = "application/octet-stream"
        @Volatile var pauseRequested: Boolean = false
        @Volatile var cancelRequested: Boolean = false
        @Volatile var generation: Int = 0
    }

    private val mediaTasks = ConcurrentHashMap<String, MediaDownloadTask>()

    private fun stagingDir(): File {
        val base = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(activity.filesDir, "downloads")
        if (!base.exists()) base.mkdirs()
        runCatching { File(base, ".nomedia").createNewFile() }   // la galeria no escanea esta carpeta
        return base
    }

    /** Las descargas cuyo nombre empieza con esto (fotos del Feed) se quedan privadas en la app. */
    private val quietPrefix = "feed_"

    /** Borra las fotos privadas del Feed de mas de 3 dias para que no se llene el telefono. */
    private fun cleanQuietPhotos() {
        runCatching {
            val limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            stagingDir().listFiles()?.forEach { if (it.name.startsWith(quietPrefix) && it.lastModified() < limit) it.delete() }
        }
    }

    private fun resolveFullUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("gh://")) {
            return trimmed
        }
        val port = prefs.getInt(CuvayService.PREF_PORT, 38000).let { if (it > 0) it else 38000 }
        val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "http://127.0.0.1:$port$path"
    }

    /**
     * Starts or resumes a native streaming download for any MIME type up to 2 GB.
     */
    @JavascriptInterface
    fun startMediaDownload(
        id: String,
        url: String,
        filename: String,
        expectedSize: Long,
        expectedCrcHex: String,
        expectedSha256Hex: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyStoragePermission()) {
            requestLegacyStoragePermission()
            toast("Storage permission needed — please tap Download again")
            return false
        }
        val safeId = sanitiseFilename(id.ifBlank { "dl_${System.currentTimeMillis()}" })
        val safeName = sanitiseFilename(filename.ifBlank { "$safeId.bin" })
        val fullUrl = resolveFullUrl(url)
        if (safeName.startsWith(quietPrefix)) cleanQuietPhotos()

        val task = mediaTasks.computeIfAbsent(safeId) {
            MediaDownloadTask(
                id = safeId,
                url = fullUrl,
                filename = safeName,
                expectedSize = expectedSize,
                expectedCrcHex = expectedCrcHex.trim().lowercase(),
                expectedSha256Hex = expectedSha256Hex.trim().lowercase()
            )
        }

        val gen: Int
        synchronized(task.lock) {
            if (task.state == "running" || task.state == "verifying") return true
            task.url = fullUrl
            if (filename.isNotBlank()) task.filename = safeName
            if (expectedSize > 0) {
                task.expectedSize = expectedSize
                if (task.total <= 0) task.total = expectedSize
            }
            if (expectedCrcHex.isNotBlank()) task.expectedCrcHex = expectedCrcHex.trim().lowercase()
            if (expectedSha256Hex.isNotBlank()) task.expectedSha256Hex = expectedSha256Hex.trim().lowercase()
            task.pauseRequested = false
            task.cancelRequested = false
            task.state = "running"
            task.detail = ""
            task.generation++
            gen = task.generation
        }

        Thread({ runMediaDownloadTask(task, gen) }, "cuvay-media-$safeId").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @JavascriptInterface
    fun pauseMediaDownload(id: String): Boolean {
        val safeId = sanitiseFilename(id)
        val task = mediaTasks[safeId] ?: return false
        synchronized(task.lock) {
            if (task.state != "running") return false
            task.pauseRequested = true
            task.state = "paused"
            task.speedBps = 0L
            task.detail = "Paused"
        }
        return true
    }

    @JavascriptInterface
    fun resumeMediaDownload(id: String): Boolean {
        val safeId = sanitiseFilename(id)
        val task = mediaTasks[safeId] ?: return false
        return startMediaDownload(
            task.id,
            task.url,
            task.filename,
            task.expectedSize,
            task.expectedCrcHex,
            task.expectedSha256Hex
        )
    }

    @JavascriptInterface
    fun cancelMediaDownload(id: String): Boolean {
        val safeId = sanitiseFilename(id)
        val task = mediaTasks[safeId] ?: return false
        synchronized(task.lock) {
            task.cancelRequested = true
            task.pauseRequested = false
            task.generation++
            task.state = "canceled"
            task.speedBps = 0L
            task.received = 0L
            task.detail = "Canceled"
        }
        try {
            File(stagingDir(), "$safeId.part").delete()
        } catch (_: Exception) { }
        return true
    }

    @JavascriptInterface
    fun mediaDownloadStatus(id: String): String {
        val safeId = sanitiseFilename(id)
        val task = mediaTasks[safeId]
        if (task == null) {
            val partFile = File(stagingDir(), "$safeId.part")
            if (partFile.exists() && partFile.length() > 0) {
                return "{\"id\":\"${jsonEscape(safeId)}\",\"state\":\"paused\"," +
                    "\"received\":${partFile.length()},\"total\":0,\"speedBps\":0," +
                    "\"crcOk\":true,\"sha256Ok\":true,\"resumable\":true,\"detail\":\"Paused\"}"
            }
            return "{\"id\":\"${jsonEscape(safeId)}\",\"state\":\"idle\",\"received\":0,\"total\":0,\"speedBps\":0,\"crcOk\":true,\"sha256Ok\":true,\"resumable\":true,\"detail\":\"\"}"
        }
        return synchronized(task.lock) {
            "{\"id\":\"${jsonEscape(task.id)}\"," +
                "\"state\":\"${jsonEscape(task.state)}\"," +
                "\"received\":${task.received}," +
                "\"total\":${task.total}," +
                "\"speedBps\":${task.speedBps}," +
                "\"filename\":\"${jsonEscape(task.filename)}\"," +
                "\"mime\":\"${jsonEscape(task.mime)}\"," +
                "\"crc\":\"${jsonEscape(task.crcHex)}\"," +
                "\"sha256\":\"${jsonEscape(task.sha256Hex)}\"," +
                "\"crcOk\":${task.crcOk}," +
                "\"sha256Ok\":${task.sha256Ok}," +
                "\"resumable\":${task.resumable}," +
                "\"detail\":\"${jsonEscape(task.detail)}\"}"
        }
    }



    fun downloadedUri(id: String): android.net.Uri? {
        val task = mediaTasks[sanitiseFilename(id)] ?: return null
        val f = task.savedFile
        if (f != null && f.exists()) {
            val u = runCatching { FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", f) }.getOrNull()
            if (u != null) return u
        }
        return task.savedUri
    }

    /** Archivo privado de una descarga terminada: sirve para decodificar fotos sin pasar por FileProvider. */
    fun downloadedFile(id: String): File? {
        val f = mediaTasks[sanitiseFilename(id)]?.savedFile
        return if (f != null && f.exists()) f else null
    }

    @JavascriptInterface

    fun openDownloadedMedia(id: String): Boolean {
        val safeId = sanitiseFilename(id)
        val task = mediaTasks[safeId] ?: return false
        val mime = mimeFromFilename(task.filename, task.mime)
        val file = task.savedFile
        if (file != null && file.exists()) {
            return try {
                val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
                openUriWithSystem(uri, mime, task.filename)
            } catch (e: Exception) {
                val u = task.savedUri
                if (u != null) openUriWithSystem(u, mime, task.filename) else false
            }
        }
        val uri = task.savedUri ?: return false
        return openUriWithSystem(uri, mime, task.filename)
    }

    @JavascriptInterface
    fun shareDownloadedMedia(id: String): Boolean {
        val safeId = sanitiseFilename(id)
        val task = mediaTasks[safeId] ?: return false
        val mime = mimeFromFilename(task.filename, task.mime)
        val file = task.savedFile
        if (file != null && file.exists()) {
            return try {
                val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
                shareUriWithSystem(uri, mime, task.filename)
            } catch (e: Exception) {
                val u = task.savedUri
                if (u != null) shareUriWithSystem(u, mime, task.filename) else false
            }
        }
        val uri = task.savedUri ?: return false
        return shareUriWithSystem(uri, mime, task.filename)
    }

    /**
     * Baja el archivo cifrado del relay de GitHub (AES-256-GCM: nonce 12 B || cifrado || etiqueta 16 B),
     * lo descifra en memoria y lo escribe completo en partFile. Devuelve false si se pauso/cancelo.
     */
    private fun ghFetchDecrypt(task: MediaDownloadTask, partFile: File, gen: Int): Boolean {
        val u = Uri.parse(task.url)
        u.getQueryParameter("err")?.let { throw java.io.IOException(it) }
        val httpsUrl = u.getQueryParameter("u") ?: throw java.io.IOException("URL de GitHub invalida")
        val keyHex = u.getQueryParameter("k") ?: throw java.io.IOException("Falta la clave del relay")
        val token = u.getQueryParameter("t").orEmpty()
        val conn = (URL(httpsUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github.raw")
            setRequestProperty("User-Agent", "cuvay-android")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        val blob: ByteArray
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                val left = conn.getHeaderField("X-RateLimit-Remaining")
                throw java.io.IOException(
                    if (left == "0") "GitHub: limite de consultas alcanzado, intenta mas tarde" else "GitHub HTTP $code"
                )
            }
            if (code == 404) throw java.io.IOException("GitHub: el archivo aun no esta en el repo (HTTP 404)")
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("GitHub HTTP $code")
            val cipherLen = conn.contentLengthLong
            synchronized(task.lock) {
                if (task.generation == gen) {
                    task.received = 0L
                    if (task.expectedSize > 0) task.total = task.expectedSize
                    task.resumable = false
                }
            }
            val out = java.io.ByteArrayOutputStream(if (cipherLen in 1..Int.MAX_VALUE.toLong()) cipherLen.toInt() else 64 * 1024)
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                var windowStart = System.currentTimeMillis()
                var windowBytes = 0L
                while (true) {
                    if (task.cancelRequested || task.generation != gen) throw DownloadCanceled()
                    if (task.pauseRequested) {
                        synchronized(task.lock) {
                            if (task.generation == gen) {
                                task.state = "paused"
                                task.speedBps = 0L
                                task.detail = "Paused"
                            }
                        }
                        return false
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    windowBytes += n
                    val now = System.currentTimeMillis()
                    val elapsed = now - windowStart
                    synchronized(task.lock) {
                        if (task.generation == gen) {
                            task.received = if (cipherLen > 0 && task.expectedSize > 0) got * task.expectedSize / cipherLen else got
                            if (elapsed >= 400) {
                                task.speedBps = (windowBytes * 1000L) / elapsed
                                windowStart = now
                                windowBytes = 0L
                            }
                        }
                    }
                }
            }
            blob = out.toByteArray()
        } finally {
            conn.disconnect()
        }
        if (blob.size < 12 + 16) throw java.io.IOException("Archivo de GitHub incompleto")
        synchronized(task.lock) {
            if (task.generation == gen) {
                task.state = "verifying"
                task.speedBps = 0L
                task.detail = "Descifrando..."
            }
        }
        val keyBytes = ByteArray(keyHex.length / 2) { keyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12)
        )
        val plain = try {
            cipher.doFinal(blob, 12, blob.size - 12)
        } catch (e: Exception) {
            throw java.io.IOException("No se pudo descifrar (clave distinta a la del servidor)")
        }
        FileOutputStream(partFile, false).use { it.write(plain) }
        return true
    }

    private fun runMediaDownloadTask(task: MediaDownloadTask, gen: Int) {
        val partFile = File(stagingDir(), "${task.id}.part")
        val finalAppFile = File(stagingDir(), task.filename)
        try {
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            if (task.expectedSize > 0 && existingBytes > task.expectedSize) {
                partFile.delete()
                existingBytes = 0L
            }

            // NUBE directa (gh://): baja el blob cifrado de GitHub, lo descifra y deja el archivo
            // completo en .part; luego sigue la verificacion normal (CRC32/SHA-256).
            if (task.url.startsWith("gh://") && (task.expectedSize <= 0 || existingBytes < task.expectedSize)) {
                partFile.delete()
                if (!ghFetchDecrypt(task, partFile, gen)) return
                existingBytes = partFile.length()
            }

            // If the part file is already complete (e.g. paused at 100%), jump straight to verification.
            if (task.expectedSize <= 0 || existingBytes < task.expectedSize) {
                val conn = (URL(task.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL && code != 416) {
                        throw java.io.IOException("HTTP $code " + (runCatching { conn.url.host + conn.url.path.take(24) }.getOrDefault("")))
                    }
                    val headerName = conn.getHeaderField("X-Download-Filename")
                    if (!headerName.isNullOrBlank()) {
                        task.filename = sanitiseFilename(headerName)
                    }
                    val serverCrc = conn.getHeaderField("X-Content-CRC32")
                    if (!serverCrc.isNullOrBlank() && task.expectedCrcHex.isBlank()) {
                        task.expectedCrcHex = serverCrc.trim().lowercase()
                    }
                    val serverSha = conn.getHeaderField("X-Content-SHA256")
                    if (!serverSha.isNullOrBlank() && task.expectedSha256Hex.isBlank()) {
                        task.expectedSha256Hex = serverSha.trim().lowercase()
                    }
                    val acceptRanges = conn.getHeaderField("Accept-Ranges")
                    task.resumable = (code == HttpURLConnection.HTTP_PARTIAL) ||
                        (acceptRanges != null && acceptRanges.contains("bytes", ignoreCase = true))

                    if (code != 416) {
                        val append = (code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                        if (!append) {
                            existingBytes = 0L
                        }
                        val contentLen = conn.contentLengthLong
                        val totalBytes = when {
                            contentLen > 0 && append -> existingBytes + contentLen
                            contentLen > 0 -> contentLen
                            else -> task.expectedSize
                        }
                        synchronized(task.lock) {
                            if (task.generation == gen) {
                                task.received = existingBytes
                                if (totalBytes > 0) task.total = totalBytes
                            }
                        }

                        conn.inputStream.use { input ->
                            FileOutputStream(partFile, append).use { os ->
                                val buf = ByteArray(64 * 1024)
                                var got = existingBytes
                                var windowStart = System.currentTimeMillis()
                                var windowBytes = 0L

                                while (true) {
                                    if (task.cancelRequested || task.generation != gen) {
                                        throw DownloadCanceled()
                                    }
                                    if (task.pauseRequested) {
                                        os.flush()
                                        synchronized(task.lock) {
                                            if (task.generation == gen) {
                                                task.state = "paused"
                                                task.speedBps = 0L
                                                task.detail = "Paused"
                                            }
                                        }
                                        return
                                    }
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, n)
                                    got += n
                                    windowBytes += n
                                    val now = System.currentTimeMillis()
                                    val elapsed = now - windowStart
                                    synchronized(task.lock) {
                                        if (task.generation == gen) {
                                            task.received = got
                                            if (elapsed >= 400) {
                                                task.speedBps = (windowBytes * 1000L) / elapsed
                                                windowStart = now
                                                windowBytes = 0L
                                            }
                                        }
                                    }
                                }
                                os.flush()
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }

            // Verify size, CRC32, and SHA-256 on disk without loading into memory.
            synchronized(task.lock) {
                if (task.generation != gen) return
                task.state = "verifying"
                task.speedBps = 0L
                task.detail = "Verifying hash..."
            }

            val actualSize = partFile.length()
            if (task.expectedSize > 0 && actualSize != task.expectedSize) {
                partFile.delete()
                throw java.io.IOException("Size mismatch ($actualSize != ${task.expectedSize})")
            }

            val crc32 = CRC32()
            val sha256Digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(partFile).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (task.cancelRequested || task.generation != gen) throw DownloadCanceled()
                    val n = fis.read(buf)
                    if (n < 0) break
                    crc32.update(buf, 0, n)
                    sha256Digest.update(buf, 0, n)
                }
            }

            val computedCrc = "%08x".format(crc32.value)
            val computedSha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            val crcMatches = task.expectedCrcHex.isBlank() ||
                task.expectedCrcHex.trimStart('0').ifEmpty { "0" } == computedCrc.trimStart('0').ifEmpty { "0" } ||
                task.expectedCrcHex == computedCrc
            val shaMatches = task.expectedSha256Hex.isBlank() || task.expectedSha256Hex == computedSha256

            if (!crcMatches) {
                partFile.delete()
                synchronized(task.lock) {
                    task.crcOk = false
                    task.state = "error"
                    task.detail = "CRC32 mismatch ($computedCrc != ${task.expectedCrcHex})"
                }
                return
            }
            if (!shaMatches) {
                partFile.delete()
                synchronized(task.lock) {
                    task.sha256Ok = false
                    task.state = "error"
                    task.detail = "SHA-256 mismatch"
                }
                return
            }

            val resolvedMime = mimeFromFilename(task.filename, "application/octet-stream")
            // Keep a copy in app external files for instant FileProvider open/share,
            // and export to public Downloads/ so the user can access it from any file manager.
            if (finalAppFile.exists()) finalAppFile.delete()
            if (!partFile.renameTo(finalAppFile)) {
                partFile.copyTo(finalAppFile, overwrite = true)
                partFile.delete()
            }

            val quiet = task.filename.startsWith(quietPrefix)   // fotos del Feed: privadas, sin Descargas ni aviso
            val publicUri = if (quiet) null else exportFileToPublicDownloads(finalAppFile, task.filename, resolvedMime)
            synchronized(task.lock) {
                if (task.generation == gen) {
                    task.received = actualSize
                    task.total = actualSize
                    task.crcHex = computedCrc
                    task.sha256Hex = computedSha256
                    task.crcOk = true
                    task.sha256Ok = true
                    task.mime = resolvedMime
                    task.savedFile = finalAppFile
                    task.savedUri = publicUri
                    task.state = "done"
                    task.detail = task.filename
                }
            }
            if (!quiet) toast("Saved to Downloads/${task.filename}")
        } catch (_: DownloadCanceled) {
            partFile.delete()
            synchronized(task.lock) {
                if (task.generation == gen) {
                    task.state = "canceled"
                    task.speedBps = 0L
                    task.detail = "Canceled"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runMediaDownloadTask failed for ${task.id}", e)
            synchronized(task.lock) {
                if (task.generation == gen) {
                    task.state = "error"
                    task.speedBps = 0L
                    task.detail = "[b5] " + (e.message ?: "Download failed") + (if (task.url.startsWith("gh://")) " (GitHub)" else " (nucleo/servidor)")
                }
            }
        }
    }

    private fun exportFileToPublicDownloads(sourceFile: File, displayName: String, mimeType: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = activity.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(target)?.use { os ->
                    FileInputStream(sourceFile).use { fis ->
                        fis.copyTo(os, bufferSize = 64 * 1024)
                    }
                    os.flush()
                }
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                target
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, displayName)
                sourceFile.copyTo(out, overwrite = true)
                FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "exportFileToPublicDownloads fallback to app file", e)
            try {
                FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", sourceFile)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun openUriWithSystem(uri: Uri, resolvedMime: String, filename: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                putExtra(Intent.EXTRA_TITLE, filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {
                activity.startActivity(Intent.createChooser(intent, filename).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "openUriWithSystem failed", e)
            toast("Open failed: ${e.message}")
            false
        }
    }

    private fun shareUriWithSystem(uri: Uri, resolvedMime: String, filename: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(intent, filename).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Legacy single-slot streaming download (app updates) ──────────────

    private val dlLock = Any()
    private var dlState = "idle" // idle | running | done | error | canceled
    private var dlReceived = 0L
    private var dlTotal = 0L
    private var dlDetail = "" // saved name on success, message on error

    @Volatile private var dlCancel = false
    @Volatile private var dlGen = 0

    private class DownloadCanceled : java.io.IOException("canceled")

    @JavascriptInterface
    fun startDownload(url: String, filename: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyStoragePermission()) {
            requestLegacyStoragePermission()
            toast("Storage permission needed — please tap Update again")
            return false
        }
        var gen = 0
        synchronized(dlLock) {
            if (dlState == "running") return true // already in flight
            dlCancel = false
            dlGen++
            gen = dlGen
            dlState = "running"
            dlReceived = 0L
            dlTotal = 0L
            dlDetail = ""
        }
        val safe = sanitiseFilename(filename)
        Thread({ runDownload(url, safe, gen) }, "cuvay-download").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @JavascriptInterface
    fun cancelDownload() {
        synchronized(dlLock) {
            if (dlState != "running") return
            dlState = "canceled"
            dlDetail = ""
        }
        dlCancel = true
    }

    @JavascriptInterface
    fun downloadStatus(): String = synchronized(dlLock) {
        "{\"state\":\"$dlState\",\"received\":$dlReceived," +
            "\"total\":$dlTotal,\"detail\":\"${jsonEscape(dlDetail)}\"}"
    }

    private fun finishDownload(gen: Int, state: String, detail: String) {
        synchronized(dlLock) {
            if (gen != dlGen) return
            dlState = state
            dlDetail = detail
        }
    }

    private fun discardPartial(resolver: android.content.ContentResolver, target: Uri?, legacy: File?) {
        if (target != null) {
            try { resolver.delete(target, null, null) } catch (ignored: Exception) { }
        }
        if (legacy != null) {
            try { legacy.delete() } catch (ignored: Exception) { }
        }
    }

    private fun runDownload(url: String, fallbackName: String, gen: Int) {
        val resolver = activity.contentResolver
        var target: Uri? = null
        var legacy: File? = null
        var safe = fallbackName
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode / 100 != 2) throw java.io.IOException("HTTP ${conn.responseCode}")
                val len = conn.contentLengthLong
                synchronized(dlLock) { if (gen == dlGen) dlTotal = if (len > 0) len else 0L }
                val header = conn.getHeaderField("X-Download-Filename")
                if (!header.isNullOrBlank()) safe = sanitiseFilename(header)

                val out: java.io.OutputStream
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeFromFilename(safe, "application/octet-stream"))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw java.io.IOException("no Downloads URI")
                    out = resolver.openOutputStream(target)
                        ?: throw java.io.IOException("cannot open Downloads")
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, safe)
                    legacy = f
                    out = FileOutputStream(f)
                }

                conn.inputStream.use { input ->
                    out.use { os ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            if (dlCancel || gen != dlGen) throw DownloadCanceled()
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            synchronized(dlLock) { if (gen == dlGen) dlReceived = got }
                        }
                        os.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target != null) {
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
            }
            finishDownload(gen, "done", safe)
            toast("Saved to Downloads/$safe")
        } catch (e: DownloadCanceled) {
            discardPartial(resolver, target, legacy)
            finishDownload(gen, "canceled", "")
        } catch (e: Exception) {
            Log.e(TAG, "startDownload failed", e)
            discardPartial(resolver, target, legacy)
            finishDownload(gen, "error", e.message ?: "download failed")
        }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun hasLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLegacyStoragePermission() {
        Handler(Looper.getMainLooper()).post {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_LEGACY_STORAGE
            )
        }
    }

    /** Guarda en Descargas una descarga privada (foto del Feed) SOLO cuando el usuario lo pide. Llamar fuera del hilo principal. */
    fun saveDownloadedMedia(id: String): Boolean {
        val task = mediaTasks[sanitiseFilename(id)] ?: return false
        val f = task.savedFile ?: return false
        if (!f.exists()) return false
        val name = task.filename.removePrefix(quietPrefix)
        val ok = exportFileToPublicDownloads(f, name, task.mime) != null
        toast(if (ok) "Saved to Downloads/$name" else "Save failed")
        return ok
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeToCache(base64: String, filename: String): Uri {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val dir = File(activity.cacheDir, "shared")
        dir.mkdirs()
        val out = File(dir, sanitiseFilename(filename))
        FileOutputStream(out).use { it.write(bytes) }
        return FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", out)
    }

    private fun sanitiseFilename(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return if (cleaned.isEmpty()) "media" else cleaned
    }

    private fun sanitiseMime(m: String): String {
        return if (m.matches(Regex("^[\\w./+-]+$"))) m else "application/octet-stream"
    }

    // Universal MIME resolver supporting all archive, video, audio, image,
    // package, and document formats without any "JPG-only" restriction.
    private fun mimeFromFilename(name: String, fallback: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return sanitiseMime(fallback)
        val ext = name.substring(dot + 1).lowercase()
        return when (ext) {
            "apk", "xapk", "apks" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip", "cbz" -> "application/zip"
            "zst", "zstd" -> "application/zstd"
            "7z" -> "application/x-7z-compressed"
            "rar", "cbr" -> "application/vnd.rar"
            "tar" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            "bz2", "tbz2" -> "application/x-bzip2"
            "xz", "txz" -> "application/x-xz"
            "iso" -> "application/x-iso9660-image"
            "mp3" -> "audio/mpeg"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "mts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "txt", "log", "md", "ini", "conf", "yaml", "yml" -> "text/plain"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "epub" -> "application/epub+zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/msword"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> sanitiseMime(fallback.ifBlank { "application/octet-stream" })
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREF_PASSWORD_HASH = "password_hash"
        const val PREF_LANG = "app_lang"
        const val REQ_LEGACY_STORAGE = 0x501
        private const val TAG = "AndroidBridge"
    }
}
