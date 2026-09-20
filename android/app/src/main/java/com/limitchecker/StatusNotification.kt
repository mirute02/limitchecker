package com.limitchecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon

/**
 * 通知センターに残量を常設する。
 *
 * **前景サービスは使わない。** Android 15 以降、dataSync 型の前景サービスは
 * 24時間あたり6時間までに制限されるため、常設表示には使えない。
 * 常設通知（setOngoing）自体は前景サービスなしで出せるので、
 * ウィジェットと同じ [RefreshWorker] から更新する（docs/decisions.md D16）。
 *
 * 見せ方は3段階（D18）。
 *   ステータスバー : ドーナツの形。単色に塗られるが濃淡は残るので読める
 *   折りたたみ時   : リングの絵 + 残量の数字
 *   展開時         : ウィジェットと同じ絵を大きく
 */
object StatusNotification {

    private const val CHANNEL_ID = "limitchecker_status"
    private const val NOTIFICATION_ID = 1

    private const val STATUS_ICON_PX = 96
    private const val BADGE_PX = 192
    private const val BIG_WIDTH_PX = 1024
    private const val BIG_HEIGHT_PX = 448

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

        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val now = System.currentTimeMillis() / 1000
        val scheme = Prefs.colorScheme(context)

        val choice = Prefs.serviceChoice(context)
        val status = (result as? HubClient.Result.Ok)?.status

        val primary = status?.service(choice.primaryServiceId)
        val outer = primary?.ring("outer")
        val middle = primary?.ring("middle")
        val fresh = Freshness.of(primary?.updatedAtEpoch, now)
        val usable = primary?.available == true && outer != null && fresh != Freshness.EXPIRED

        // 「両方」のときは、両サービスの5時間枠を並べる
        val secondary = if (choice == ServiceChoice.BOTH) status?.service("codex") else null
        val secondaryOuter = secondary?.ring("outer")

        val title = when {
            !usable -> context.getString(R.string.notif_title_unavailable)
            choice == ServiceChoice.BOTH && secondaryOuter != null ->
                context.getString(
                    R.string.notif_title_both,
                    Math.round(outer!!.remaining * 100),
                    Math.round(secondaryOuter.remaining * 100),
                )
            else -> context.getString(
                R.string.notif_title,
                Math.round(outer!!.remaining * 100),
                Math.round((middle?.remaining ?: 0.0) * 100),
            )
        }

        val body = when {
            result is HubClient.Result.NotConfigured -> context.getString(R.string.notif_setup)
            result is HubClient.Result.Failed -> result.reason
            !usable -> context.getString(R.string.notif_stale)
            // 「両方」のときは週次枠も本文に入れる。題名には5時間枠しか入らないため。
            choice == ServiceChoice.BOTH && secondary != null -> context.getString(
                R.string.notif_body_both,
                Math.round((middle?.remaining ?: 0.0) * 100),
                Math.round((secondary.ring("middle")?.remaining ?: 0.0) * 100),
            )
            outer?.resetsAtEpoch != null ->
                context.getString(R.string.notif_reset, countdown(outer.resetsAtEpoch - now))
            else -> ""
        }

        val mode = Prefs.statusIconMode(context)
        val statusBitmap = if (
            choice == ServiceChoice.BOTH && mode == StatusIconMode.RINGS_BOTH
        ) {
            // 4つの値（両サービスの5時間枠と週次枠）を縦棒で出す。
            // 24dp に同心円を2本重ねると潰れるため、高さで表す（D34）。
            DonutRenderer.renderStatusBarBars(
                STATUS_ICON_PX,
                listOf(
                    if (usable) outer?.remaining else null,
                    if (usable) middle?.remaining else null,
                    secondary?.ring("outer")?.remaining,
                    secondary?.ring("middle")?.remaining,
                ),
            )
        } else {
            DonutRenderer.renderStatusBarIcon(
                sizePx = STATUS_ICON_PX,
                mode = mode,
                outer = if (usable) outer else null,
                // 「両方」の他モードでは、右半分を Codex の5時間枠にする
                middle = if (usable) {
                    if (choice == ServiceChoice.BOTH) secondaryOuter else middle
                } else null,
                nowEpoch = now,
            )
        }
        val statusIcon = Icon.createWithBitmap(statusBitmap)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(statusIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(context))
            // 消せないようにする。常設が目的なので
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            // アプリ名とアイコンに乗るアクセント色。逼迫時は朱色にして気づきやすくする
            .setColor(accentColor(scheme, outer?.remaining, usable, night))

        if (usable) {
            // 折りたたみ時は右端にリングの絵。数字だけより状態が伝わる
            builder.setLargeIcon(
                DonutRenderer.renderBadge(
                    BADGE_PX, outer,
                    if (choice == ServiceChoice.BOTH) secondaryOuter else middle,
                    night, dead = false, scheme = scheme,
                )
            )
            // 展開するとウィジェットと同じ絵が大きく出る
            builder.setStyle(
                Notification.BigPictureStyle()
                    .bigPicture(
                        DonutRenderer.render(
                            widthPx = BIG_WIDTH_PX,
                            heightPx = BIG_HEIGHT_PX,
                            result = result,
                            nowEpoch = now,
                            night = night,
                            widthDp = 400,
                            heightDp = 175,
                            scheme = scheme,
                        )
                    )
                    .setBigContentTitle(title)
                    .setSummaryText(body)
            )
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // 通知の権限が外された。設定は残すが、次回の許可まで出せない。
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** ウィジェットと同じ配色に合わせる。外側リングと同じ考え方（D12）。 */
    private fun accentColor(
        scheme: ColorScheme, remaining: Double?, usable: Boolean, night: Boolean,
    ): Int = when {
        !usable || remaining == null ->
            if (night) Color.parseColor("#6E6A70") else Color.parseColor("#9E9A9F")
        else -> scheme.ringColor("outer", remaining, night)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun countdown(seconds: Long): String {
        if (seconds <= 0) return "まもなく"
        if (seconds >= 86_400) return "${seconds / 86_400}日"
        return "${seconds / 3600}:${"%02d".format((seconds % 3600) / 60)}"
    }
}
