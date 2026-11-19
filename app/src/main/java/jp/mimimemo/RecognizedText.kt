package jp.mimimemo

/**
 * 音声認識の結果を、抽出しやすい文に整える（issue #42）。
 */
object RecognizedText {

    /** 日本語の間に入る空白。両側が英字のとき（英単語どうし）は残す */
    private val SPACE_BETWEEN = Regex("(?<![A-Za-z])[ 　]+|[ 　]+(?![A-Za-z])")

    /**
     * 端末内の認識器は「僕 で は ない よ」のように単語の間に空白を入れて返すことがある。
     * 空白があるとキーワード（「作っといて」など）に一致しないので取り除く。
     */
    fun normalize(text: String): String = text.trim().replace(SPACE_BETWEEN, "")

    /** 文の終わりの記号。この直後で分ける */
    private val SENTENCE_END = Regex("(?<=[。？！?!])")

    /**
     * 連続認識の区切りには「〜よ。いやもういいや。あれやめるかな。」のように文がいくつも入るので、文に分ける（#69）。
     * [MIN_SENTENCE] 文字より短い切れ端（「うん。」など）は、前の文につなぐ。
     */
    fun splitSentences(text: String): List<String> {
        val parts = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in parts) {
            if (out.isNotEmpty() && p.trimEnd('。', '？', '！', '?', '!').length < MIN_SENTENCE) {
                out[out.lastIndex] = out.last() + p
            } else {
                out += p
            }
        }
        return out
    }

    private const val MIN_SENTENCE = 3

    /**
     * 認識の候補から1つ選ぶ。候補は確からしい順に並んでいるので先頭を基本にし、
     * 先頭が [prefer] に当てはまらず、後ろの候補が当てはまるなら（キーワードを含むなど）そちらを選ぶ。
     */
    fun choose(hypotheses: List<String>, prefer: (String) -> Boolean): String? {
        val cleaned = hypotheses.map(::normalize).filter { it.isNotEmpty() }
        val first = cleaned.firstOrNull() ?: return null
        if (prefer(first)) return first
        return cleaned.firstOrNull(prefer) ?: first
    }
}
