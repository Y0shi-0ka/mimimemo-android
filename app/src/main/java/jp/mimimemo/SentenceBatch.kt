package jp.mimimemo

import java.time.LocalDate

/**
 * 聞き取った文をメモリにためておき、まとめて候補にする（issue #36）。
 * DB にも端末にも保存しない。画面を閉じれば消える。
 *
 * まとめて調べるので、文をまたいだ文脈を使える：
 * 「来週月曜が締め切りです」「レポート出しといて」のように期限が前の文にあっても補える。
 */
class SentenceBatch {

    private val pending = mutableListOf<String>()

    /** 前回まとめた最後の数文。次の束の期限を補うためだけに使う */
    private var tail: List<String> = emptyList()

    val size: Int get() = pending.size

    fun add(text: String) {
        pending += text
    }

    /**
     * ためた文を取り出す。
     * @return 前回の終わりの文（文脈用）と、今回調べる文
     */
    fun drain(): Pair<List<String>, List<String>> {
        val context = tail
        val sentences = pending.toList()
        pending.clear()
        tail = (context + sentences).takeLast(CONTEXT_SENTENCES)
        return context to sentences
    }

    companion object {
        /** 期限を探しにいく前の文の数 */
        const val CONTEXT_SENTENCES = 2

        /**
         * 期限のない候補に、近くの文の期限を補う。直前の文、その前の文、直後の文の順に探す。
         * @param sentences 文脈用の文も含めたすべての文
         * @param todos sentences と同じ並びの抽出結果（候補にならなかった文は null）
         */
        fun fillDueFromContext(
            sentences: List<String>,
            todos: List<Todo?>,
            findDate: (String) -> LocalDate?,
        ): List<Todo?> = todos.mapIndexed { i, todo ->
            if (todo == null || todo.due != null) return@mapIndexed todo
            val due = listOf(i - 1, i - 2, i + 1)
                .filter { it in sentences.indices }
                .firstNotNullOfOrNull { findDate(sentences[it]) }
            if (due != null) todo.copy(due = due) else todo
        }
    }
}
