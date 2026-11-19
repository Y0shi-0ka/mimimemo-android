package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** 5分ごとにまとめて候補にする処理（issue #36） */
class SentenceBatchTest {

    private val today = LocalDate.of(2026, 11, 11) // 水曜
    private val parser = DateParser()
    private val extractor = TodoExtractor(keywords = { TodoExtractor.DEFAULT_KEYWORDS }, today = { today })

    private fun heard(texts: List<String>, on: LocalDate = today) = texts.map { SentenceBatch.Heard(it, on) }

    private fun collect(sentences: List<String>, context: List<String> = emptyList()): List<Todo?> =
        collectHeard(heard(sentences), heard(context))

    private fun collectHeard(sentences: List<SentenceBatch.Heard>, context: List<SentenceBatch.Heard> = emptyList()): List<Todo?> {
        val all = context + sentences
        val todos = context.map { null } + sentences.map { extractor.extractByRuleOrNull(it.text, it.on) }
        return SentenceBatch.fillDueFromContext(all, todos) { parser.parse(it.text, it.on) }.drop(context.size)
    }

    private fun TodoExtractor.extractByRuleOrNull(text: String, on: LocalDate): Todo? =
        if (TodoExtractor.DEFAULT_KEYWORDS.any { text.contains(it) }) extractByRule(text, on) else null

    @Test
    fun dueFromPreviousSentence() {
        val r = collect(listOf("来週月曜が提出の日です", "スライド作っといてね"))
        assertEquals(LocalDate.of(2026, 11, 16), r[1]?.due)
    }

    @Test
    fun ownDueWins() {
        val r = collect(listOf("来週月曜が提出の日です", "金曜までにスライド作っといて"))
        assertEquals(LocalDate.of(2026, 11, 13), r[1]?.due)
    }

    @Test
    fun dueFromNextSentence() {
        val r = collect(listOf("スライド作っといて", "金曜の授業で使うから"))
        assertEquals(LocalDate.of(2026, 11, 13), r[0]?.due)
    }

    @Test
    fun noDateNearby() {
        val r = collect(listOf("今日はいい天気", "あ", "い", "スライド作っといて"))
        assertNull(r[3]?.due)
    }

    @Test
    fun contextFromPreviousBatch() {
        val batch = SentenceBatch()
        batch.add("明日が締め切りの話")
        batch.add("えーと")
        batch.drain()
        batch.add("スライド作っといて")
        val (context, sentences) = batch.drain()
        assertEquals(listOf("明日が締め切りの話", "えーと"), context.map { it.text })
        val r = collectHeard(sentences.map { it.copy(on = today) }, context.map { it.copy(on = today) })
        assertEquals(LocalDate.of(2026, 11, 12), r[0]?.due)
    }

    @Test
    fun drainEmpties() {
        val batch = SentenceBatch()
        batch.add("a")
        assertEquals(1, batch.size)
        batch.drain()
        assertEquals(0, batch.size)
    }

    @Test
    fun dueCountsFromTheDayItWasHeard() {
        // 23:58 に聞いた「明日まで」を、日付が変わってからまとめても期限はずれない（#60）
        val heardOn = LocalDate.of(2026, 11, 11)
        val r = collectHeard(listOf(SentenceBatch.Heard("明日までにレポート出しといて", heardOn)))
        assertEquals(LocalDate.of(2026, 11, 12), r[0]?.due)
        // 前の文から補うときも、その文を聞いた日で数える
        val r2 = collectHeard(
            listOf(
                SentenceBatch.Heard("明日が提出の日です", heardOn),
                SentenceBatch.Heard("スライド作っといて", heardOn.plusDays(1)),
            )
        )
        assertEquals(LocalDate.of(2026, 11, 12), r2[1]?.due)
    }
}
