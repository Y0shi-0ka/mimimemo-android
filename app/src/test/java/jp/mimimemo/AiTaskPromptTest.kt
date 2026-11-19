package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端末内 AI への問いと答えの読み方（issue #68） */
class AiTaskPromptTest {

    @Test
    fun userMessageNumbersFromOne() {
        assertEquals("1: こんにちは\n2: レポート出しといて\n答え", AiTaskPrompt.userMessage(listOf("こんにちは", "レポート出しといて")))
    }

    @Test
    fun parsesLines() {
        val reply = """
            2|レポートを出す|来週の月曜|授業
            4|シフトを代わる||バイト
        """.trimIndent()
        assertEquals(
            listOf(
                AiTaskPrompt.Task(1, "レポートを出す", "来週の月曜", "授業"),
                AiTaskPrompt.Task(3, "シフトを代わる", null, "バイト"),
            ),
            AiTaskPrompt.parse(reply, 4),
        )
    }

    @Test
    fun toleratesSmallModelNoise() {
        // 全角の区切り、行頭の記号、タグの抜け、知らないタグ、句点
        val reply = "・1｜資料を印刷する｜明日\n- 2|発表の練習をする。|なし|趣味\n答え: 以上です"
        assertEquals(
            listOf(
                AiTaskPrompt.Task(0, "資料を印刷する", "明日", null),
                AiTaskPrompt.Task(1, "発表の練習をする", null, null),
            ),
            AiTaskPrompt.parse(reply, 2),
        )
    }

    @Test
    fun dropsInvalidLines() {
        assertTrue(AiTaskPrompt.parse("なし", 3).isEmpty())
        // 範囲外の番号・やることが空・同じ番号の2回目
        val r = AiTaskPrompt.parse("9|何か||その他\n1|||その他\n2|買い物する||その他\n2|もう一度||その他", 3)
        assertEquals(listOf(AiTaskPrompt.Task(1, "買い物する", null, "その他")), r)
    }
}
