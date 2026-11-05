package com.cuvay.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

private const val CH = "cuvay_avisos"

/** Programa la revision del panel cada 15 min (el minimo de Android), aunque la app este cerrada. */
fun scheduleNotifyWorker(ctx: Context) {
    val req = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("cuvay_notify", ExistingPeriodicWorkPolicy.KEEP, req)
}

class NotifyWorker(private val ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        runCatching { Alerts.check(ctx, Alerts.localApi(ctx)) }
        val s = runCatching { fetchRemote(ctx, track = false) }.getOrNull() ?: return Result.retry()
        val a = s.optJSONArray("messages") ?: return Result.success()
        val prefs = ctx.getSharedPreferences(RPREFS, Context.MODE_PRIVATE)
        val seen = (prefs.getStringSet("nseen", emptySet()) ?: emptySet()).toMutableSet()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Avisos de Cuvay", NotificationManager.IMPORTANCE_HIGH))
        for (i in 0 until a.length()) {
            val m = a.getJSONObject(i)
            if (m.optString("kind") != "notif") continue
            val id = m.optInt("id"); if (id.toString() in seen) continue
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle(m.optString("title").ifBlank { "Cuvay" })
                .setContentText(m.optString("body"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(m.optString("body")))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
            runCatching { nm.notify(id, n); seen += id.toString() }
        }
        prefs.edit().putStringSet("nseen", seen).apply()
        return Result.success()
    }
}
