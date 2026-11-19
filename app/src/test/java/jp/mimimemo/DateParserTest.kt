package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DateParserTest {

    private val parser = DateParser()

    // 2026-11-09 は月曜日
    private val monday = LocalDate.of(2026, 11, 9)
    private val wednesday = LocalDate.of(2026, 11, 11)

    private fun parse(text: String, base: LocalDate = wednesday) = parser.parse(text, base)

    @Test
    fun relativeDays() {
        assertEquals(LocalDate.of(2026, 11, 11), parse("今日中に送って"))
        assertEquals(LocalDate.of(2026, 11, 12), parse("明日までに提出"))
        assertEquals(LocalDate.of(2026, 11, 13), parse("明後日の授業で発表"))
        assertEquals(LocalDate.of(2026, 11, 13), parse("あさってまでに"))
        assertEquals(LocalDate.of(2026, 11, 14), parse("しあさって"))
    }

    @Test
    fun nextWeekWeekday() {
        // 週は月曜はじまり
        assertEquals(LocalDate.of(2026, 11, 16), parse("来週月曜までにレポート提出"))
        assertEquals(LocalDate.of(2026, 11, 20), parse("来週の金曜日に持ってきて"))
        assertEquals(LocalDate.of(2026, 11, 22), parse("来週日曜"))
        assertEquals(LocalDate.of(2026, 11, 24), parse("再来週の火曜"))
        assertEquals(LocalDate.of(2026, 11, 13), parse("今週金曜まで"))
        assertEquals(LocalDate.of(2026, 11, 16), parse("来週までにやっといて"))
    }

    @Test
    fun weekdayOnlyIsNextOccurrence() {
        assertEquals(LocalDate.of(2026, 11, 13), parse("金曜までに"))
        // 同じ曜日なら翌週
        assertEquals(LocalDate.of(2026, 11, 18), parse("水曜に提出"))
        assertEquals(LocalDate.of(2026, 11, 16), parse("月曜日", monday))
    }

    @Test
    fun dayOfMonth() {
        assertEquals(LocalDate.of(2026, 11, 15), parse("15日までに課題を出して"))
        // 過ぎていれば来月
        assertEquals(LocalDate.of(2026, 12, 5), parse("5日までに"))
        // 当日は今月
        assertEquals(LocalDate.of(2026, 11, 11), parse("11日まで"))
        // 来月にない日は月末にそろえる
        assertEquals(LocalDate.of(2027, 2, 28), parse("30日まで", LocalDate.of(2027, 1, 31)))
    }

    @Test
    fun monthAndDay() {
        assertEquals(LocalDate.of(2026, 12, 3), parse("12月3日に発表"))
        assertEquals(LocalDate.of(2026, 11, 20), parse("11/20までに"))
        assertEquals(LocalDate.of(2026, 11, 20), parse("１１／２０締め切り"))
        // 過ぎていれば来年
        assertEquals(LocalDate.of(2027, 1, 10), parse("1月10日提出"))
        assertNull(parse("2月30日"))
    }

    @Test
    fun otherExpressions() {
        assertEquals(LocalDate.of(2026, 11, 30), parse("月末までに"))
        assertEquals(LocalDate.of(2026, 12, 31), parse("来月末"))
        // 「今月中」「来月いっぱい」も月末（#138）
        assertEquals(LocalDate.of(2026, 11, 30), parse("今月中に出す"))
        assertEquals(LocalDate.of(2026, 12, 31), parse("来月いっぱいで終わる"))
        assertEquals(LocalDate.of(2026, 12, 10), parse("来月10日"))
        assertEquals(LocalDate.of(2026, 11, 14), parse("3日後"))
        assertEquals(LocalDate.of(2026, 11, 25), parse("2週間後"))
        assertEquals(LocalDate.of(2026, 11, 14), parse("週末までに"))
    }

    @Test
    fun noDate() {
        assertNull(parse("スライド作っといて"))
        assertNull(parse("次回教科書を持ってきて"))
        assertNull(parse("3日間かかる"))
    }

    @Test
    fun findReturnsRange() {
        val m = parser.find("レポートを明日までに", wednesday)!!
        assertEquals("明日", "レポートを明日までに".substring(m.range))
    }
}
