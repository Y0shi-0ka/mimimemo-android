package jp.mimimemo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 一覧を期限で区切る（issue #49）。週は月曜はじまり（[DateParser] と同じ）。
 */
object DueSections {

    enum class Section(val label: String) {
        OVERDUE("期限切れ"),
        TODAY("今日"),
        TOMORROW("明日"),
        THIS_WEEK("今週"),
        LATER("来週以降"),
        NONE("期限なし"),
    }

    fun sectionOf(due: LocalDate?, today: LocalDate): Section = when {
        due == null -> Section.NONE
        due.isBefore(today) -> Section.OVERDUE
        due == today -> Section.TODAY
        due == today.plusDays(1) -> Section.TOMORROW
        !due.isAfter(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))) -> Section.THIS_WEEK
        else -> Section.LATER
    }

    /** 区切りごとにまとめる。中身のない区切りは含めない。並びは区切りの順、その中は元の順 */
    fun group(todos: List<Todo>, today: LocalDate): List<Pair<Section, List<Todo>>> =
        todos.groupBy { sectionOf(it.due, today) }.toSortedMap().map { (section, items) -> section to items }
}
