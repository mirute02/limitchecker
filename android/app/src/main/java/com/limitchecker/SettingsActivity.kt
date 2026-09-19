package com.limitchecker

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * hub の接続先とトークンを入れる画面。
 *
 * 依存を増やさないため、レイアウトはコードで組む。
 * トークンは伏せ字で入力し、保存後に読み返して表示しない。
 * 保存先は [TokenStore]（Android Keystore で暗号化）。
 */
class SettingsActivity : Activity() {

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusText: TextView
    private lateinit var preview: ImageView
    private lateinit var instructions: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // targetSdk 35 以降はウィンドウが画面全体に広がる（edge-to-edge）。
        // アクションバーを使うと、その下にコンテンツが潜り込んで先頭が隠れる。
        // タイトルは自前で描き、余白はインセットから明示的に入れる。
        actionBar?.hide()

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        // ---- 接続先 ----
        root.addView(label(getString(R.string.hub_url_label)))
        urlField = EditText(this).apply {
            // TYPE_CLASS_TEXT と OR しないと入力クラスが TYPE_NULL になり、
            // 文字を受け付けない欄になる。
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            hint = DEFAULT_URL
            val saved = Prefs.hubUrl(this@SettingsActivity)
            setText(saved.ifEmpty { DEFAULT_URL })
        }
        root.addView(urlField, wide())

        // ---- トークン ----
        root.addView(label(getString(R.string.token_label)))
        tokenField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            hint = if (Prefs.token(this@SettingsActivity).isEmpty()) {
                getString(R.string.token_hint_empty)
            } else {
                getString(R.string.token_hint_saved)
            }
        }
        root.addView(tokenField, wide())

        root.addView(note(getString(R.string.token_storage_note)))
        if (!TokenStore.isUsable()) {
            root.addView(note(getString(R.string.token_keystore_unavailable)))
        }

        root.addView(Button(this).apply {
            text = getString(R.string.save_and_test)
            setOnClickListener { saveAndTest() }
        }, wide())

        statusText = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
            text = getString(R.string.settings_help)
        }
        root.addView(statusText)

        // ---- プレビュー ----
        root.addView(label(getString(R.string.preview_label)))
        preview = ImageView(this).apply { adjustViewBounds = true }
        root.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)),
        )

        // ---- hub の手順 ----
        instructions = TextView(this).apply {
            text = getString(R.string.hub_setup_steps)
            setPadding(0, dp(12), 0, 0)
            setTextIsSelectable(true)
            // コマンドを含むので等幅にする。桁が揃わないと読みにくい。
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            visibility = android.view.View.GONE
        }
        root.addView(Button(this).apply {
            text = getString(R.string.show_hub_steps)
            setOnClickListener {
                val showing = instructions.visibility == android.view.View.VISIBLE
                instructions.visibility =
                    if (showing) android.view.View.GONE else android.view.View.VISIBLE
                text = getString(
                    if (showing) R.string.show_hub_steps else R.string.hide_hub_steps
                )
            }
        }, wide())
        root.addView(instructions)

        val scroll = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // ステータスバー・ナビゲーションバー・キーボードのぶんだけ内側に寄せる
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // 画面を開くたびに通信はしない。保存済みかどうかだけを反映する。
        renderPreview(
            if (Prefs.isConfigured(this)) {
                HubClient.Result.Failed(getString(R.string.preview_tap_test))
            } else {
                HubClient.Result.NotConfigured
            }
        )
    }

    private fun saveAndTest() {
        val url = urlField.text.toString().trim()
        val typed = tokenField.text.toString().trim()
        // 空欄なら保存済みのトークンを保つ
        val token = if (typed.isEmpty()) Prefs.token(this) else typed

        if (url.isEmpty() || token.isEmpty()) {
            statusText.text = getString(R.string.error_incomplete)
            return
        }

        Prefs.save(this, url, token)
        tokenField.setText("")
        tokenField.hint = getString(R.string.token_hint_saved)

        if (Prefs.token(this).isEmpty()) {
            // Keystore に保存できなかった。平文では置かない。
            statusText.text = getString(R.string.token_keystore_unavailable)
            return
        }

        statusText.text = getString(R.string.testing)

        // ネットワークはメインスレッドで触れない
        Thread {
            val result = HubClient.fetch(this)
            val message = when (result) {
                is HubClient.Result.Ok -> {
                    val claude = result.status.service("claude_code")
                    val outer = claude?.ring("outer")
                    if (claude?.available == true && outer != null) {
                        getString(R.string.ok_with_value, Math.round(outer.remaining * 100))
                    } else {
                        getString(R.string.ok_no_data)
                    }
                }
                is HubClient.Result.NotConfigured -> getString(R.string.error_incomplete)
                is HubClient.Result.Failed -> getString(R.string.error_failed, result.reason)
            }
            runOnUiThread {
                statusText.text = message
                renderPreview(result)
                WidgetRenderer.updateAll(this, result)
            }
        }.start()
    }

    private fun renderPreview(result: HubClient.Result) {
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        preview.setImageBitmap(
            DonutRenderer.render(
                widthPx = dp(320),
                heightPx = dp(140),
                result = result,
                nowEpoch = System.currentTimeMillis() / 1000,
                night = night,
            )
        )
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, dp(6), 0, 0)
        alpha = 0.75f
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val DEFAULT_URL = "http://127.0.0.1:8787"
    }
}
