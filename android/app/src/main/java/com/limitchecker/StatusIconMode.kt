package com.limitchecker

/**
 * ステータスバー（時計や電池が並ぶ行）に何を出すか。
 *
 * 置けるのは小アイコン1つだけで、24dp 程度しかない。
 * どれを選んでも読める大きさを保てるよう、出す情報は1種類に絞る。
 * 詳しい値は通知を引き下ろせば見える。
 */
enum class StatusIconMode(val key: String, val labelRes: Int) {
    /** 2本のリング。外側=5時間枠、内側=週次枠。ウィジェットと同じ読み方。 */
    RINGS_BOTH("rings_both", R.string.mode_rings_both),

    /** 5時間枠だけを太いリングで。1本なので形が読み取りやすい。 */
    RING_5H("ring_5h", R.string.mode_ring_5h),

    /** 週次枠だけを太いリングで。 */
    RING_WEEK("ring_week", R.string.mode_ring_week),

    /** 5時間枠の残量を数字で。正確さ重視。 */
    PCT_5H("pct_5h", R.string.mode_pct_5h),

    /** 週次枠の残量を数字で。 */
    PCT_WEEK("pct_week", R.string.mode_pct_week),

    /** 5時間枠が回復するまでの時間。「あとどれだけ待てば使えるか」を見たいとき。 */
    RESET_5H("reset_5h", R.string.mode_reset_5h);

    companion object {
        val DEFAULT = RINGS_BOTH

        fun fromKey(key: String?): StatusIconMode =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
