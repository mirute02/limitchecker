package com.limitchecker

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * hub の GET /status を表す。
 *
 * 描画に必要なものだけを持つ。hub が将来フィールドを増やしても、
 * ここで拾わない限りアプリには入ってこない。
 */
data class Ring(
    val slot: String,
    val label: String,
    val remaining: Double,
    val resetsAtEpoch: Long?,
)

data class Service(
    val id: String,
    val label: String,
    val available: Boolean,
    /** 一度でも報告があったか。false なら未設定なので表示しない。 */
    val configured: Boolean,
    val updatedAtEpoch: Long?,
    val rings: List<Ring>,
) {
    fun ring(slot: String): Ring? = rings.firstOrNull { it.slot == slot }

    /** 最も逼迫しているリングの残量。色の判定に使う。 */
    fun worstRemaining(): Double? = rings.minOfOrNull { it.remaining }
}

data class Status(
    val services: List<Service>,
) {
    fun service(id: String): Service? = services.firstOrNull { it.id == id }

    companion object {
        /** RFC3339 を epoch 秒に。解釈できなければ null（鮮度不明として扱う）。 */
        private fun parseTime(value: String?): Long? {
            if (value.isNullOrEmpty()) return null
            return try {
                OffsetDateTime.parse(value).toEpochSecond()
            } catch (e: DateTimeParseException) {
                null
            }
        }

        fun parse(json: String): Status {
            val root = JSONObject(json)
            val services = mutableListOf<Service>()
            val array = root.optJSONArray("services") ?: return Status(emptyList())

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val rings = mutableListOf<Ring>()
                val ringArray = obj.optJSONArray("rings")
                if (ringArray != null) {
                    for (j in 0 until ringArray.length()) {
                        val r = ringArray.optJSONObject(j) ?: continue
                        val slot = r.optString("slot")
                        if (slot.isEmpty()) continue
                        rings.add(
                            Ring(
                                slot = slot,
                                label = r.optString("label", slot),
                                remaining = r.optDouble("remaining", 0.0).coerceIn(0.0, 1.0),
                                resetsAtEpoch = parseTime(r.optString("resets_at", null)),
                            )
                        )
                    }
                }
                services.add(
                    Service(
                        id = obj.optString("id"),
                        label = obj.optString("label", obj.optString("id")),
                        available = obj.optBoolean("available", false),
                        configured = obj.optBoolean("configured", false),
                        updatedAtEpoch = parseTime(obj.optString("updated_at", null)),
                        rings = rings,
                    )
                )
            }
            return Status(services)
        }
    }
}

/**
 * 鮮度。statusLine は Claude Code の実行中しか更新されないため、
 * 古い値をそのまま新しい値のように見せない（docs/decisions.md D1）。
 */
enum class Freshness {
    FRESH,   // 10分以内
    STALE,   // 10分〜1時間。薄く表示する
    EXPIRED; // 1時間超。グレーにして「更新できません」と出す

    companion object {
        private const val STALE_SEC = 10 * 60L
        private const val EXPIRED_SEC = 60 * 60L

        fun of(updatedAtEpoch: Long?, nowEpoch: Long): Freshness {
            if (updatedAtEpoch == null) return EXPIRED
            val age = nowEpoch - updatedAtEpoch
            return when {
                age <= STALE_SEC -> FRESH
                age <= EXPIRED_SEC -> STALE
                else -> EXPIRED
            }
        }
    }
}
