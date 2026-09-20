package com.limitchecker

/**
 * ステータスバーと通知に、どのサービスを出すか。
 *
 * ウィジェットは幅があるので両方並べられるが、ステータスバーは小アイコン1つ、
 * 通知の折りたたみ時も1行しかない。そこで出すものを選ばせる。
 *
 * [BOTH] を選んだ場合、ステータスバーは円を左右に分けて
 * 左が Claude Code の5時間枠、右が Codex の5時間枠になる。
 * 1サービスを選んだ場合は、左右が同じサービスの5時間枠と週次枠になる。
 */
enum class ServiceChoice(val key: String, val labelRes: Int) {
    CLAUDE("claude", R.string.service_claude),
    CODEX("codex", R.string.service_codex),
    BOTH("both", R.string.service_both);

    /** hub が返すサービス ID。BOTH のときは主役を Claude Code とする。 */
    val primaryServiceId: String
        get() = if (this == CODEX) "codex" else "claude_code"

    companion object {
        val DEFAULT = CLAUDE

        fun fromKey(key: String?): ServiceChoice =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
