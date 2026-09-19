package com.limitchecker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * hub のトークンを端末内で暗号化して保持する。
 *
 * 鍵は Android Keystore が持ち、アプリからは取り出せない。対応端末では
 * ハードウェア（TEE / StrongBox）に格納され、アプリの領域を吸い出されても
 * 鍵がないため復号できない。
 *
 * `setUserAuthenticationRequired` は指定しない。画面ロック中でもウィジェットが
 * 残量を取りに行く必要があるため（WorkManager はロック中も走る）。
 *
 * 平文で保存していた版からの移行も行う。
 */
object TokenStore {

    private const val KEY_ALIAS = "limitchecker_token_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private const val PREF_ENCRYPTED = "token_enc"

    /** 平文で保存していた旧キー。読み出したら暗号化して置き換える。 */
    private const val PREF_LEGACY_PLAIN = "token"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, token: String) {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val stored = try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
            }
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val body = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$iv:$body"
        } catch (e: Exception) {
            // Keystore が使えない端末では保存しない。平文で置くくらいなら
            // 「未設定」のままにして、その旨を画面に出す。
            null
        }

        prefs.edit().apply {
            if (stored != null) putString(PREF_ENCRYPTED, stored) else remove(PREF_ENCRYPTED)
            remove(PREF_LEGACY_PLAIN)
            apply()
        }
    }

    fun load(context: Context): String {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

        prefs.getString(PREF_ENCRYPTED, null)?.let { stored ->
            val parts = stored.split(":")
            if (parts.size != 2) return ""
            return try {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                if (iv.size != IV_BYTES) return ""
                val body = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                String(cipher.doFinal(body), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }

        // 旧版が平文で保存していたものを拾い、暗号化して置き換える
        val legacy = prefs.getString(PREF_LEGACY_PLAIN, null)
        if (!legacy.isNullOrEmpty()) {
            save(context, legacy)
            return legacy
        }
        return ""
    }

    fun clear(context: Context) {
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
            .remove(PREF_ENCRYPTED)
            .remove(PREF_LEGACY_PLAIN)
            .apply()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // 鍵が消せなくても、暗号文を消してあるので読めない
        }
    }

    /** Keystore が使える端末か。使えない場合は画面で知らせる。 */
    fun isUsable(): Boolean = try {
        secretKey()
        true
    } catch (e: Exception) {
        false
    }
}
