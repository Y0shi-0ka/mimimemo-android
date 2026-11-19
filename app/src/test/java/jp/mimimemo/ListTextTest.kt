package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 一覧を送るときの文章（#116） */
class ListTextTest {
    @Test
    fun oneLinePerTodo() {
        val text = ListText.of(
            listOf(
                Todo(title = "レポートを出す", due = LocalDate.of(2026, 11, 20)),
                Todo(title = "ゴミ出し", due = LocalDate.of(2026, 11, 23), repeat = Repeat.WEEKLY),
                Todo(title = "洗剤を買う"),
            ),
        )
        assertEquals("・レポートを出す（11/20(金)）\n・ゴミ出し（11/23(月)・毎週）\n・洗剤を買う", text)
    }
}
