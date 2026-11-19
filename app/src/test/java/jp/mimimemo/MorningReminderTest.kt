package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 朝の通知の中身（#100） */
class MorningReminderTest {

    private val today = LocalDate.of(2026, 11, 20)

    @Test
    fun nothingToRemind() {
        assertNull(MorningReminder.message(emptyList(), today))
        assertNull(MorningReminder.message(listOf(Todo(title = "いつか"), Todo(title = "来週", due = today.plusDays(7))), today))
    }

    @Test
    fun countsAndUrgentTitlesFirst() {
        val todos = listOf(
            Todo(title = "明日A", due = today.plusDays(1)),
            Todo(title = "今日A", due = today),
            Todo(title = "期限なし"),
            Todo(title = "今日B", due = today),
            Todo(title = "過ぎた", due = today.minusDays(2)),
        )
        val m = MorningReminder.message(todos, today)!!
        assertEquals("期限切れ 1件・今日が期限 2件・明日 1件", m.title)
        assertEquals(listOf("過ぎた", "今日A", "今日B"), m.lines)
    }

    @Test
    fun onlyTomorrow() {
        val m = MorningReminder.message(listOf(Todo(title = "明日A", due = today.plusDays(1))), today)!!
        assertEquals("明日 1件", m.title)
        assertEquals(listOf("明日A"), m.lines)
    }

    @Test
    fun nextAtIsTodayOrTomorrow() {
        val morning = java.time.LocalDateTime.of(2026, 11, 20, 6, 30)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 20, 7, 0), MorningReminder.nextAt(morning, 7))
        val late = java.time.LocalDateTime.of(2026, 11, 20, 9, 0)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 21, 9, 0), MorningReminder.nextAt(late, 9))
    }
}
