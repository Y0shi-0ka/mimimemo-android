package jp.mimimemo

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 評価セット（eval/conversations.json）をルールで流し、見逃し・誤検出・題名を数える（issue #71）。
 * 端末内 AI は eval/run_llm_eval.py で同じ例を流して比べる。
 * 成績が下がったら失敗するように、今の成績を下限にしている（よくなったら下限を上げる）。
 */
class TodoEvalTest {

    private val today = LocalDate.of(2026, 11, 11)
    private val extractor = TodoExtractor(keywords = { TodoExtractor.DEFAULT_KEYWORDS }, today = { today })

    private fun evalFile(): File =
        listOf(File("../eval/conversations.json"), File("eval/conversations.json")).first { it.exists() }

    @Test
    fun ruleBasedScores() = runBlocking {
        val cases = JSONObject(evalFile().readText()).getJSONArray("cases")
        var tp = 0
        var fp = 0
        var fn = 0
        var titleOk = 0
        var titleAll = 0
        // 直すときに見ていない例（split=holdout）だけの成績も出す（合わせ込みの度合いを見るため）
        var hTp = 0
        var hFp = 0
        var hFn = 0
        val report = StringBuilder()
        val rulesJson = JSONObject()
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val sentences = case.getJSONArray("sentences").let { a -> (0 until a.length()).map { a.getString(it) } }
            val expected = case.getJSONArray("tasks").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            val titleHas = case.optJSONObject("title_has")
            // アプリの5分まとめと同じ流れ（文ごとの判定 → 直前の候補の続き）
            val heard = sentences.map { SentenceBatch.Heard(it, today) }
            val todos = SentenceBatch.continueFromPrevious(heard, sentences.map { extractor.extract(it) })
            val found = todos.mapIndexedNotNull { i, t -> t?.let { i + 1 to it.title } }
            val foundIdx = found.map { it.first }.toSet()
            rulesJson.put(case.getString("name"), org.json.JSONArray(foundIdx.sorted()))
            tp += (foundIdx intersect expected).size
            fp += (foundIdx - expected).size
            fn += (expected - foundIdx).size
            if (case.optString("split") == "holdout") {
                hTp += (foundIdx intersect expected).size
                hFp += (foundIdx - expected).size
                hFn += (expected - foundIdx).size
            }
            for ((i, title) in found) {
                val want = titleHas?.optString(i.toString())?.takeIf { it.isNotEmpty() } ?: continue
                titleAll++
                if (title.contains(want)) titleOk++
            }
            report.append(if (foundIdx == expected) "OK " else "NG ")
                .append(case.getString("name")).append(": 期待=").append(expected.sorted())
                .append(" 結果=").append(found).append('\n')
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        report.append("適合率 %.2f（誤検出 %d） 再現率 %.2f（見逃し %d） 題名 %d/%d".format(precision, fp, recall, fn, titleOk, titleAll))
        val hP = if (hTp + hFp == 0) 1.0 else hTp.toDouble() / (hTp + hFp)
        val hR = if (hTp + hFn == 0) 1.0 else hTp.toDouble() / (hTp + hFn)
        report.append("\n（見ていない例だけ）適合率 %.2f（誤検出 %d） 再現率 %.2f（見逃し %d）".format(hP, hFp, hR, hFn))
        println(report)
        // AI との組み合わせを eval/run_llm_eval.py で計算できるよう、ルールの結果を書き出す
        File("build/eval-rules.json").apply { parentFile?.mkdirs() }.writeText(rulesJson.toString())
        assertTrue("適合率が下がった\n$report", precision >= MIN_PRECISION)
        assertTrue("再現率が下がった\n$report", recall >= MIN_RECALL)
    }

    private companion object {
        // 2026-11-20（#80 のあと）、全36例で 適合率 0.98・再現率 0.85、見ていない20例で 1.00・0.74。
        // 例を54件に増やして（#71）、全体で 0.96・0.86、見ていない38例で 0.97・0.80。下限は少し余裕を持たせる
        const val MIN_PRECISION = 0.90
        const val MIN_RECALL = 0.80
    }
}
