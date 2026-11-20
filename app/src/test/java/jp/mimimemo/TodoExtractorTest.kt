package jp.mimimemo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodoExtractorTest {

    private val today = LocalDate.of(2026, 11, 11) // 水曜

    private fun extractor(
        keywords: List<String> = TodoExtractor.DEFAULT_KEYWORDS,
        ai: LocalAiClient = UnavailableLocalAiClient,
    ) = TodoExtractor(keywords = { keywords }, ai = ai, today = { today })

    @Test
    fun filtersByKeyword() {
        val e = extractor()
        assertTrue(e.isCandidate("来週月曜までにレポート提出してください"))
        assertTrue(e.isCandidate("次回、教科書を持ってきてね"))
        assertFalse(e.isCandidate("今日は天気がいいですね"))
        assertFalse(e.isCandidate("   "))
    }

    @Test
    fun nonCandidateReturnsNull() = runBlocking {
        assertNull(extractor().extract("お昼どこで食べる？"))
    }

    @Test
    fun userKeywordIsUsed() = runBlocking {
        // 組み込みの言い方（#66）でも辞書の初期値でも拾わない言葉を、ユーザーが足したときだけ拾う
        assertNull(extractor().extract("スライドの件よろしく"))
        val todo = extractor(TodoExtractor.DEFAULT_KEYWORDS + "よろしく").extract("スライドの件よろしく")
        assertNotNull(todo)
        assertEquals("スライドの件よろしく", todo!!.title)
    }

    @Test
    fun ruleExtraction() = runBlocking {
        val todo = extractor().extract("えーと、来週月曜までにレポート提出してください。")!!
        assertEquals("レポート提出する", todo.title)
        assertEquals(LocalDate.of(2026, 11, 16), todo.due)
        assertEquals("授業", todo.tag)
        assertEquals("えーと、来週月曜までにレポート提出してください。", todo.sourceText)
    }

    @Test
    fun titles() {
        val e = extractor()
        fun title(s: String) = e.extractByRule(s).title
        assertEquals("スライド作る", title("スライド作っといて"))
        assertEquals("次回、教科書を持ってくる", title("次回、教科書を持ってきてね"))
        assertEquals("レポートを出す", title("レポートを明日までに出して"))
        // 「返して」「出しといてね」なども辞書形にする（#71）
        assertEquals("本を返す", title("本を返してね"))
        assertEquals("美容院予約する", title("土曜に美容院予約しとこう"))
        assertEquals("統計の過去問解く", title("統計の過去問解いとかないとまずい"))
        assertEquals("課題を出す", title("15日までに課題を出しておいてね"))
        assertEquals("グループのスライドを準備する", title("じゃあ金曜までにグループのスライドを準備して"))
    }

    @Test
    fun manualInputFillsDueAndTag() {
        val e = extractor()
        val todo = e.fromManualInput("明日までにレポート提出")
        assertEquals("レポート提出", todo.title)
        assertEquals(LocalDate.of(2026, 11, 12), todo.due)
        assertEquals("授業", todo.tag)
        assertNull(todo.sourceText)

        // キーワードがなくても、そのままの文で追加できる
        val plain = e.fromManualInput("牛乳を買う")
        assertEquals("牛乳を買う", plain.title)
        assertNull(plain.due)
        assertEquals(Todo.TAG_OTHER, plain.tag)
    }

    @Test
    fun tags() {
        val e = extractor()
        assertEquals("研究室", e.guessTag("ゼミの資料を準備して"))
        assertEquals("バイト", e.guessTag("来週のシフト提出して"))
        assertEquals("グループ", e.guessTag("グループのレポートまとめといて"))
        assertEquals(Todo.TAG_OTHER, e.guessTag("お願いね"))
    }

    @Test
    fun usesLocalAiWhenAvailable() = runBlocking {
        val ai = object : LocalAiClient {
            override fun isAvailable() = true
            override suspend fun extractTask(sentence: String) =
                LocalAiClient.Result(title = "データベースのレポート", dueText = "来週月曜", tag = "授業")
        }
        val todo = extractor(ai = ai).extract("データベースのレポート、来週月曜までに提出ね")!!
        assertEquals("データベースのレポート", todo.title)
        assertEquals(LocalDate.of(2026, 11, 16), todo.due)
    }

    @Test
    fun localAiNoTaskReturnsNull() = runBlocking {
        val ai = object : LocalAiClient {
            override fun isAvailable() = true
            override suspend fun extractTask(sentence: String): LocalAiClient.Result? = null
        }
        assertNull(extractor(ai = ai).extract("提出物ってもう出した？"))
    }

    @Test
    fun localAiFailureFallsBackToRule() = runBlocking {
        val ai = object : LocalAiClient {
            override fun isAvailable() = true
            override suspend fun extractTask(sentence: String): LocalAiClient.Result? = error("model crashed")
        }
        val todo = extractor(ai = ai).extract("明日までに課題を提出して")!!
        assertEquals("課題を提出する", todo.title)
        assertEquals(LocalDate.of(2026, 11, 12), todo.due)
    }
}
