package com.limitchecker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * ウィジェットの絵を Bitmap として描く。
 *
 * RemoteViews は Canvas を直接扱えないため、ここで描いた Bitmap を ImageView に流す
 * （docs/design.md）。ウィジェット全体を1枚に描くことで、RemoteViews のレイアウト制約を
 * 避けて配置を完全に制御する。
 *
 * 配色の考え方（docs/decisions.md D12）:
 *   赤と緑で状態を表すのは、最も多い型の色覚特性（P型・D型）で区別できなくなる。
 *   そこで役割を分ける。
 *     - **色相はリングの識別**に使う。外側=青、中央=オレンジ。
 *       この組み合わせは P型・D型・T型のいずれでも判別できる（Okabe-Ito の推奨対）。
 *     - **残量の多寡は弧の長さ**で表す。色に頼らないので誰でも読める。
 *     - **逼迫（20%未満）だけ**を朱色にし、さらに線を太くする。
 *       色以外の手がかりを重ねることで、色だけに依存しないようにする。
 */
object DonutRenderer {

    /**
     * Okabe-Ito の色覚バリアフリー配色から採った値。
     * 暗い背景では同じ色相を明るくして、コントラストを確保する。
     */
    private class Palette(val isNight: Boolean) {
        private val night = isNight
        val background = if (night) Color.parseColor("#1C1B1F") else Color.WHITE
        val text = if (night) Color.parseColor("#E6E1E5") else Color.parseColor("#1C1B1F")
        val subText = if (night) Color.parseColor("#A8A2AB") else Color.parseColor("#5F5F63")
        val track = if (night) Color.parseColor("#3A383C") else Color.parseColor("#E4E1E6")

        val gray = if (night) Color.parseColor("#6E6A70") else Color.parseColor("#9E9A9F")
    }

    /** これを下回ったら線を太くする。色は [ColorScheme] が決める。 */
    private const val CRITICAL = ColorScheme.CRITICAL

    /** この幅（dp）を下回ったらドーナツを1つだけ出す。2つ並べると小さくなりすぎるため。 */
    private const val TWO_COLUMN_MIN_DP = 190

    /** これを下回ったら省略形。サービス名を出さず、リングと時間だけにする。 */
    private const val COMPACT_MAX_DP = 112

    fun render(
        widthPx: Int,
        heightPx: Int,
        result: HubClient.Result,
        nowEpoch: Long,
        night: Boolean,
        widthDp: Int = TWO_COLUMN_MIN_DP,
        heightDp: Int = 110,
        background: WidgetBackground = WidgetBackground.OPAQUE,
        scheme: ColorScheme = ColorScheme.DEFAULT,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = Palette(night)

        if (background.alpha > 0) {
            val radius = minOf(width, height) * 0.10f
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.background
                    alpha = background.alpha
                },
            )
        }
        // 背景を抜くと壁紙の色次第で文字が沈むため、影で輪郭を残す
        val shadow = background != WidgetBackground.OPAQUE

        when (result) {
            is HubClient.Result.NotConfigured ->
                drawMessage(canvas, width, height, palette, "タップして hub を設定", shadow)
            is HubClient.Result.Failed ->
                drawMessage(canvas, width, height, palette, result.reason, shadow)
            is HubClient.Result.Ok ->
                drawServices(
                    canvas, width, height, palette, result.status, nowEpoch,
                    widthDp, heightDp, shadow, scheme,
                )
        }
        return bitmap
    }

    /** 透過時の可読性を保つための影。明るい壁紙でも暗い壁紙でも輪郭が残る。 */
    private fun Paint.applyShadow(enabled: Boolean, night: Boolean, size: Float) {
        if (!enabled) return
        setShadowLayer(size * 0.14f, 0f, 0f, if (night) Color.BLACK else Color.WHITE)
    }

    private fun drawMessage(
        canvas: Canvas, width: Int, height: Int, palette: Palette, message: String,
        shadow: Boolean = false,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subText
            textAlign = Paint.Align.CENTER
            textSize = minOf(width, height) * 0.11f
            applyShadow(shadow, palette.isNight, textSize)
        }
        canvas.drawText(message, width / 2f, height / 2f + paint.textSize / 3f, paint)
    }

    private fun drawServices(
        canvas: Canvas,
        width: Int,
        height: Int,
        palette: Palette,
        status: Status,
        nowEpoch: Long,
        widthDp: Int,
        heightDp: Int,
        shadow: Boolean,
        scheme: ColorScheme,
    ) {
        // 入る形に合わせて並べ方を決める（D25）。
        //   横に広い   : 横並び2つ
        //   縦に長い   : 縦積み2つ。どちらも同じ大きさになる
        //   それ以外   : 1つだけ
        val horizontal = widthDp >= TWO_COLUMN_MIN_DP
        val vertical = !horizontal && heightDp >= widthDp * 1.6f
        val count = if (horizontal || vertical) 2 else 1

        val ids = if (count == 2) listOf("claude_code", "codex") else listOf("claude_code")
        val cellWidth = if (horizontal) width / 2f else width.toFloat()
        val cellHeight = if (vertical) height / 2f else height.toFloat()
        // 1セルの幅が狭ければ要素を減らして読める大きさを保つ
        val cellWidthDp = if (horizontal) widthDp / 2 else widthDp
        val compact = cellWidthDp < COMPACT_MAX_DP || (vertical && heightDp / 2 < COMPACT_MAX_DP)

        ids.forEachIndexed { index, id ->
            drawService(
                canvas = canvas,
                left = if (horizontal) cellWidth * index else 0f,
                top = if (vertical) cellHeight * index else 0f,
                width = cellWidth,
                height = cellHeight,
                palette = palette,
                service = status.service(id),
                fallbackLabel = if (id == "codex") "Codex" else "Claude Code",
                nowEpoch = nowEpoch,
                compact = compact,
                shadow = shadow,
                scheme = scheme,
            )
        }
    }

    private fun drawService(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        palette: Palette,
        service: Service?,
        fallbackLabel: String,
        nowEpoch: Long,
        compact: Boolean,
        shadow: Boolean,
        scheme: ColorScheme,
    ) {
        // 余白は短いほうの辺で決める。幅だけで決めると、横に広くて背が低い形で
        // 余白が過大になり、ドーナツが極端に小さくなる。
        val shortSide = minOf(width, height)
        val padding = shortSide * (if (compact) 0.06f else 0.10f)

        val innerWidth = width - padding * 2
        val innerHeight = height - padding * 2
        // ラベルを置かない場合に取れる大きさ。これが基準になる。
        val fullDiameter = minOf(innerWidth, innerHeight)
        if (fullDiameter <= 0f) return

        // 省略形ではサービス名を出さない。その分リングを大きく取る。
        var labelSize = if (compact) 0f else (shortSide * 0.12f)
        var diameter = minOf(innerWidth, innerHeight - labelSize * 1.6f)

        // ラベルのせいでグラフが目に見えて小さくなるなら、ラベルを捨てて
        // グラフを優先する。どの形でも同じ大きさのグラフが出るようにするため（D28）。
        if (labelSize > 0f && diameter < fullDiameter * 0.78f) {
            labelSize = 0f
            diameter = fullDiameter
        }
        if (diameter <= 0f) return

        // 縦に余裕があるときは、中身を縦中央に寄せて空白を作らない。
        val contentHeight = diameter + labelSize * 1.6f
        val innerTop = top + ((height - contentHeight) / 2f).coerceAtLeast(padding)

        val centerX = left + width / 2f
        val centerY = innerTop + diameter / 2f
        val stroke = diameter * 0.095f
        val gap = stroke * 0.55f

        val freshness = Freshness.of(service?.updatedAtEpoch, nowEpoch)
        val dead = service == null || !service.available || freshness == Freshness.EXPIRED
        val alpha = if (freshness == Freshness.STALE && !dead) 150 else 255

        val outerRadius = diameter / 2f - stroke / 2f
        val middleRadius = outerRadius - stroke - gap

        val outer = service?.ring("outer")
        val middle = service?.ring("middle")

        drawRing(canvas, centerX, centerY, outerRadius, stroke, palette, outer, dead, alpha, scheme)
        drawRing(canvas, centerX, centerY, middleRadius, stroke, palette, middle, dead, alpha, scheme)

        // 中心にはリセットまでの残り時間を置く。常に5時間枠（D8）。
        // 残量の数字は置かない。弧の長さが残量を表しているため（D13）。
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dead) palette.gray else palette.text
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = diameter * (if (compact) 0.26f else 0.19f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            applyShadow(shadow, palette.isNight, textSize)
        }
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subText
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = diameter * 0.11f
            applyShadow(shadow, palette.isNight, textSize)
        }

        val centerText = when {
            service == null || !service.available -> "取得不可"
            freshness == Freshness.EXPIRED -> "未更新"
            outer?.resetsAtEpoch != null -> formatCountdown(outer.resetsAtEpoch - nowEpoch)
            else -> "—"
        }
        canvas.drawText(centerText, centerX, centerY + centerPaint.textSize * 0.35f, centerPaint)

        // 省略形では補足も名前も出さない。入れても読めない大きさになる。
        if (compact) return

        if (!dead && centerText != "—") {
            canvas.drawText("後に回復", centerX, centerY + centerPaint.textSize * 1.25f, captionPaint)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subText
            textAlign = Paint.Align.CENTER
            textSize = labelSize
            applyShadow(shadow, palette.isNight, textSize)
        }
        canvas.drawText(
            service?.label ?: fallbackLabel,
            centerX,
            innerTop + diameter + labelSize * 1.1f,
            labelPaint,
        )
    }

    private fun drawRing(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        stroke: Float,
        palette: Palette,
        ring: Ring?,
        dead: Boolean,
        alpha: Int,
        scheme: ColorScheme,
    ) {
        if (radius <= 0f) return

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = palette.track
        }
        canvas.drawCircle(centerX, centerY, radius, trackPaint)

        if (dead || ring == null) return

        val isCritical = ring.remaining < CRITICAL
        // 逼迫時は色だけでなく線の太さも変える。色に依存しない手がかりを重ねる。
        val width = if (isCritical) stroke * 1.25f else stroke
        val bounds = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            color = scheme.ringColor(ring.slot, ring.remaining, palette.isNight)
            this.alpha = alpha
        }
        // 残量を12時方向から時計回りに描く。残量が減ると弧が短くなる。
        val sweep = (ring.remaining * 360.0).toFloat()
        if (sweep > 0.5f) {
            canvas.drawArc(bounds, -90f, sweep, false, arcPaint)
        }
    }


    // ------------------------------------------------------------------
    // 通知向けの描画
    // ------------------------------------------------------------------

    /**
     * ステータスバー（時計や電池が並ぶ行）用のアイコン。
     *
     * OS は小アイコンを**アルファ値だけを使って**単色で塗る。色は反映されないが、
     * **半透明は半透明のまま残る**。そこで薄いトラックの上に濃い弧を重ねると、
     * 単色でもドーナツとして読める。電池アイコンと同じ読み方ができる。
     *
     * 何を出すかは利用者が選ぶ（[StatusIconMode]）。24dp しかないため、
     * どのモードでも出す情報は1種類に絞る。
     */
    fun renderStatusBarIcon(
        sizePx: Int,
        mode: StatusIconMode,
        outer: Ring?,
        middle: Ring?,
        nowEpoch: Long,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        // 取得できていないことは、どのモードでも同じ形で示す
        val unavailable = when (mode) {
            StatusIconMode.RING_WEEK, StatusIconMode.PCT_WEEK -> middle == null
            else -> outer == null
        }
        if (unavailable) {
            drawIconText(canvas, size, "?")
            return bitmap
        }

        when (mode) {
            StatusIconMode.RINGS_BOTH -> {
                // 同心円を2本にすると 24dp では潰れて1本に見える。
                // 円を上下に分け、それぞれ半周で表す。半周ぶんの太さを使えるので
                // 小さくても両方読める。上が5時間枠、下が週次枠。
                drawSplitRing(canvas, size, outer!!.remaining, middle?.remaining)
            }
            StatusIconMode.RING_5H, StatusIconMode.RING_WEEK -> {
                // 1本だけなら太く描ける。形が読み取りやすくなる。
                val ring = if (mode == StatusIconMode.RING_5H) outer!! else middle!!
                val stroke = size * 0.26f
                val radius = center - stroke / 2f - size * 0.05f
                drawIconRing(canvas, center, radius, stroke, ring.remaining)
            }
            StatusIconMode.PCT_5H, StatusIconMode.PCT_WEEK -> {
                val ring = if (mode == StatusIconMode.PCT_5H) outer!! else middle!!
                val percent = Math.round(ring.remaining * 100).coerceIn(0, 100)
                // 3桁は潰れるので 100 は 99 に丸める
                drawIconText(canvas, size, if (percent >= 100) "99" else percent.toString())
            }
            StatusIconMode.RESET_5H -> {
                drawIconText(canvas, size, shortCountdown(outer!!.resetsAtEpoch, nowEpoch))
            }
        }
        return bitmap
    }

    /** 文字数に応じて大きさを変える。3文字でも潰れないようにする。 */
    private fun drawIconText(canvas: Canvas, size: Int, text: String) {
        val scale = when (text.length) {
            1 -> 0.80f
            2 -> 0.74f
            else -> 0.56f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size * scale
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
    }

    /** アイコンに収まる長さにする。"1h" / "45m" / "4d" の3文字まで。 */
    private fun shortCountdown(resetsAtEpoch: Long?, nowEpoch: Long): String {
        if (resetsAtEpoch == null) return "?"
        val remain = resetsAtEpoch - nowEpoch
        if (remain <= 0) return "0"
        return when {
            remain >= 86_400 -> "${remain / 86_400}d"
            remain >= 3_600 -> "${remain / 3_600}h"
            else -> "${remain / 60}m"
        }
    }

    /**
     * 上半分と下半分に分けて2つの値を表す。
     *
     * 上（左から右へ時計回り）が5時間枠、下（左から右へ反時計回り）が週次枠。
     * 両方満タンなら1つの円になる。左右の端に隙間を空けて、上下が別物だと分かるようにする。
     */
    private fun drawSplitRing(canvas: Canvas, size: Int, top: Double, bottom: Double?) {
        val center = size / 2f
        val stroke = size * 0.22f
        val radius = center - stroke / 2f - size * 0.04f
        val bounds = RectF(center - radius, center - radius, center + radius, center + radius)
        // 左右に空ける隙間（度）。上下の境目をはっきりさせる。
        val gap = 12f
        val span = 180f - gap

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
            color = Color.WHITE
            alpha = 70
        }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
            color = Color.WHITE
        }

        // 上半分: 180度（左）から時計回りに
        canvas.drawArc(bounds, 180f + gap / 2f, span, false, track)
        val topSweep = (span * top).toFloat()
        if (topSweep > 1f) canvas.drawArc(bounds, 180f + gap / 2f, topSweep, false, arc)

        // 下半分: 180度（左）から反時計回りに
        canvas.drawArc(bounds, 180f - gap / 2f, -span, false, track)
        if (bottom != null) {
            val bottomSweep = (span * bottom).toFloat()
            if (bottomSweep > 1f) {
                canvas.drawArc(bounds, 180f - gap / 2f, -bottomSweep, false, arc)
            }
        }
    }

    private fun drawIconRing(
        canvas: Canvas, center: Float, radius: Float, stroke: Float, remaining: Double,
    ) {
        // トラックは薄く。OS の単色化でも濃淡は残る。
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.WHITE
            alpha = 70
        }
        canvas.drawCircle(center, center, radius, track)

        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
            color = Color.WHITE
        }
        val bounds = RectF(center - radius, center - radius, center + radius, center + radius)
        val sweep = (remaining * 360.0).toFloat()
        if (sweep > 1f) canvas.drawArc(bounds, -90f, sweep, false, arc)
    }

    /**
     * 通知の右側に出すアイコン。背景は透明にして、リングだけを見せる。
     * ウィジェットと同じ色使いなので、並べても違和感が出ない。
     */
    fun renderBadge(
        sizePx: Int,
        outer: Ring?,
        middle: Ring?,
        night: Boolean,
        dead: Boolean,
        scheme: ColorScheme = ColorScheme.DEFAULT,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = Palette(night)
        val center = size / 2f
        val stroke = size * 0.13f
        val gap = stroke * 0.5f
        val outerRadius = center - stroke / 2f - size * 0.03f
        val middleRadius = outerRadius - stroke - gap

        drawRing(canvas, center, center, outerRadius, stroke, palette, outer, dead, 255, scheme)
        drawRing(canvas, center, center, middleRadius, stroke, palette, middle, dead, 255, scheme)
        return bitmap
    }

    /** 1日未満は "H:MM"、それ以上は "N日"。 */
    private fun formatCountdown(seconds: Long): String {
        if (seconds <= 0) return "まもなく"
        if (seconds >= 86_400) return "${seconds / 86_400}日"
        return "${seconds / 3600}:${"%02d".format((seconds % 3600) / 60)}"
    }
}
