package com.limitchecker

import android.graphics.Color

/**
 * リングの配色。
 *
 * 既定は色相で**どのリングか**を示し、残量の多寡は弧の長さで表す（D12）。
 * 値は Okabe-Ito の色覚バリアフリー配色から採っており、
 * P型・D型・T型のいずれでも2つのリングを区別できる。
 *
 * [MONO] は色相を使わず濃淡だけで分ける。色が一切判別できなくても読める。
 * [TRAFFIC] は残量そのものを色で表す従来型。分かりやすい反面、
 * 緑と赤の判別に頼るため、色覚特性によっては区別できない。
 * 選べるようにはするが既定にはしない。
 */
enum class ColorScheme(val key: String, val labelRes: Int) {

    /** 青とオレンジ。もっとも判別しやすい組み合わせ。 */
    BLUE_ORANGE("blue_orange", R.string.scheme_blue_orange),

    /** 濃淡のみ。色を使わない。 */
    MONO("mono", R.string.scheme_mono),

    /** 残量を色で表す従来型。緑・黄・朱。 */
    TRAFFIC("traffic", R.string.scheme_traffic);

    /** リングの色。逼迫時は [criticalColor] に切り替わる。 */
    fun ringColor(slot: String, remaining: Double, night: Boolean): Int {
        if (this == TRAFFIC) {
            return when {
                remaining > 0.50 -> if (night) GREEN_DARK else GREEN_LIGHT
                remaining >= 0.20 -> if (night) AMBER_DARK else AMBER_LIGHT
                else -> criticalColor(night)
            }
        }
        if (remaining < CRITICAL) return criticalColor(night)

        val isOuter = slot == "outer"
        return when (this) {
            BLUE_ORANGE ->
                if (isOuter) blue(night) else if (night) ORANGE_DARK else ORANGE_LIGHT
            MONO ->
                if (isOuter) monoStrong(night) else monoWeak(night)
            TRAFFIC -> blue(night) // 到達しない
        }
    }

    /** 逼迫時の色。通知のアクセント色にも使う。 */
    fun criticalColor(night: Boolean): Int = when (this) {
        MONO -> monoStrong(night)
        else -> if (night) VERMILLION_DARK else VERMILLION_LIGHT
    }

    /** 設定画面に出す見本。左が外側、右が中央のリングの色。 */
    fun sampleColors(night: Boolean): Pair<Int, Int> = Pair(
        ringColor("outer", 1.0, night),
        ringColor("middle", 1.0, night),
    )

    private fun blue(night: Boolean) = if (night) BLUE_DARK else BLUE_LIGHT
    private fun monoStrong(night: Boolean) =
        if (night) Color.parseColor("#E6E1E5") else Color.parseColor("#26242A")
    private fun monoWeak(night: Boolean) =
        if (night) Color.parseColor("#8E8A92") else Color.parseColor("#8A8690")

    companion object {
        val DEFAULT = BLUE_ORANGE

        /** これを下回ったら逼迫として扱う。 */
        const val CRITICAL = 0.20

        fun fromKey(key: String?): ColorScheme =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        // Okabe-Ito の配色。暗い背景では同じ色相を明るくする。
        private val BLUE_LIGHT = Color.parseColor("#0072B2")
        private val BLUE_DARK = Color.parseColor("#56B4E9")
        private val ORANGE_LIGHT = Color.parseColor("#D68A00")
        private val ORANGE_DARK = Color.parseColor("#E69F00")
        private val GREEN_LIGHT = Color.parseColor("#008765")
        private val GREEN_DARK = Color.parseColor("#3FC79A")
        private val AMBER_LIGHT = Color.parseColor("#B8860B")
        private val AMBER_DARK = Color.parseColor("#F0C000")
        private val VERMILLION_LIGHT = Color.parseColor("#D55E00")
        private val VERMILLION_DARK = Color.parseColor("#FF7043")
    }
}
