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
 * リングは2周（D7）。外側が5時間枠、中央が週次枠。
 * 中心は常に5時間枠の残量とリセットまでの残り時間（D8）。
 */
object DonutRenderer {

    private class Palette(night: Boolean) {
        val background = if (night) Color.parseColor("#1C1B1F") else Color.WHITE
        val text = if (night) Color.parseColor("#E6E1E5") else Color.parseColor("#1C1B1F")
        val subText = if (night) Color.parseColor("#A8A2AB") else Color.parseColor("#5F5F63")
        val track = if (night) Color.parseColor("#3A383C") else Color.parseColor("#E4E1E6")
        val green = if (night) Color.parseColor("#66BB6A") else Color.parseColor("#2E7D32")
        val yellow = if (night) Color.parseColor("#FFCA28") else Color.parseColor("#EF6C00")
        val red = if (night) Color.parseColor("#EF5350") else Color.parseColor("#C62828")
        val gray = if (night) Color.parseColor("#6E6A70") else Color.parseColor("#9E9A9F")
    }

    /** 配色のしきい値は agent 側と同じ（docs/design.md）。 */
    private fun colorFor(palette: Palette, remaining: Double): Int = when {
        remaining > 0.50 -> palette.green
        remaining >= 0.20 -> palette.yellow
        else -> palette.red
    }

    fun render(
        widthPx: Int,
        heightPx: Int,
        result: HubClient.Result,
        nowEpoch: Long,
        night: Boolean,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = Palette(night)

        val radius = minOf(width, height) * 0.10f
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.background },
        )

        when (result) {
            is HubClient.Result.NotConfigured ->
                drawMessage(canvas, width, height, palette, "タップして hub を設定")
            is HubClient.Result.Failed ->
                drawMessage(canvas, width, height, palette, result.reason)
            is HubClient.Result.Ok ->
                drawServices(canvas, width, height, palette, result.status, nowEpoch)
        }
        return bitmap
    }

    private fun drawMessage(
        canvas: Canvas, width: Int, height: Int, palette: Palette, message: String,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subText
            textAlign = Paint.Align.CENTER
            textSize = minOf(width, height) * 0.11f
        }
        canvas.drawText(message, width / 2f, height / 2f + paint.textSize / 3f, paint)
    }

    private fun drawServices(
        canvas: Canvas, width: Int, height: Int, palette: Palette, status: Status, nowEpoch: Long,
    ) {
        val ids = listOf("claude_code", "codex")
        val columnWidth = width / ids.size.toFloat()
        ids.forEachIndexed { index, id ->
            drawService(
                canvas = canvas,
                left = columnWidth * index,
                width = columnWidth,
                height = height.toFloat(),
                palette = palette,
                service = status.service(id),
                fallbackLabel = if (id == "codex") "Codex" else "Claude Code",
                nowEpoch = nowEpoch,
            )
        }
    }

    private fun drawService(
        canvas: Canvas,
        left: Float,
        width: Float,
        height: Float,
        palette: Palette,
        service: Service?,
        fallbackLabel: String,
        nowEpoch: Long,
    ) {
        val padding = width * 0.10f
        val labelSize = width * 0.11f
        val available = width - padding * 2
        val diameter = minOf(available, height - padding * 2 - labelSize * 1.6f)
        if (diameter <= 0f) return

        val centerX = left + width / 2f
        val centerY = padding + diameter / 2f
        val stroke = diameter * 0.095f
        val gap = stroke * 0.55f

        val freshness = Freshness.of(service?.updatedAtEpoch, nowEpoch)
        // 取得できていない、または1時間以上古ければグレー（D1）
        val dead = service == null || !service.available || freshness == Freshness.EXPIRED
        // 10分〜1時間は薄く見せる
        val alpha = if (freshness == Freshness.STALE && !dead) 150 else 255

        val outerRadius = diameter / 2f - stroke / 2f
        val middleRadius = outerRadius - stroke - gap

        val outer = service?.ring("outer")
        val middle = service?.ring("middle")

        drawRing(canvas, centerX, centerY, outerRadius, stroke, palette, outer, dead, alpha)
        drawRing(canvas, centerX, centerY, middleRadius, stroke, palette, middle, dead, alpha)

        // 中心は常に5時間枠（D8）
        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dead) palette.gray else palette.text
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = diameter * 0.24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dead) palette.gray else palette.subText
            this.alpha = alpha
            textAlign = Paint.Align.CENTER
            textSize = diameter * 0.125f
        }

        val bigText = if (dead || outer == null) "—" else "${Math.round(outer.remaining * 100)}%"
        canvas.drawText(bigText, centerX, centerY + bigPaint.textSize * 0.20f, bigPaint)

        val subText = when {
            service == null || !service.available -> "取得不可"
            freshness == Freshness.EXPIRED -> "更新できません"
            outer?.resetsAtEpoch != null -> formatCountdown(outer.resetsAtEpoch - nowEpoch)
            else -> ""
        }
        if (subText.isNotEmpty()) {
            canvas.drawText(subText, centerX, centerY + bigPaint.textSize * 0.95f, smallPaint)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.subText
            textAlign = Paint.Align.CENTER
            textSize = labelSize
        }
        canvas.drawText(
            service?.label ?: fallbackLabel,
            centerX,
            padding + diameter + labelSize * 1.1f,
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
    ) {
        if (radius <= 0f) return
        val bounds = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = palette.track
        }
        canvas.drawCircle(centerX, centerY, radius, trackPaint)

        if (dead || ring == null) return

        // 残量を12時方向から時計回りに描く。残量が減ると弧が短くなる。
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = colorFor(palette, ring.remaining)
            this.alpha = alpha
        }
        val sweep = (ring.remaining * 360.0).toFloat()
        if (sweep > 0.5f) {
            canvas.drawArc(bounds, -90f, sweep, false, arcPaint)
        }
    }

    /** 1日未満は "H:MM"、それ以上は "Nd"。 */
    private fun formatCountdown(seconds: Long): String {
        if (seconds <= 0) return ""
        if (seconds >= 86_400) return "${seconds / 86_400}d"
        return "${seconds / 3600}:${"%02d".format((seconds % 3600) / 60)}"
    }
}
