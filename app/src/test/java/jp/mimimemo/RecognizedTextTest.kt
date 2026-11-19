package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 認識結果の整え方（issue #42） */
class RecognizedTextTest {

    @Test
    fun spacesBetweenJapaneseAreRemoved() {
        assertEquals("僕ではないよ", RecognizedText.normalize("僕 で は ない よ"))
        assertEquals("スライド作っといて", RecognizedText.normalize("スライド 作っ と い て"))
        assertEquals("11月20日までに提出", RecognizedText.normalize("11 月 20 日 まで に 提出"))
        assertEquals("全角も消す", RecognizedText.normalize("全角　も 消す"))
    }

    @Test
    fun spacesBetweenEnglishWordsAreKept() {
        assertEquals("Google Driveに上げといて", RecognizedText.normalize("Google Drive に 上げ といて"))
        assertEquals("pull requestを出す", RecognizedText.normalize("pull request を 出す"))
    }

    @Test
    fun choosesHypothesisWithKeyword() {
        val keywords = TodoExtractor.DEFAULT_KEYWORDS
        val prefer = { s: String -> keywords.any { s.contains(it) } }
        // 先頭がキーワードを含めば先頭
        assertEquals("資料を準備して", RecognizedText.choose(listOf("資料を準備して", "資料を順位して"), prefer))
        // 先頭が含まず、後ろが含むなら後ろ
        assertEquals("スライド作っといて", RecognizedText.choose(listOf("スライド作っと出て", "スライド 作っ と い て"), prefer))
        // どれも含まなければ先頭
        assertEquals("今日はいい天気", RecognizedText.choose(listOf("今日はいい天気", "今日は良い天気"), prefer))
        assertNull(RecognizedText.choose(listOf(" ", ""), prefer))
    }
}
