package com.limitchecker

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusText: TextView
    private lateinit var preview: ImageView
    private lateinit var instructions: LinearLayout
    private lateinit var notificationButton: Button
    /** ウィジェット配置から呼ばれた場合の ID。通常起動では INVALID。 */
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** 背景を切り替えたときに再描画するため、直近の取得結果を持っておく。 */
    private var lastResult: HubClient.Result = HubClient.Result.NotConfigured

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
        root.addView(label(getString(R.string.scheme_label)))
        root.addView(schemeChooser(), wide())
        root.addView(note(getString(R.string.scheme_note)))

        root.addView(label(getString(R.string.bg_label)))
        root.addView(backgroundChooser(), wide())

        root.addView(label(getString(R.string.preview_label)))
        preview = ImageView(this).apply { adjustViewBounds = true }
        root.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)),
        )

        // ---- 通知センターへの常設 ----
        root.addView(label(getString(R.string.notification_label)))
        notificationButton = Button(this).apply {
            setOnClickListener { toggleNotification() }
        }
        root.addView(notificationButton, wide())
        root.addView(note(getString(R.string.notification_note)))
        applyNotificationLabel()

        // ---- ステータスバーに出すもの ----
        root.addView(label(getString(R.string.status_icon_label)))
        root.addView(statusIconChooser(), wide())
        root.addView(note(getString(R.string.status_icon_note)))

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

    // ------------------------------------------------------------------
    // 通知センターへの常設
    // ------------------------------------------------------------------

    private fun applyNotificationLabel() {
        notificationButton.text = getString(
            if (Prefs.notificationEnabled(this)) R.string.notification_disable
            else R.string.notification_enable
        )
    }

    private fun toggleNotification() {
        if (Prefs.notificationEnabled(this)) {
            Prefs.setNotificationEnabled(this, false)
            StatusNotification.cancel(this)
            RefreshWorker.syncSchedule(this)
            applyNotificationLabel()
            return
        }

        // Android 13 以降は通知に実行時の許可が要る。本アプリで唯一の許可ダイアログ。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
            return
        }
        enableNotification()
    }

    private fun enableNotification() {
        Prefs.setNotificationEnabled(this, true)
        StatusNotification.ensureChannel(this)
        RefreshWorker.syncSchedule(this)
        // すぐ出す。次の定期実行まで15分待たせない。
        RefreshWorker.refreshNow(this, force = true)
        applyNotificationLabel()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enableNotification()
        } else {
            statusText.text = getString(R.string.notification_denied)
        }
    }

    /**
     * 配色を選ばせる。文字だけでは分からないので、色の見本を添える。
     * 左が外側（5時間枠）、右が中央（週次枠）。
     */
    private fun schemeChooser(): RadioGroup {
        val current = Prefs.colorScheme(this)
        val night = isNight()
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            ColorScheme.entries.forEach { scheme ->
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val button = RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = getString(scheme.labelRes)
                    tag = scheme
                    isChecked = scheme == current
                    setPadding(paddingLeft, dp(6), dp(8), dp(6))
                }
                addView(button, wide())
                // 見本は選択肢の下に小さく置く
                val (outer, middle) = scheme.sampleColors(night)
                addView(ImageView(this@SettingsActivity).apply {
                    setImageBitmap(swatch(outer, middle))
                    setPadding(dp(32), 0, 0, dp(8))
                }, LinearLayout.LayoutParams(dp(96), dp(16)))
            }
            setOnCheckedChangeListener { group, checkedId ->
                val scheme = group.findViewById<RadioButton>(checkedId)?.tag as? ColorScheme
                    ?: return@setOnCheckedChangeListener
                Prefs.setColorScheme(this@SettingsActivity, scheme)
                renderPreview(lastResult)
                RefreshWorker.refreshNow(this@SettingsActivity, force = true)
            }
        }
    }

    /** 配色の見本。2色を並べた小さな帯。 */
    private fun swatch(outer: Int, middle: Int): Bitmap {
        val w = dp(96)
        val h = dp(16)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = h / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = outer
        canvas.drawRoundRect(RectF(0f, 0f, w / 2f - dp(2), h.toFloat()), radius, radius, paint)
        paint.color = middle
        canvas.drawRoundRect(RectF(w / 2f + dp(2), 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        return bitmap
    }

    /** ウィジェットの背景の濃さを選ばせる。選んだらプレビューに即反映する。 */
    private fun backgroundChooser(): RadioGroup {
        val current = Prefs.widgetBackground(this)
        return RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            WidgetBackground.entries.forEach { background ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = getString(background.labelRes)
                    tag = background
                    isChecked = background == current
                })
            }
            setOnCheckedChangeListener { group, checkedId ->
                val background = group.findViewById<RadioButton>(checkedId)?.tag as? WidgetBackground
                    ?: return@setOnCheckedChangeListener
                Prefs.setWidgetBackground(this@SettingsActivity, background)
                renderPreview(lastResult)
                RefreshWorker.refreshNow(this@SettingsActivity, force = true)
            }
        }
    }

    /**
     * ステータスバーに出す内容を選ばせる。
     * 変えたら即座に描き直す。次の定期実行まで待たせない。
     */
    private fun statusIconChooser(): RadioGroup {
        val current = Prefs.statusIconMode(this)
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            StatusIconMode.entries.forEach { mode ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    text = getString(mode.labelRes)
                    tag = mode
                    isChecked = mode == current
                    setPadding(paddingLeft, dp(6), paddingRight, dp(6))
                })
            }
            setOnCheckedChangeListener { group, checkedId ->
                val mode = group.findViewById<RadioButton>(checkedId)?.tag as? StatusIconMode
                    ?: return@setOnCheckedChangeListener
                Prefs.setStatusIconMode(this@SettingsActivity, mode)
                if (Prefs.notificationEnabled(this@SettingsActivity)) {
                    RefreshWorker.refreshNow(this@SettingsActivity, force = true)
                }
            }
        }
    }

    private fun renderPreview(result: HubClient.Result) {
        lastResult = result
        preview.setImageBitmap(
            DonutRenderer.render(
                widthPx = dp(320),
                heightPx = dp(140),
                result = result,
                nowEpoch = System.currentTimeMillis() / 1000,
                night = isNight(),
                heightDp = 140,
                background = Prefs.widgetBackground(this),
                scheme = Prefs.colorScheme(this),
            )
        )
    }

    private fun isNight() = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ------------------------------------------------------------------
    // hub の手順
    // ------------------------------------------------------------------

    /**
     * 手順を組み立てる。区切り記号の解析はしない。
     * [SetupSteps] が型で分けて持っているため、コマンドには必ず
     * 個別のコピーボタンが付く（docs/decisions.md D22）。
     */
    private fun buildSteps() {
        instructions.removeAllViews()
        val codeBackground =
            if (isNight()) Color.parseColor("#2A282C") else Color.parseColor("#F1EFF3")

        SetupSteps.items.forEach { item ->
            when (item) {
                is SetupSteps.Item.Head -> {
                    instructions.addView(TextView(this).apply {
                        text = item.text
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(18), 0, dp(2))
                    }, wide())
                    // どこで作業するかを見出しごとに示す。
                    // これがないと、スマホでコマンドを打とうとして詰まる。
                    instructions.addView(TextView(this).apply {
                        text = getString(item.where.labelRes)
                        textSize = 12f
                        setPadding(0, 0, 0, dp(4))
                        setTextColor(
                            if (item.where == SetupSteps.Where.THIS_PHONE) {
                                if (isNight()) Color.parseColor("#56B4E9")
                                else Color.parseColor("#0072B2")
                            } else {
                                if (isNight()) Color.parseColor("#E69F00")
                                else Color.parseColor("#B87400")
                            }
                        )
                    }, wide())
                }

                is SetupSteps.Item.Body -> instructions.addView(TextView(this).apply {
                    text = item.text
                    textSize = 13f
                    setPadding(0, dp(4), 0, dp(4))
                    setLineSpacing(0f, 1.25f)
                }, wide())

                is SetupSteps.Item.Command ->
                    instructions.addView(codeBlock(item.text, codeBackground), wide())
            }
        }
    }


    /** コマンド1つと、それをコピーするボタンを横に並べる。 */
    private fun codeBlock(command: String, backgroundColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(backgroundColor)
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
            // 行の間隔を空けて、どれが1コマンドかを見て分かるようにする
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            layoutParams = params

            addView(TextView(this@SettingsActivity).apply {
                text = command
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageButton(this@SettingsActivity).apply {
                setImageBitmap(copyGlyph())
                background = null
                // 目で見えるラベルがないので、読み上げ用に必ず付ける
                contentDescription = getString(R.string.copy)
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener { copyToClipboard(command) }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
    }

    /**
     * コピーの図形を描く。重なった2枚の紙。
     *
     * 端末やテーマで有無が変わる標準アイコンに頼らず自前で描く。
     * 文字と同じ色にするので、明暗どちらのテーマでも馴染む。
     */
    private fun copyGlyph(): Bitmap {
        val size = dp(20)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = size * 0.09f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = if (isNight()) Color.parseColor("#E6E1E5") else Color.parseColor("#1C1B1F")
        }
        val radius = size * 0.14f
        // 奥の紙（左上）
        canvas.drawRoundRect(
            RectF(size * 0.14f, size * 0.14f, size * 0.66f, size * 0.66f),
            radius, radius, paint,
        )
        // 手前の紙（右下）。重なりが分かるよう、背景色で縁取ってから描く
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isNight()) Color.parseColor("#2A282C") else Color.parseColor("#F1EFF3")
        }
        canvas.drawRoundRect(
            RectF(size * 0.34f - stroke, size * 0.34f - stroke, size * 0.90f, size * 0.90f),
            radius, radius, cut,
        )
        canvas.drawRoundRect(
            RectF(size * 0.34f, size * 0.34f, size * 0.86f, size * 0.86f),
            radius, radius, paint,
        )
        return bitmap
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
        private const val REQUEST_NOTIFICATIONS = 1
    }
}
