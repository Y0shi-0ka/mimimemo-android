package jp.mimimemo

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * 「明日」「来週月曜」「15日まで」などの日付表現を、基準日から見た具体的な日付に変換する。
 *
 * 上にある規則ほど優先する。曜日だけ（「金曜まで」）は基準日より後の、いちばん近いその曜日とする。
 * 週は月曜はじまりとして数える。
 */
class DateParser {

    /** 見つかった日付と、文中で日付表現が占める範囲 */
    data class Match(val date: LocalDate, val range: IntRange)

    fun parse(text: String, base: LocalDate): LocalDate? = find(text, base)?.date

    fun find(text: String, base: LocalDate): Match? {
        val s = normalize(text)
        for (rule in rules) {
            for (m in rule.regex.findAll(s)) {
                val date = rule.toDate(m, base) ?: continue
                return Match(date, m.range)
            }
        }
        return null
    }

    private class Rule(val regex: Regex, val toDate: (MatchResult, LocalDate) -> LocalDate?)

    private val rules = listOf(
        // 11月20日
        Rule(Regex("(\\d{1,2})月(\\d{1,2})日?")) { m, base -> monthDay(m.int(1), m.int(2), base) },
        // 11/20
        Rule(Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])")) { m, base -> monthDay(m.int(1), m.int(2), base) },
        // 来週月曜 / 来週の金曜日 / 今週水曜 / 再来週の火曜
        Rule(Regex("(再来週|来週|今週)の?([月火水木金土日])曜日?")) { m, base ->
            weekStart(m.groupValues[1], base).plusDays(dayOfWeek(m.groupValues[2]).value - 1L)
        },
        // 来月10日 / 今月の25日
        Rule(Regex("(来月|今月)の?(\\d{1,2})日")) { m, base ->
            dayOfMonth(YearMonth.from(base).plusMonths(if (m.groupValues[1] == "来月") 1 else 0), m.int(2))
        },
        // 月末 / 来月末
        Rule(Regex("来月末|今月末|月末")) { m, base ->
            YearMonth.from(base).plusMonths(if (m.value == "来月末") 1 else 0).atEndOfMonth()
        },
        // 週末 → その週の土曜（日曜なら当日）
        Rule(Regex("今?週末")) { _, base ->
            if (base.dayOfWeek == DayOfWeek.SUNDAY) base
            else base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        },
        // 曜日なしの「来週」「再来週」→ その週の月曜
        Rule(Regex("再来週|来週")) { m, base -> weekStart(m.value, base) },
        Rule(Regex("明々後日|明明後日|しあさって")) { _, base -> base.plusDays(3) },
        Rule(Regex("明後日|あさって")) { _, base -> base.plusDays(2) },
        Rule(Regex("明日|あした")) { _, base -> base.plusDays(1) },
        Rule(Regex("今日中|今日|本日|今夜|今晩")) { _, base -> base },
        Rule(Regex("(\\d{1,3})日後")) { m, base -> base.plusDays(m.int(1).toLong()) },
        Rule(Regex("(\\d{1,2})週間後")) { m, base -> base.plusWeeks(m.int(1).toLong()) },
        Rule(Regex("(\\d{1,2})[かヶケカ]月後")) { m, base -> base.plusMonths(m.int(1).toLong()) },
        // 金曜まで / 月曜日に
        Rule(Regex("([月火水木金土日])曜日?")) { m, base ->
            base.with(TemporalAdjusters.next(dayOfWeek(m.groupValues[1])))
        },
        // 15日まで → 今月15日（もう過ぎていれば来月15日）
        Rule(Regex("(?<![\\d月])(\\d{1,2})日(?![後間中])")) { m, base ->
            val day = m.int(1)
            if (day !in 1..31) null
            else if (day >= base.dayOfMonth) dayOfMonth(YearMonth.from(base), day)
            else dayOfMonth(YearMonth.from(base).plusMonths(1), day)
        },
    )

    private fun MatchResult.int(group: Int): Int = groupValues[group].toInt()

    /** 月日を、基準日以降でいちばん近い日付にする（過ぎていれば来年）。 */
    private fun monthDay(month: Int, day: Int, base: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val thisYear = try {
            LocalDate.of(base.year, month, day)
        } catch (e: DateTimeException) {
            return null
        }
        return if (thisYear.isBefore(base)) thisYear.plusYears(1) else thisYear
    }

    /** 月の日数を超える日（例：2月30日）は月末にそろえる。 */
    private fun dayOfMonth(ym: YearMonth, day: Int): LocalDate? {
        if (day !in 1..31) return null
        return ym.atDay(minOf(day, ym.lengthOfMonth()))
    }

    private fun weekStart(word: String, base: LocalDate): LocalDate {
        val monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (word) {
            "来週" -> monday.plusWeeks(1)
            "再来週" -> monday.plusWeeks(2)
            else -> monday
        }
    }

    private fun dayOfWeek(ch: String): DayOfWeek = when (ch) {
        "月" -> DayOfWeek.MONDAY
        "火" -> DayOfWeek.TUESDAY
        "水" -> DayOfWeek.WEDNESDAY
        "木" -> DayOfWeek.THURSDAY
        "金" -> DayOfWeek.FRIDAY
        "土" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    /** 全角数字・全角スラッシュを半角にする（文字数は変えないので範囲はそのまま使える）。 */
    private fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '／' -> '/'
                    else -> c
                }
            )
        }
    }
}
