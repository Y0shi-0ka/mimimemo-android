package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 音声認識の結果そのままの文（句読点がない・漢数字が混じる）での抽出を確かめる（issue #22）。
 */
class RecognizedSpeechTest {

    private val today = LocalDate.of(2026, 11, 11) // 水曜
    private val parser = DateParser()
    private val extractor = TodoExtractor(keywords = { TodoExtractor.DEFAULT_KEYWORDS }, today = { today })

    private fun date(text: String) = parser.parse(text, today)
    private fun todo(text: String) = extractor.extractByRule(text)

    @Test
    fun kanjiNumeralDates() {
        assertEquals(LocalDate.of(2026, 11, 20), date("十一月二十日までに提出"))
        assertEquals(LocalDate.of(2026, 12, 5), date("十二月五日"))
        assertEquals(LocalDate.of(2026, 11, 14), date("三日後に持ってきて"))
        assertEquals(LocalDate.of(2026, 11, 25), date("二週間後が締め切り"))
        assertEquals(LocalDate.of(2026, 12, 11), date("一か月後までに"))
        assertEquals(LocalDate.of(2026, 11, 15), date("十五日まで"))
        assertEquals(LocalDate.of(2026, 11, 30), date("三十日に提出"))
        assertEquals(LocalDate.of(2026, 12, 10), date("来月十日まで"))
    }

    @Test
    fun kanjiNumeralsOutsideDatesAreKept() {
        // 日付でない数は書き換えない（タイトルに残る）
        assertEquals("三人でスライドを作る", todo("三人でスライドを作っといて").title)
        assertNull(date("二人でやっといて"))
        assertNull(date("一日中かかった"))
        assertEquals("二〇二六年の目標を決めておく", todo("二〇二六年の目標を決めておいて").title)
    }

    @Test
    fun timesAreNotDates() {
        assertNull(date("15時に集合"))
        assertNull(date("午後3時から会議"))
        assertNull(date("3時間後に戻る"))
        assertNull(date("十五時に集合"))
        assertNull(date("30分後に提出"))
        // 日付と時刻が両方あるときは日付だけを取る
        assertEquals(LocalDate.of(2026, 11, 20), date("11月20日の15時までに提出"))
    }

    @Test
    fun titlesFromUnpunctuatedSpeech() {
        fun title(s: String) = todo(s).title
        assertEquals("レポート提出する", title("えっとじゃあ来週の月曜までにレポート提出してください"))
        assertEquals("レポートを提出する", title("十一月二十日までにレポートを提出しておいてください"))
        assertEquals("スライドを作る", title("あのスライドを作っといてね"))
        assertEquals("次回教科書を持ってくる", title("じゃあ次回教科書を持ってきてください"))
        assertEquals("実験のレポートを出しておく", title("それと実験のレポートを金曜までに出しておいて"))
    }

    @Test
    fun dueFromUnpunctuatedSpeech() {
        assertEquals(LocalDate.of(2026, 11, 16), todo("えっとじゃあ来週の月曜までにレポート提出してください").due)
        assertEquals(LocalDate.of(2026, 11, 20), todo("十一月二十日までにレポートを提出しておいてください").due)
        assertEquals(LocalDate.of(2026, 11, 13), todo("それと実験のレポートを金曜までに出しておいて").due)
    }

    @Test
    fun sourceTextKeepsOriginalWording() {
        assertEquals("十一月二十日までにレポートを提出", todo("十一月二十日までにレポートを提出").sourceText)
    }

    @Test
    fun duplicateCandidatesAreSuppressed() {
        val seen = CandidateDeduper()
        val a = todo("来週月曜までにレポート提出してください")
        assertTrue(seen.isNew(a))
        // 言い直し・聞き返しで同じ内容がもう一度来た
        assertFalse(seen.isNew(todo("来週の月曜までにレポート提出してね")))
        // 期限が違えば別の ToDo
        assertTrue(seen.isNew(todo("金曜までにレポート提出してください")))
        // 空白や句読点の違いは同じとみなす
        assertFalse(seen.isNew(a.copy(title = "レポート 提出する。")))
    }
}
