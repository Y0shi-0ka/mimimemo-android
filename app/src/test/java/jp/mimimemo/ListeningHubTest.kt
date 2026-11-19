package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 候補の束に同じ内容が二重に入らないこと（#91） */
class ListeningHubTest {

    private val friday = LocalDate.of(2026, 11, 20)

    @Test
    fun sameCandidateIsNotAddedTwiceAndIsDroppedAfterAdding() {
        ListeningHub.candidates.value.forEach { ListeningHub.removeCandidate(it) }

        ListeningHub.addCandidate(Todo(title = "レポートを出す", due = friday))
        ListeningHub.addCandidate(Todo(title = "レポート出す", due = friday))
        ListeningHub.addCandidate(Todo(title = "レポート出す"))
        ListeningHub.addCandidate(Todo(title = "牛乳を買う"))
        assertEquals(listOf("レポートを出す", "牛乳を買う"), ListeningHub.candidates.value.map { it.title })

        // 期限が違えば、期限の変更として別に出す
        ListeningHub.addCandidate(Todo(title = "レポート出す", due = friday.plusDays(3)))
        assertEquals(3, ListeningHub.candidates.value.size)

        // 追加したら、同じ内容の残りも外す（期限が違うものは残す）
        ListeningHub.dropSameAs(Todo(title = "レポート出す", due = friday))
        assertEquals(listOf("牛乳を買う", "レポート出す"), ListeningHub.candidates.value.map { it.title })

        ListeningHub.candidates.value.forEach { ListeningHub.removeCandidate(it) }
    }
}
