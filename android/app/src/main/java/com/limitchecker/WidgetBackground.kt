package com.limitchecker

/**
 * ウィジェットの背景の濃さ。
 *
 * 透過させると壁紙に馴染むが、壁紙の色によっては文字が読みにくくなる。
 * そのため透過時は文字と数字に影を落として、明るい壁紙でも暗い壁紙でも
 * 輪郭が残るようにする（[DonutRenderer]）。
 */
enum class WidgetBackground(val key: String, val alpha: Int, val labelRes: Int) {
    OPAQUE("opaque", 255, R.string.bg_opaque),
    TRANSLUCENT("translucent", 140, R.string.bg_translucent),
    TRANSPARENT("transparent", 0, R.string.bg_transparent);

    companion object {
        val DEFAULT = OPAQUE

        fun fromKey(key: String?): WidgetBackground =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
