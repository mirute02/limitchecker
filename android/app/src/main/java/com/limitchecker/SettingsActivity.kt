package com.limitchecker

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * hub の接続先とトークンを入れる画面。ウィジェット配置時の設定画面も兼ねる。
 *
 * 依存を増やさないため、レイアウトはコードで組む。
 * トークンは伏せ字で入力し、保存後に読み返して表示しない。
 * 保存先は [TokenStore]（Android Keystore で暗号化）。
 */
class SettingsActivity : Activity() {

    private enum class Os { LINUX, MAC }

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusText: TextView
    private lateinit var preview: ImageView
    private lateinit var instructions: LinearLayout
    private lateinit var linuxButton: Button
    private lateinit var macButton: Button

    private var osMode = Os.LINUX

    /** ウィジェット配置から呼ばれた場合の ID。通常起動では INVALID。 */
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val isConfigureFlow: Boolean
        get() = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // targetSdk 35 以降はウィンドウが画面全体に広がる（edge-to-edge）。
        // アクションバーを使うとその下にコンテンツが潜り込むため、自前で描く。
        actionBar?.hide()

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 設定画面が RESULT_OK を返さないと、Android はウィジェットの配置を取り消す。
        // 先に CANCELED を入れておき、保存できた時点で OK に差し替える。
        if (isConfigureFlow) {
            setResult(
                RESULT_CANCELED,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        }

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        // ---- 接続先 ----
        root.addView(label(getString(R.string.hub_url_label)))
        urlField = EditText(this).apply {
            setSingleLine()
            // TYPE_CLASS_TEXT と OR しないと入力クラスが TYPE_NULL になり、
            // 文字を受け付けない欄になる。
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = DEFAULT_URL
            setText(Prefs.hubUrl(this@SettingsActivity).ifEmpty { DEFAULT_URL })
        }
        root.addView(urlField, wide())

        // ---- トークン ----
        root.addView(label(getString(R.string.token_label)))
        tokenField = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // setSingleLine と inputType の設定順で変換方法が外れることがあるため、
            // 伏せ字を明示する。貼り付けた内容も必ず伏せ字になる。
            transformationMethod = PasswordTransformationMethod.getInstance()
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
        instructions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(Button(this).apply {
            text = getString(R.string.show_hub_steps)
            setOnClickListener {
                val showing = instructions.visibility == View.VISIBLE
                instructions.visibility = if (showing) View.GONE else View.VISIBLE
                text = getString(if (showing) R.string.show_hub_steps else R.string.hide_hub_steps)
            }
        }, wide())
        root.addView(instructions, wide())
        buildSteps()

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
        if (!Prefs.isConfigured(this)) {
            renderPreview(HubClient.Result.NotConfigured)
            return
        }
        // 設定済みなら実際に取りに行く。画面を開き直すたびに
        // 未接続のように見えてしまうのを避ける。
        statusText.text = getString(R.string.testing)
        fetchInBackground(completeConfigureOnFinish = false)
    }

    private fun saveAndTest() {
        val url = urlField.text.toString().trim()
        val typed = tokenField.text.toString().trim()
        val token = if (typed.isEmpty()) Prefs.token(this) else typed

        if (url.isEmpty() || token.isEmpty()) {
            statusText.text = getString(R.string.error_incomplete)
            return
        }

        Prefs.save(this, url, token)
        tokenField.setText("")
        tokenField.hint = getString(R.string.token_hint_saved)

        if (Prefs.token(this).isEmpty()) {
            statusText.text = getString(R.string.token_keystore_unavailable)
            return
        }

        statusText.text = getString(R.string.testing)
        fetchInBackground(completeConfigureOnFinish = true)
    }

    /** ネットワークはメインスレッドで触れないため別スレッドで取る。 */
    private fun fetchInBackground(completeConfigureOnFinish: Boolean) {
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
                // 配置中なら、ここで配置を確定させる。hub に届かなくても
                // ウィジェットは置けるようにする（状態は絵に出る）。
                if (completeConfigureOnFinish && isConfigureFlow) completeConfigure()
            }
        }.start()
    }

    private fun completeConfigure() {
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }

    private fun renderPreview(result: HubClient.Result) {
        preview.setImageBitmap(
            DonutRenderer.render(
                widthPx = dp(320),
                heightPx = dp(140),
                result = result,
                nowEpoch = System.currentTimeMillis() / 1000,
                night = isNight(),
            )
        )
    }

    private fun isNight() = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ------------------------------------------------------------------
    // hub の手順
    // ------------------------------------------------------------------

    /**
     * 手順を見出し・本文・コマンドに分けて組み立てる。
     * コマンドには「コピー」を付ける。端末で長押し選択させるのは現実的でない。
     *
     * 書式は行頭の記号で決まる。
     *   "# "  見出し
     *   "> "  コマンド（連続行は1ブロックにまとめる）
     *   "@linux" / "@mac" / "@all"  以降の表示対象を切り替える
     *   それ以外は本文
     */
    private fun buildSteps() {
        instructions.removeAllViews()
        instructions.addView(osSwitch(), wide())

        val codeBackground =
            if (isNight()) Color.parseColor("#2A282C") else Color.parseColor("#F1EFF3")
        val lines = getString(R.string.hub_setup_steps).split("\n")

        var visible = true
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("@") -> {
                    visible = when (line.trim()) {
                        "@linux" -> osMode == Os.LINUX
                        "@mac" -> osMode == Os.MAC
                        else -> true
                    }
                    index++
                }
                !visible -> index++
                line.startsWith("# ") -> {
                    instructions.addView(TextView(this).apply {
                        text = line.removePrefix("# ")
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(18), 0, dp(4))
                    }, wide())
                    index++
                }
                line.startsWith("> ") -> {
                    val commands = mutableListOf<String>()
                    while (index < lines.size && lines[index].startsWith("> ")) {
                        commands.add(lines[index].removePrefix("> "))
                        index++
                    }
                    instructions.addView(
                        codeBlock(commands.joinToString("\n"), codeBackground),
                        wide(),
                    )
                }
                line.isBlank() -> index++
                else -> {
                    instructions.addView(TextView(this).apply {
                        text = line
                        textSize = 13f
                        setPadding(0, dp(3), 0, dp(3))
                        setLineSpacing(0f, 1.25f)
                    }, wide())
                    index++
                }
            }
        }
    }

    /** Linux と macOS を切り替える。自分に関係ない手順を読まずに済む。 */
    private fun osSwitch(): LinearLayout {
        linuxButton = Button(this).apply {
            text = getString(R.string.os_linux)
            setOnClickListener { setOs(Os.LINUX) }
        }
        macButton = Button(this).apply {
            text = getString(R.string.os_mac)
            setOnClickListener { setOs(Os.MAC) }
        }
        applyOsHighlight()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                linuxButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                macButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private fun setOs(os: Os) {
        if (osMode == os) return
        osMode = os
        buildSteps()
    }

    private fun applyOsHighlight() {
        // 選択中がどちらかを濃さで示す
        linuxButton.alpha = if (osMode == Os.LINUX) 1f else 0.45f
        macButton.alpha = if (osMode == Os.MAC) 1f else 0.45f
    }

    private fun codeBlock(command: String, backgroundColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(backgroundColor)
            }
            setPadding(dp(12), dp(10), dp(12), dp(6))

            addView(TextView(this@SettingsActivity).apply {
                text = command
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
            }, wide())

            addView(LinearLayout(this@SettingsActivity).apply {
                gravity = Gravity.END
                addView(Button(this@SettingsActivity).apply {
                    text = getString(R.string.copy)
                    textSize = 12f
                    minimumHeight = dp(36)
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener { copyToClipboard(command) }
                })
            }, wide())
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        // ラベルは通知に出ることがあるため、中身が推測できない名前にする
        clipboard.setPrimaryClip(ClipData.newPlainText("limitchecker", text))
        // Android 13 以降はシステムが自前でコピー通知を出すため重ねない
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------

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
