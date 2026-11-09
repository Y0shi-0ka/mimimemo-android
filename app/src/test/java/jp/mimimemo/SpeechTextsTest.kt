package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SpeechTextsTest {

    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun dueFormat() {
        assertEquals("期限なし", DueFormatter.format(null, today))
        assertEquals("11/11(水)・今日", DueFormatter.format(today, today))
        assertEquals("11/12(木)・明日", DueFormatter.format(today.plusDays(1), today))
        assertEquals("11/16(月)・あと5日", DueFormatter.format(today.plusDays(5), today))
        assertEquals("11/10(火)・期限切れ", DueFormatter.format(today.minusDays(1), today))
    }

    @Test
    fun reminder() {
        assertEquals(
            "明日は、データベースのレポート、の締め切りです。",
            SpeechTexts.reminder(listOf(Todo(title = "データベースのレポート"))),
        )
        assertEquals(
            "明日が期限のToDoが2件あります。A。B。",
            SpeechTexts.reminder(listOf(Todo(title = "A"), Todo(title = "B"))),
        )
    }

    @Test
    fun summary() {
        assertEquals("今日はまだToDoを登録していません。", SpeechTexts.summary(emptyList()))
        assertEquals(
            "今日は2件のToDoを登録しました。1件目、レポート提出、期限は11月16日。2件目、スライド作る。",
            SpeechTexts.summary(
                listOf(
                    Todo(title = "レポート提出", due = LocalDate.of(2026, 11, 16)),
                    Todo(title = "スライド作る"),
                )
            ),
        )
    }
}
