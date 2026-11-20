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
        assertEquals("二〇二六年の目標を決める", todo("二〇二六年の目標を決めておいて").title)
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
        assertEquals("実験のレポートを出す", title("それと実験のレポートを金曜までに出しておいて"))
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

    @Test
    fun dateNoticesAreNotCandidates() {
        // 期限を伝えるだけの文（issue #39）
        assertTrue(extractor.isDateNotice("来週月曜が提出の日です"))
        assertTrue(extractor.isDateNotice("締め切りは金曜です"))
        assertTrue(extractor.isDateNotice("提出は十一月二十日だよ"))
        assertTrue(extractor.isDateNotice("期限は明日です"))
        // やることが残る文は候補のまま
        assertFalse(extractor.isDateNotice("レポートの締め切りは金曜です"))
        assertFalse(extractor.isDateNotice("金曜までにレポート提出して"))
        assertFalse(extractor.isDateNotice("提出お願いします"))
        assertNull(kotlinx.coroutines.runBlocking { extractor.extract("来週月曜が提出の日です") })
    }

    @Test
    fun leadingParticleAfterDateIsRemoved() {
        assertEquals("レポートを出す", todo("明日はレポートを出す").title)
        assertEquals("発表なので準備をする", todo("来週月曜が発表なので準備をしておいて").title)
        assertEquals("スライドを作る", todo("金曜にスライドを作っといて").title)
    }

    @Test
    fun deduperForgetsOldest() {
        val seen = CandidateDeduper(limit = 2)
        val a = Todo(title = "a")
        val b = Todo(title = "b")
        val c = Todo(title = "c")
        assertTrue(seen.isNew(a))
        assertTrue(seen.isNew(b))
        assertTrue(seen.isNew(c)) // a を忘れる
        assertFalse(seen.isNew(c))
        assertTrue(seen.isNew(a))
    }

    @Test
    fun naturalTaskExpressionsAreCandidates() {
        // 辞書にない普段の言い方でも候補にする（#66）
        val bare = TodoExtractor(keywords = { emptyList() }, today = { today })
        listOf(
            "レポート出さなきゃ",
            "明日までに洗濯しないと",
            "スライド作らなければいけない",
            "金曜に面談する予定です",
            "週末に部屋を片付けるつもり",
            "資料を送ってください",
            "それ調べておいて",
            "ゴミ出しといて",
            "傘持っていくの忘れないようにしておく",
            "明日レポート出す",
            "金曜テストある",
            "帰りに牛乳買ってきて",
            "図書館で本借りてきてね",
        ).forEach { assertTrue(it, bare.isCandidate(it)) }
        listOf(
            "今日はいい天気ですね",
            "明日は晴れらしい",
            "ちょっとくらいならいいよ",
            "昨日の試合すごかった",
            "レポート出した",
        ).forEach { assertFalse(it, bare.isCandidate(it)) }
    }

    @Test
    fun naturalTaskTitles() {
        assertEquals("レポート出す", todo("レポート出さなきゃ").title)
        assertEquals("洗濯する", todo("洗濯しないと").title)
        assertEquals("スライド作る", todo("スライド作らなければいけない").title)
        assertEquals("面談する", todo("金曜に面談する予定です").title)
        assertEquals("部屋を片付ける", todo("部屋を片付けるつもり").title)
        assertEquals("レポート出す", todo("明日レポート出す").title)
    }

    @Test
    fun alreadyListedTodosAreSkipped() {
        // すでに一覧にある ToDo と同じ候補は出さない（#72）
        val listed = listOf(Todo(id = 1, title = "レポートを提出する", due = LocalDate.of(2026, 11, 16)))
        assertTrue(CandidateDeduper.isAlreadyListed(Todo(title = "レポート提出", due = LocalDate.of(2026, 11, 16)), listed))
        assertTrue(CandidateDeduper.isAlreadyListed(Todo(title = "レポート 提出する。"), listed))
        // 期限が違えば、期限の変更として出す
        assertFalse(CandidateDeduper.isAlreadyListed(Todo(title = "レポート提出", due = LocalDate.of(2026, 11, 20)), listed))
        // 完了したものとは比べない・題名が違えば出す
        assertFalse(CandidateDeduper.isAlreadyListed(Todo(title = "レポート提出"), listed.map { it.copy(done = true) }))
        assertFalse(CandidateDeduper.isAlreadyListed(Todo(title = "スライドを作る"), listed))
    }
}
