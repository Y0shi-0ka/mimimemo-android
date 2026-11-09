package jp.mimimemo

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 期限の表示用の文字列 */
object DueFormatter {
    private val WEEKDAYS = arrayOf("月", "火", "水", "木", "金", "土", "日")

    fun weekday(date: LocalDate): String = WEEKDAYS[date.dayOfWeek.value - 1]

    /** 11/17(月) */
    fun short(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}(${weekday(date)})"

    /** 一覧に出す期限：「11/17(月)・あと3日」「期限なし」など */
    fun format(due: LocalDate?, today: LocalDate): String {
        if (due == null) return "期限なし"
        val days = ChronoUnit.DAYS.between(today, due)
        val relative = when {
            days < 0 -> "期限切れ"
            days == 0L -> "今日"
            days == 1L -> "明日"
            else -> "あと${days}日"
        }
        return "${short(due)}・$relative"
    }

    /** 読み上げ用：「11月17日」 */
    fun spoken(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
}

/** TextToSpeech で読み上げる文章をつくる */
object SpeechTexts {

    fun reminder(todos: List<Todo>): String = when (todos.size) {
        0 -> "明日が期限のToDoはありません。"
        1 -> "明日は、${todos[0].title}、の締め切りです。"
        else -> "明日が期限のToDoが${todos.size}件あります。" +
            todos.joinToString("。", postfix = "。") { it.title }
    }

    fun summary(todos: List<Todo>): String {
        if (todos.isEmpty()) return "今日はまだToDoを登録していません。"
        return buildString {
            append("今日は${todos.size}件のToDoを登録しました。")
            todos.forEachIndexed { i, t ->
                append("${i + 1}件目、${t.title}")
                t.due?.let { append("、期限は${DueFormatter.spoken(it)}") }
                append("。")
            }
        }
    }
}
