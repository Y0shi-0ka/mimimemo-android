package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 題名を、やることの要約にする（#144） */
class TaskSummaryTest {

    @Test
    fun endingsBecomePlainForm() {
        assertEquals("エントリーシート書く", TaskSummary.of("エントリーシート書いとく"))
        assertEquals("課題を出す", TaskSummary.of("課題を出しておく"))
        assertEquals("みんなに連絡する", TaskSummary.of("みんなに連絡しとく"))
        assertEquals("病院の予約入れる", TaskSummary.of("病院の予約入れとく"))
        assertEquals("買う", TaskSummary.of("買っとく"))
        assertEquals("授業のスライド、あとで共有する", TaskSummary.of("授業のスライド、あとで共有します"))
        assertEquals("議事録は書く", TaskSummary.of("議事録は書きます"))
        assertEquals("飲み会の会費、払う", TaskSummary.of("飲み会の会費、払って"))
        assertEquals("課題をていしゅつする", TaskSummary.of("課題をていしゅつして"))
        assertEquals("朝出す", TaskSummary.of("朝出する"))
        assertEquals("書類を提出する", TaskSummary.of("書類を提出する"))
    }

    @Test
    fun prefaceAndParticles() {
        assertEquals("帰りにプリンター用紙買ってくる", TaskSummary.of("悪いんだけど、帰りにプリンター用紙買ってくる"))
        assertEquals("論文を読む", TaskSummary.of("論文も読む"))
        assertEquals("メール、返信する", TaskSummary.of("のメール、返信する"))
    }

    @Test
    fun realSpeechFillersAndFragments() {
        // 実機で話した文から作られた題名（11/20）
        assertEquals(
            "金テスト行く",
            TaskSummary.of("話で明日なんか色々とやらなきゃいけないとあった気がしてて、えっと金テスト行かなきゃダメなのとえっと"),
        )
        assertEquals("金テスト行く", TaskSummary.of("えっと金テスト行かなきゃダメなのとえっと"))
        assertEquals("レポート出す", TaskSummary.of("なんかレポート出さなきゃいけない"))
    }

    @Test
    fun shortTitlesKeepTheirReason() {
        // 短いときは理由も残す（「準備をする」だけでは何の準備か分からない）
        assertEquals("発表なので準備をする", TaskSummary.of("発表なので準備をしておく"))
    }

    @Test
    fun longSpeechIsShortened() {
        val long = "この前話してた件なんだけど、来月のイベントの会場がまだ決まってないから、駅前の公民館に問い合わせる"
        val s = TaskSummary.of(long)
        assertEquals("駅前の公民館に問い合わせる", s)
        val ramble = "あのゲームを1万時間遊ぶと線路をダッシュするゲームを極めた男は普通に遊ぶだけでは飽き足らずコインを避ける"
        assertTrue(TaskSummary.of(ramble), TaskSummary.of(ramble).length <= TaskSummary.MAX_LENGTH)
    }
}
