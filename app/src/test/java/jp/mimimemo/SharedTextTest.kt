package jp.mimimemo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 共有された文章から候補を作る（#93） */
class SharedTextTest {

    private val today = LocalDate.of(2026, 11, 18)
    private val now = LocalDateTime.of(2026, 11, 18, 10, 0)
    private val extractor = TodoExtractor(keywords = { TodoExtractor.DEFAULT_KEYWORDS }, today = { today })

    private fun find(text: String) = runBlocking { SharedText.findCandidates(text, extractor, now = now) }

    @Test
    fun tasksInMessageBecomeCandidates() {
        val todos = find("おつかれさま！\n明日までに資料を送ってほしい。あと議事録もお願い")
        assertTrue(todos.size >= 1)
        assertTrue(todos.first().title.contains("資料"))
        assertEquals(today.plusDays(1), todos.first().due)
        assertTrue(todos.all { it.createdAt == now })
    }

    @Test
    fun wholeTextIsOneCandidateWhenNothingFound() {
        val todos = find("駅前のパン屋 金曜")
        assertEquals(1, todos.size)
        assertEquals("駅前のパン屋 金曜", todos[0].title)
        assertEquals(LocalDate.of(2026, 11, 20), todos[0].due)
    }

    @Test
    fun longTextIsShortenedAndBlankIsIgnored() {
        assertEquals(41, find("あ".repeat(100)).single().title.length)
        assertTrue(find("  \n ").isEmpty())
    }
}
