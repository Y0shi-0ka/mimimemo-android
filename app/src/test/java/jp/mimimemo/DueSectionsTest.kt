package jp.mimimemo

import jp.mimimemo.DueSections.Section
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 一覧の期限の区切り（issue #49） */
class DueSectionsTest {

    private val wed = LocalDate.of(2026, 11, 11)

    @Test
    fun sections() {
        assertEquals(Section.OVERDUE, DueSections.sectionOf(wed.minusDays(1), wed))
        assertEquals(Section.TODAY, DueSections.sectionOf(wed, wed))
        assertEquals(Section.TOMORROW, DueSections.sectionOf(wed.plusDays(1), wed))
        assertEquals(Section.THIS_WEEK, DueSections.sectionOf(LocalDate.of(2026, 11, 15), wed)) // 日曜
        assertEquals(Section.LATER, DueSections.sectionOf(LocalDate.of(2026, 11, 16), wed)) // 来週月曜
        assertEquals(Section.NONE, DueSections.sectionOf(null, wed))
    }

    @Test
    fun sundayTomorrowIsNotThisWeek() {
        val sun = LocalDate.of(2026, 11, 15)
        assertEquals(Section.TOMORROW, DueSections.sectionOf(sun.plusDays(1), sun))
        assertEquals(Section.LATER, DueSections.sectionOf(sun.plusDays(2), sun))
    }

    @Test
    fun groupSkipsEmptySectionsAndKeepsOrder() {
        val a = Todo(id = 1, title = "a", due = null)
        val b = Todo(id = 2, title = "b", due = wed)
        val c = Todo(id = 3, title = "c", due = wed)
        val d = Todo(id = 4, title = "d", due = wed.minusDays(3))
        val groups = DueSections.group(listOf(d, b, c, a), wed)
        assertEquals(listOf(Section.OVERDUE, Section.TODAY, Section.NONE), groups.map { it.first })
        assertEquals(listOf(b, c), groups[1].second)
    }
}
