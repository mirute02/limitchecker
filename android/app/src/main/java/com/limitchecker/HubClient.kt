package com.limitchecker

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * hub から GET /status を取る。
 *
 * 依存を増やさないため HttpURLConnection を使う。取得は冪等な GET のみで、
 * 再送で困る操作はない。
 *
 * トークンは Authorization ヘッダにのみ載せ、ログにも例外メッセージにも出さない。
 */
object HubClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BODY_BYTES = 256 * 1024

    sealed class Result {
        data class Ok(val status: Status) : Result()

        /** 設定がまだ。ウィジェットは案内を出す。 */
        object NotConfigured : Result()

        /** 取得できなかった。ウィジェットはグレー表示にする（D1）。 */
        data class Failed(val reason: String) : Result()
    }

    /**
     * 接続コードをトークンと交換する。
     *
     * 43文字のトークンを手で転記させないための入口。
     * 認証なしで受ける代わりに、hub 側でコードの有効期限・試行回数・使い捨てを守る。
     */
    fun pair(baseUrl: String, code: String): String? {
        val url = try {
            URL(baseUrl.trimEnd('/') + "/pair")
        } catch (e: Exception) {
            return null
        }
        if (url.protocol != "http" && url.protocol != "https") return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { out ->
                out.write("""{"code":"$code"}""".toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { stream ->
                String(stream.readNBytes(MAX_BODY_BYTES), Charsets.UTF_8)
            }
            org.json.JSONObject(body).optString("token", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun fetch(context: Context): Result {
        if (!Prefs.isConfigured(context)) return Result.NotConfigured

        val base = Prefs.hubUrl(context)
        val token = Prefs.token(context)

        val url = try {
            URL("$base/status")
        } catch (e: Exception) {
            return Result.Failed("URL が不正です")
        }
        if (url.protocol != "http" && url.protocol != "https") {
            return Result.Failed("http か https を指定してください")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            when (val code = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.use { stream ->
                        String(stream.readNBytes(MAX_BODY_BYTES), Charsets.UTF_8)
                    }
                    Result.Ok(Status.parse(body))
                }
                401, 403 -> Result.Failed("トークンが違います")
                else -> Result.Failed("hub が $code を返しました")
            }
        } catch (e: IOException) {
            // 到達できないのは普通のこと（hub 停止、圏外、Tailscale 未接続）。
            // 例外メッセージには URL が含まれうるので、そのまま表示はしない。
            Result.Failed("hub に接続できません")
        } catch (e: Exception) {
            Result.Failed("応答を解釈できません")
        } finally {
            connection?.disconnect()
        }
    }
}
