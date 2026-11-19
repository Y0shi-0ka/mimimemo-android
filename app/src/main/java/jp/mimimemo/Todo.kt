package jp.mimimemo

import java.time.LocalDate
import java.time.LocalDateTime

/** ToDo 1件分のデータ（todos テーブルの1行に対応）。 */
data class Todo(
    val id: Long = 0,
    val title: String,
    val due: LocalDate? = null,
    val tag: String = TAG_OTHER,
    /** 抽出元の発言。手で追加したものは null */
    val sourceText: String? = null,
    val done: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    /** 繰り返し（#107）。null なら繰り返さない */
    val repeat: Repeat? = null,
) {
    companion object {
        const val TAG_OTHER = "その他"

        /** 決まったタグ。自分で足したタグ（科目名など）は TagStore（#10） */
        val TAGS = listOf("授業", "研究室", "グループ", "バイト", TAG_OTHER)
    }
}

/** 繰り返し（#107）。完了にすると期限を次の日付へ進める */
enum class Repeat(val key: String, val label: String) {
    DAILY("daily", "毎日"),
    WEEKLY("weekly", "毎週"),
    MONTHLY("monthly", "毎月");

    fun next(date: LocalDate): LocalDate = when (this) {
        DAILY -> date.plusDays(1)
        WEEKLY -> date.plusWeeks(1)
        MONTHLY -> date.plusMonths(1)
    }

    /** 今日より後の最初の日付（遅れて完了したときも、過ぎた日付にはしない） */
    fun nextAfter(due: LocalDate, today: LocalDate): LocalDate {
        var d = next(due)
        while (!d.isAfter(today)) d = next(d)
        return d
    }

    companion object {
        fun fromKey(key: String?): Repeat? = entries.firstOrNull { it.key == key }
    }
}
