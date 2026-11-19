package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 期限の札の選択肢（issue #47） */
class ChoiceChipsTest {

    private fun labels(today: LocalDate) = ChoiceChips.dueOptions(today).map { it.label to it.date }

    @Test
    fun wednesday() {
        val wed = LocalDate.of(2026, 11, 11)
        assertEquals(
            listOf(
                "なし" to null,
                "今日" to wed,
                "明日" to LocalDate.of(2026, 11, 12),
                "金曜" to LocalDate.of(2026, 11, 13),
                "来週月曜" to LocalDate.of(2026, 11, 16),
            ),
            labels(wed),
        )
    }

    @Test
    fun thursdayDropsFridayBecauseTomorrowIsFriday() {
        val thu = LocalDate.of(2026, 11, 12)
        assertEquals(listOf("なし", "今日", "明日", "来週月曜"), labels(thu).map { it.first })
    }

    @Test
    fun fridayShowsNextFriday() {
        val fri = LocalDate.of(2026, 11, 13)
        val r = labels(fri)
        assertEquals("来週金曜" to LocalDate.of(2026, 11, 20), r[3])
    }

    @Test
    fun sundayDropsMondayBecauseTomorrowIsMonday() {
        val sun = LocalDate.of(2026, 11, 15)
        val r = labels(sun).map { it.first }
        assertEquals(listOf("なし", "今日", "明日", "来週金曜"), r)
    }
}
