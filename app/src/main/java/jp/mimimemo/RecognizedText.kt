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
