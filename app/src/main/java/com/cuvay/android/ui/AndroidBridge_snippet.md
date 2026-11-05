Pega esto en AndroidBridge.kt, justo ANTES de la linea `fun openDownloadedMedia(id: String): Boolean {`
(y antes de su `@JavascriptInterface`, para que no quede pegado a esa anotacion):

    fun downloadedUri(id: String): android.net.Uri? {
        val task = mediaTasks[sanitiseFilename(id)] ?: return null
        val f = task.savedFile
        if (f != null && f.exists()) {
            val u = runCatching { FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", f) }.getOrNull()
            if (u != null) return u
        }
        return task.savedUri
    }
