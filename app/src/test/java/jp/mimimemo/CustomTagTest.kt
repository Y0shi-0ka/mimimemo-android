package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自分で足したタグ（科目名など、#10） */
class CustomTagTest {

    private fun extractor(tags: List<String>) =
        TodoExtractor(keywords = { TodoExtractor.DEFAULT_KEYWORDS }, customTags = { tags })

    @Test
    fun customTagWinsOverRules() {
        val e = extractor(listOf("データベース", "データベース演習"))
        // 「レポート」だけなら「授業」だが、足したタグの名前があればそちらを付ける。長い名前を優先
        assertEquals("データベース", e.guessTag("データベースのレポート出さなきゃ"))
        assertEquals("データベース演習", e.guessTag("データベース演習の課題やらなきゃ"))
        assertEquals("授業", e.guessTag("統計のレポート出さなきゃ"))
        assertNull(e.customTag("統計のレポート出さなきゃ"))
    }

    @Test
    fun allTagsKeepOtherLast() {
        assertEquals(listOf("統計", "授業", "研究室", "グループ", "バイト", "その他"), TagStore.allTags(listOf("統計")))
        assertEquals(Todo.TAGS, TagStore.allTags(emptyList()))
    }
}
