package com.limitchecker

import android.content.Context

/**
 * hub の接続情報。
 *
 * アプリ専用領域に保存する。`android:allowBackup="false"` と組み合わせることで
 * 端末バックアップにも乗らない（docs/security.md）。
 * トークンはログに出さない。画面に出すときもマスクする。
 */
object Prefs {
    private const val FILE = "limitchecker"
    private const val KEY_URL = "hub_url"
    private const val KEY_TOKEN = "token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hubUrl(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty()

    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    fun isConfigured(context: Context): Boolean =
        hubUrl(context).isNotEmpty() && token(context).isNotEmpty()

    fun save(context: Context, url: String, token: String) {
        prefs(context).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }
}
