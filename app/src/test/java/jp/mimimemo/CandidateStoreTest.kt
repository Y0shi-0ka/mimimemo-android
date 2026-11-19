package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 決める前の候補の保存形式（issue #55） */
class CandidateStoreTest {

    @Test
    fun roundTrip() {
        val list = listOf(
            Todo(id = -1, title = "レポート提出する", due = LocalDate.of(2026, 11, 23), tag = "授業", sourceText = "来週月曜までにレポート"),
            Todo(id = -2, title = "スライド作る", due = null, tag = Todo.TAG_OTHER, sourceText = null),
        )
        // 聞いた時刻（createdAt）も残る（#62）
        assertEquals(list, CandidateStore.decode(CandidateStore.encode(list)))
    }

    @Test
    fun brokenValuesAreSkipped() {
        assertEquals(emptyList<Todo>(), CandidateStore.decode(null))
        assertEquals(emptyList<Todo>(), CandidateStore.decode("壊れた値"))
        val r = CandidateStore.decode("""[{"id":-1,"title":"a","due":"2026-13-99","tag":"授業"},{"id":-2,"title":"b","due":null,"tag":"その他"}]""")
        assertEquals(listOf("b"), r.map { it.title })
    }
}
