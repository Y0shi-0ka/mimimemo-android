package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 繰り返しの ToDo（#107） */
class RepeatTest {

    // 2026-11-18 は水曜
    private val today = LocalDate.of(2026, 11, 18)
    private val e = TodoExtractor(keywords = { TodoExtractor.DEFAULT_KEYWORDS }, today = { today })

    @Test
    fun nextDateIsAfterToday() {
        assertEquals(LocalDate.of(2026, 11, 25), Repeat.WEEKLY.nextAfter(LocalDate.of(2026, 11, 18), today))
        // 遅れて完了しても、過ぎた日付にはしない
        assertEquals(LocalDate.of(2026, 11, 23), Repeat.WEEKLY.nextAfter(LocalDate.of(2026, 11, 2), today))
        assertEquals(LocalDate.of(2026, 11, 19), Repeat.DAILY.nextAfter(LocalDate.of(2026, 11, 10), today))
        assertEquals(LocalDate.of(2026, 12, 25), Repeat.MONTHLY.nextAfter(LocalDate.of(2026, 11, 25), today))
        assertEquals(Repeat.WEEKLY, Repeat.fromKey("weekly"))
        assertNull(Repeat.fromKey(null))
    }

    @Test
    fun repeatIsReadFromSpeech() {
        val garbage = e.extractByRule("毎週月曜にゴミ出し")
        assertEquals(Repeat.WEEKLY, garbage.repeat)
        assertEquals("ゴミ出し", garbage.title)
        assertEquals(LocalDate.of(2026, 11, 23), garbage.due)

        val pill = e.extractByRule("毎日薬を飲まないと")
        assertEquals(Repeat.DAILY, pill.repeat)
        assertEquals(today, pill.due)
        assertTrue(pill.title, pill.title.startsWith("薬を飲"))

        assertEquals(Repeat.MONTHLY, e.extractByRule("毎月25日に家賃を払う").repeat)
        assertNull(e.extractByRule("明日までにレポート出す").repeat)
    }

    @Test
    fun repeatWithDateIsCandidate() {
        assertTrue(e.isCandidate("毎週月曜はゴミ出し"))
        // 「毎日暑いね」は候補にしない
        assertEquals(false, e.isCandidate("毎日暑いね"))
    }
}
