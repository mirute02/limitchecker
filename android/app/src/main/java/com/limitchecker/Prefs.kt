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

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hubUrl(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty()

    fun token(context: Context): String = TokenStore.load(context)

    fun isConfigured(context: Context): Boolean =
        hubUrl(context).isNotEmpty() && token(context).isNotEmpty()

    fun save(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .apply()
        TokenStore.save(context, token.trim())
    }
}
