package com.limitchecker

import android.content.Context

/**
 * hub の接続情報。
 *
 * URL は平文で構わないが、**トークンは [TokenStore] が Android Keystore で
 * 暗号化して保持する**。SharedPreferences に平文で置かない。
 *
 * `android:allowBackup="false"` と併せて端末バックアップにも乗らない
 * （docs/security.md）。
 */
object Prefs {
    internal const val FILE = "limitchecker"
    private const val KEY_URL = "hub_url"
    private const val KEY_NOTIFICATION = "notification_enabled"
    private const val KEY_STATUS_ICON = "status_icon_mode"
    private const val KEY_WIDGET_BG = "widget_background"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hubUrl(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty()

    fun token(context: Context): String = TokenStore.load(context)

    fun isConfigured(context: Context): Boolean =
        hubUrl(context).isNotEmpty() && token(context).isNotEmpty()

    /** 通知センターに常設するか。 */
    fun notificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION, false)

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION, enabled).apply()
    }

    /** ステータスバーに何を出すか。 */
    fun statusIconMode(context: Context): StatusIconMode =
        StatusIconMode.fromKey(prefs(context).getString(KEY_STATUS_ICON, null))

    fun setStatusIconMode(context: Context, mode: StatusIconMode) {
        prefs(context).edit().putString(KEY_STATUS_ICON, mode.key).apply()
    }

    /** ウィジェットの背景の濃さ。 */
    fun widgetBackground(context: Context): WidgetBackground =
        WidgetBackground.fromKey(prefs(context).getString(KEY_WIDGET_BG, null))

    fun setWidgetBackground(context: Context, background: WidgetBackground) {
        prefs(context).edit().putString(KEY_WIDGET_BG, background.key).apply()
    }

    fun save(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .apply()
        TokenStore.save(context, token.trim())
    }
}
