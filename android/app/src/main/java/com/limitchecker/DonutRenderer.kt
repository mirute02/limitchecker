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

    /** 名前を出すならこの高さは要る。これ未満なら出さず、配置で埋める。 */
    private const val MIN_LABEL_DP = 9f

    /** これ以上の大きさなら、ドーナツをやめて横棒にする。 */
    private const val BARS_MIN_WIDTH_DP = 220
    private const val BARS_MIN_HEIGHT_DP = 170

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
        absoluteTime: Boolean = false,
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
                    widthDp, heightDp, shadow, scheme, absoluteTime,
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
        absoluteTime: Boolean,
    ) {
        // 入る形に合わせて並べ方を決める（D25）。
        //   横に広い   : 横並び2つ
        //   縦に長い   : 縦積み2つ。どちらも同じ大きさになる
        //   それ以外   : 1つだけ
        // 一度も報告がないサービスは出さない。常にグレーのリングが並んでいると
        // 故障しているように見える（D28）。
        val known = listOf("claude_code", "codex").filter { status.service(it)?.configured == true }
        val available = known.ifEmpty { listOf("claude_code") }

        // 十分な面積があるなら、ドーナツより横棒のほうが情報量が多く読みやすい（D30）
        if (widthDp >= BARS_MIN_WIDTH_DP && heightDp >= BARS_MIN_HEIGHT_DP) {
            drawBars(canvas, width, height, palette, status, available, nowEpoch,
                shadow, scheme, absoluteTime)
            return
        }

        val horizontal = available.size >= 2 && widthDp >= TWO_COLUMN_MIN_DP
        val vertical = available.size >= 2 && !horizontal && heightDp >= widthDp * 1.6f
        val count = if (horizontal || vertical) 2 else 1

        val ids = available.take(count)
        val cellWidthDp = if (horizontal) widthDp / 2 else widthDp
        val compact = cellWidthDp < COMPACT_MAX_DP || (vertical && heightDp / 2 < COMPACT_MAX_DP)

        if (vertical) {
            // 縦積みではドーナツの大きさが幅で決まるため、縦が余る。
            // 余りの扱いは2通り（D35）:
            //   読める大きさの名前が入るなら → 名前に充てる。情報が増える
            //   入らないなら              → 上下と中間に均等に配す。
            //                              片寄った空きは事故に見えるが、
            //                              等間隔なら意図した配置に見える
            val density = if (widthDp > 0) width.toFloat() / widthDp else 1f
            val minLabelPx = MIN_LABEL_DP * density

            val margin = minOf(width, height) * 0.04f
            val byWidth = width - margin * 2
            val cellHeight = (height - margin * 2) / 2f
            val baseDiameter = minOf(byWidth, cellHeight)

            val leftover = cellHeight - baseDiameter
            val labelSize = (leftover * 0.55f).coerceAtMost(baseDiameter * 0.22f)
            val useLabel = labelSize >= minLabelPx

            if (useLabel) {
                val diameter = minOf(byWidth, cellHeight - labelSize * 1.5f)
                val content = diameter + labelSize * 1.5f
                val gap = ((height - content * 2) / 3f).coerceAtLeast(0f)
                ids.forEachIndexed { index, id ->
                    drawService(
                        canvas = canvas,
                        left = 0f,
                        top = gap + (content + gap) * index,
                        width = width.toFloat(),
                        height = content,
                        palette = palette,
                        service = status.service(id),
                        fallbackLabel = if (id == "codex") "Codex" else "Claude",
                        nowEpoch = nowEpoch,
                        compact = true,
                        shadow = shadow,
                        scheme = scheme,
                        absoluteTime = absoluteTime,
                        fixedDiameter = diameter,
                        fixedLabelSize = labelSize,
                    )
                }
            } else {
                // 上・間・下を同じ間隔にする
                val gap = ((height - baseDiameter * 2) / 3f).coerceAtLeast(0f)
                ids.forEachIndexed { index, id ->
                    drawService(
                        canvas = canvas,
                        left = 0f,
                        top = gap + (baseDiameter + gap) * index,
                        width = width.toFloat(),
                        height = baseDiameter,
                        palette = palette,
                        service = status.service(id),
                        fallbackLabel = if (id == "codex") "Codex" else "Claude",
                        nowEpoch = nowEpoch,
                        compact = true,
                        shadow = shadow,
                        scheme = scheme,
                        absoluteTime = absoluteTime,
                        fixedDiameter = baseDiameter,
                    )
                }
            }
            return
        }

        val cellWidth = if (horizontal) width / 2f else width.toFloat()
        ids.forEachIndexed { index, id ->
            drawService(
                canvas = canvas,
                left = cellWidth * index,
                top = 0f,
                width = cellWidth,
                height = height.toFloat(),
                palette = palette,
                service = status.service(id),
                fallbackLabel = if (id == "codex") "Codex" else "Claude Code",
                nowEpoch = nowEpoch,
                compact = compact,
                shadow = shadow,
                scheme = scheme,
                absoluteTime = absoluteTime,
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
        absoluteTime: Boolean = false,
        fixedDiameter: Float? = null,
        fixedLabelSize: Float? = null,
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
        var labelSize = fixedLabelSize ?: if (compact) 0f else (shortSide * 0.12f)
        var diameter = fixedDiameter ?: minOf(innerWidth, innerHeight - labelSize * 1.6f)

        // 呼び出し元が大きさを決めている場合は、その判断に従う。
        // そうでないときだけ、ラベルでグラフが小さくなりすぎないか見る（D28）。
        if (fixedDiameter == null && labelSize > 0f && diameter < fullDiameter * 0.78f) {
            labelSize = 0f
            diameter = fullDiameter
        }
        if (diameter <= 0f) return

        // 縦に余裕があるときは、中身を縦中央に寄せて空白を作らない。
        val contentHeight = diameter + labelSize * 1.6f
        // 呼び出し元が高さを決めている場合は、その枠にぴったり収める。
        val innerTop = if (fixedDiameter != null) {
            top + ((height - contentHeight) / 2f).coerceAtLeast(0f)
        } else {
            top + ((height - contentHeight) / 2f).coerceAtLeast(padding)
        }

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
            outer?.resetsAtEpoch != null ->
                if (absoluteTime) formatClock(outer.resetsAtEpoch)
                else formatCountdown(outer.resetsAtEpoch - nowEpoch)
            else -> "—"
        }
        canvas.drawText(centerText, centerX, centerY + centerPaint.textSize * 0.35f, centerPaint)

        // 補足（「後に回復」）は省略形では出さない。入れても読めない。
        // 名前は labelSize があれば出す（縦積みで余りを名前に充てる場合）。
        if (compact && labelSize <= 0f) return

        if (!compact && !dead && centerText != "—") {
            canvas.drawText(
                if (absoluteTime) "に回復" else "後に回復",
                centerX, centerY + centerPaint.textSize * 1.25f, captionPaint,
            )
        }

        if (labelSize <= 0f) return
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
    /**
     * 両サービスの5時間枠と週次枠、4つの値を1つのアイコンに収める。
     *
     * 24dp では角度より**高さ**のほうが正確に読める。電波強度計と同じ要領で、
     * 4本の縦棒にする。2本ずつ間隔を空けてサービスを分ける。
     *
     *   左の2本 = Claude Code（5時間枠・週次枠）
     *   右の2本 = Codex（5時間枠・週次枠）
     *
     * OS が単色に塗ってもアルファは残るので、薄い枠の上に濃い棒を重ねて
     * 「満タンに対してどれだけ」が読める（D34）。
     */
    fun renderStatusBarBars(sizePx: Int, values: List<Double?>): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val margin = size * 0.08f
        val groupGap = size * 0.10f
        val barGap = size * 0.045f
        val usable = size - margin * 2
        val barWidth = (usable - groupGap - barGap * 2) / 4f
        val maxHeight = size - margin * 2
        val radius = barWidth * 0.3f

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 70
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        var x = margin
        values.forEachIndexed { index, value ->
            // 枠は常に描く。描かないと「空」と「未取得」の区別がつかない。
            canvas.drawRoundRect(
                RectF(x, margin, x + barWidth, margin + maxHeight),
                radius, radius, track,
            )
            if (value != null) {
                val h = (maxHeight * value).toFloat().coerceAtLeast(barWidth * 0.5f)
                canvas.drawRoundRect(
                    RectF(x, margin + maxHeight - h, x + barWidth, margin + maxHeight),
                    radius, radius, fill,
                )
            }
            x += barWidth
            // 2本目の後だけ広く空けて、サービスの区切りを示す
            x += if (index == 1) groupGap else barGap
        }
        return bitmap
    }

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
        val unavailable = outer == null
        if (unavailable) {
            drawIconText(canvas, size, "?")
            return bitmap
        }

        when (mode) {
            StatusIconMode.RINGS_BOTH -> {
                // 同心円を2本にすると 24dp では潰れて1本に見える。
                // 円を左右に分け、それぞれ半周で表す。半周ぶんの太さを使えるので
                // 小さくても両方読める。左が5時間枠、右が週次枠。
                drawSplitRing(canvas, size, outer!!.remaining, middle?.remaining)
            }
            StatusIconMode.RING_5H -> {
                // 1本だけなら太く描ける。形が読み取りやすくなる。
                val ring = outer!!
                val stroke = size * 0.26f
                val radius = center - stroke / 2f - size * 0.05f
                drawIconRing(canvas, center, radius, stroke, ring.remaining)
            }
            StatusIconMode.PCT_5H -> {
                val percent = Math.round(outer!!.remaining * 100).coerceIn(0, 100)
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
     * 左半分と右半分に分けて2つの値を表す。左が5時間枠、右が週次枠。
     *
     * **どちらも下端（6時）から上へ伸びる。** つまり残量は下に溜まり、
     * 使うほど上から減っていく。水位が下がるのと同じ読み方で、
     * 電池やタンクと同じ感覚で読める。
     *
     * 上下の端に隙間を空けて、左右が別物だと分かるようにする。
     */
    private fun drawSplitRing(canvas: Canvas, size: Int, left: Double, right: Double?) {
        val center = size / 2f
        val stroke = size * 0.22f
        val radius = center - stroke / 2f - size * 0.04f
        val bounds = RectF(center - radius, center - radius, center + radius, center + radius)
        // 上下に空ける隙間（度）。左右の境目をはっきりさせる。
        val gap = 12f
        val span = 180f - gap
        // Android の角度は3時が 0 度で時計回り。6時は 90 度。
        val bottom = 90f

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

        // 左半分: 6時から 9時を通って 12時へ
        canvas.drawArc(bounds, bottom + gap / 2f, span, false, track)
        val leftSweep = (span * left).toFloat()
        if (leftSweep > 1f) canvas.drawArc(bounds, bottom + gap / 2f, leftSweep, false, arc)

        // 右半分: 6時から 3時を通って 12時へ
        canvas.drawArc(bounds, bottom - gap / 2f, -span, false, track)
        if (right != null) {
            val rightSweep = (span * right).toFloat()
            if (rightSweep > 1f) {
                canvas.drawArc(bounds, bottom - gap / 2f, -rightSweep, false, arc)
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


    // ------------------------------------------------------------------
    // 大きいウィジェット向けの横棒
    // ------------------------------------------------------------------

    /**
     * 面積があるときは、ドーナツより横棒のほうが読みやすい（D30）。
     *
     * ドーナツは「だいたいどれくらい」を速く伝えるのに向くが、面積が余る。
     * 横棒なら、サービス名・枠の種類・残量・回復までの時間を並べて置ける。
     */
    private fun drawBars(
        canvas: Canvas,
        width: Int,
        height: Int,
        palette: Palette,
        status: Status,
        ids: List<String>,
        nowEpoch: Long,
        shadow: Boolean,
        scheme: ColorScheme,
        absoluteTime: Boolean,
    ) {
        val pad = minOf(width, height) * 0.055f
        val innerWidth = width - pad * 2

        // 1サービスあたり: 見出し1行 + リング2本。
        // サービスの区切りは、見出しの**前**に空ける。後ろに空けると
        // 見出しが下のグループから切り離されて見える（D44）。
        val rowsPerService = 3
        val gapUnits = 0.5f
        val totalUnits = ids.size * rowsPerService + (ids.size - 1) * gapUnits
        val unit = (height - pad * 2) / totalUnits

        val titleSize = unit * 0.60f
        val labelSize = unit * 0.44f
        val barHeight = unit * 0.34f

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
            textSize = labelSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            applyShadow(shadow, palette.isNight, textSize)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subText
            textSize = labelSize
            applyShadow(shadow, palette.isNight, textSize)
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.text
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            applyShadow(shadow, palette.isNight, textSize)
        }

        // 一番長い値の幅を測って右側を確保する。桁が動いても行が崩れない。
        val valueWidth = valuePaint.measureText("100%  00/00 00:00")
        val labelWidth = labelPaint.measureText("5時間枠") * 1.18f
        val barWidth = (innerWidth - labelWidth - valueWidth - pad * 0.5f)
            .coerceAtLeast(innerWidth * 0.2f)

        var y = pad

        ids.forEachIndexed { index, id ->
            if (index > 0) y += unit * gapUnits

            val service = status.service(id)
            val fresh = Freshness.of(service?.updatedAtEpoch, nowEpoch)
            val dead = service == null || !service.available || fresh == Freshness.EXPIRED
            val alpha = if (fresh == Freshness.STALE && !dead) 150 else 255

            // 1行ぶんの帯の中で、縦中央に文字を置く
            canvas.drawText(service?.label ?: id, pad, y + unit * 0.5f + titleSize * 0.36f, titlePaint)
            y += unit

            listOf("outer" to "5時間枠", "middle" to "週次枠").forEach { (slot, name) ->
                val ring = service?.ring(slot)
                val center = y + unit * 0.5f

                canvas.drawText(name, pad, center + labelSize * 0.36f, labelPaint)

                val barLeft = pad + labelWidth
                val radius = barHeight / 2f
                canvas.drawRoundRect(
                    RectF(barLeft, center - barHeight * 0.5f, barLeft + barWidth, center + barHeight * 0.5f),
                    radius, radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.track },
                )

                if (!dead && ring != null) {
                    val filled = (barWidth * ring.remaining).toFloat().coerceAtLeast(barHeight)
                    canvas.drawRoundRect(
                        RectF(barLeft, center - barHeight * 0.5f, barLeft + filled, center + barHeight * 0.5f),
                        radius, radius,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = scheme.ringColor(slot, ring.remaining, palette.isNight)
                            this.alpha = alpha
                        },
                    )
                }

                val text = when {
                    dead || ring == null -> "—"
                    ring.resetsAtEpoch != null -> {
                        val when_ = if (absoluteTime) formatClockWithDay(ring.resetsAtEpoch, nowEpoch)
                        else formatCountdown(ring.resetsAtEpoch - nowEpoch)
                        "${Math.round(ring.remaining * 100)}%  $when_"
                    }
                    else -> "${Math.round(ring.remaining * 100)}%"
                }
                valuePaint.color = if (dead) palette.gray else palette.text
                valuePaint.alpha = alpha
                canvas.drawText(text, (width - pad), center + labelSize * 0.36f, valuePaint)

                y += unit
            }
        }
    }

    /** 何時に回復するかを24時間表記で。 */
    private fun formatClock(epochSeconds: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = epochSeconds * 1000
        return "%02d:%02d".format(
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
        )
    }

    /** 当日なら時刻だけ、別の日なら日付を添える。棒グラフは週次枠も出すため。 */
    private fun formatClockWithDay(epochSeconds: Long, nowEpoch: Long): String {
        val target = java.util.Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowEpoch * 1000 }
        val sameDay = target.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            target.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        val clock = "%02d:%02d".format(
            target.get(java.util.Calendar.HOUR_OF_DAY), target.get(java.util.Calendar.MINUTE),
        )
        return if (sameDay) clock
        else "%d/%d %s".format(
            target.get(java.util.Calendar.MONTH) + 1,
            target.get(java.util.Calendar.DAY_OF_MONTH), clock,
        )
    }

    /** 1日未満は "H:MM"、それ以上は "N日"。 */
    private fun formatCountdown(seconds: Long): String {
        if (seconds <= 0) return "まもなく"
        if (seconds >= 86_400) return "${seconds / 86_400}日"
        return "${seconds / 3600}:${"%02d".format((seconds % 3600) / 60)}"
    }
}
