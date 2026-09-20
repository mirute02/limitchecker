package com.limitchecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon

/**
 * 通知センターに残量を常設する。
 *
 * **前景サービスは使わない。** Android 15 以降、dataSync 型の前景サービスは
 * 24時間あたり6時間までに制限されるため、常設表示には使えない。
 * 常設通知（setOngoing）自体は前景サービスなしで出せるので、
 * ウィジェットと同じ [RefreshWorker] から更新する。
 * 追加の常駐プロセスは持たない（docs/decisions.md D16）。
 *
 * ステータスバー（時計や電池が並ぶ行）に出せるのは小さなアイコン1つだけで、
 * 任意の文字は置けない。そこで残量の数字を描いたアイコンを実行時に生成する。
 * OS が小アイコンを単色で塗るため、**色は使えない**。形（数字）だけが残る。
 * 色分けは通知を開いた先で見せる。
 */
object StatusNotification {

    private const val CHANNEL_ID = "limitchecker_status"
    private const val NOTIFICATION_ID = 1

    /** ステータスバーのアイコンは小さい。この辺りが判読の下限。 */
    private const val ICON_SIZE_PX = 96

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_status),
            // 常に出ているものなので、音も割り込みもなし
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_status_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun update(context: Context, result: HubClient.Result) {
        if (!Prefs.notificationEnabled(context)) {
            cancel(context)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)

        val now = System.currentTimeMillis() / 1000
        val claude = (result as? HubClient.Result.Ok)?.status?.service("claude_code")
        val outer = claude?.ring("outer")
        val middle = claude?.ring("middle")
        val fresh = Freshness.of(claude?.updatedAtEpoch, now)
        val usable = claude?.available == true && outer != null && fresh != Freshness.EXPIRED

        val title = if (usable) {
            context.getString(
                R.string.notif_title,
                Math.round(outer!!.remaining * 100),
                Math.round((middle?.remaining ?: 0.0) * 100),
            )
        } else {
            context.getString(R.string.notif_title_unavailable)
        }

        val body = when {
            result is HubClient.Result.NotConfigured -> context.getString(R.string.notif_setup)
            result is HubClient.Result.Failed -> result.reason
            !usable -> context.getString(R.string.notif_stale)
            outer?.resetsAtEpoch != null ->
                context.getString(R.string.notif_reset, countdown(outer.resetsAtEpoch - now))
            else -> ""
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon(context, if (usable) outer!!.remaining else null))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(context))
            // 消せないようにする。常設が目的なので
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // 通知の権限が外された。設定は残すが、次回の許可まで出せない。
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * ステータスバー用のアイコンを実行時に描く。
     *
     * OS はアルファ値だけを使って単色で塗るため、白で描けばよい。
     * 色を指定しても反映されない。
     */
    private fun smallIcon(context: Context, remaining: Double?): Icon {
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        if (remaining == null) {
            // 取得できていないことを、数字ではなく形で示す
            paint.textSize = ICON_SIZE_PX * 0.80f
            canvas.drawText("?", ICON_SIZE_PX / 2f, ICON_SIZE_PX * 0.76f, paint)
        } else {
            val percent = Math.round(remaining * 100).coerceIn(0, 100)
            // 3桁になると潰れるので、100 は "99" 扱いにして桁を揃える
            val label = if (percent >= 100) "99" else percent.toString()
            paint.textSize = ICON_SIZE_PX * 0.74f
            canvas.drawText(label, ICON_SIZE_PX / 2f, ICON_SIZE_PX * 0.76f, paint)
        }
        return Icon.createWithBitmap(bitmap)
    }

    private fun countdown(seconds: Long): String {
        if (seconds <= 0) return "まもなく"
        if (seconds >= 86_400) return "${seconds / 86_400}日"
        return "${seconds / 3600}:${"%02d".format((seconds % 3600) / 60)}"
    }
}
