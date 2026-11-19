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
) {
    companion object {
        const val TAG_OTHER = "その他"

        /** 決まったタグ。自分で足したタグ（科目名など）は TagStore（#10） */
        val TAGS = listOf("授業", "研究室", "グループ", "バイト", TAG_OTHER)
    }
}
