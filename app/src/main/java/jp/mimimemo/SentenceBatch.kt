package jp.mimimemo

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 聞き取った文をメモリにためておき、まとめて候補にする（issue #36）。
 * DB にも端末にも保存しない。画面を閉じれば消える。
 *
 * まとめて調べるので、文をまたいだ文脈を使える：
 * 「来週月曜が締め切りです」「レポート出しといて」のように期限が前の文にあっても補える。
 *
 * 文は聞いた日と一緒に覚え、期限はその日を基準に計算する。
 * 23:58 に聞いた「明日まで」を 0:03 にまとめても、期限がずれない（issue #60）。
 */
class SentenceBatch {

    /** 聞いた文と、聞いた日・時刻（時刻は候補のカードに出す、#62） */
    data class Heard(val text: String, val on: LocalDate, val at: LocalDateTime = on.atStartOfDay())

    private val pending = mutableListOf<Heard>()

    /** 前回まとめた最後の数文。次の束の期限を補うためだけに使う */
    private var tail: List<Heard> = emptyList()

    val size: Int get() = pending.size

    fun add(text: String, at: LocalDateTime = LocalDateTime.now()) {
        pending += Heard(text, at.toLocalDate(), at)
    }

    /**
     * ためた文を取り出す。
     * @return 前回の終わりの文（文脈用）と、今回調べる文
     */
    fun drain(): Pair<List<Heard>, List<Heard>> {
        val context = tail
        val sentences = pending.toList()
        pending.clear()
        tail = (context + sentences).takeLast(CONTEXT_SENTENCES)
        return context to sentences
    }

    companion object {
        /** 「あと卵もね」「関数電卓も」のような、前の ToDo の続き（#80） */
        private val CONTINUATION = Regex("^(?:あと|それと|それから|あっ?)?[、,\\s　]*([^、。]{1,10}?)も(?:ね|よ|お願い|頼む)?[。！!]?$")

        /** 題名の終わりの動作（「牛乳を買う」→「買う」、「白衣持ってくる」→「持ってくる」） */
        private val TITLE_VERB = Regex("(?:を)([^を]+)$|([一-龯]?[ぁ-ゖ]+)$")

        /**
         * 候補でない短い文が「あと卵もね」のような直前の候補の続きなら、同じ動作の候補にする（#80）。
         * 期限とタグも直前の候補から引き継ぐ。
         */
        fun continueFromPrevious(sentences: List<Heard>, todos: List<Todo?>): List<Todo?> {
            val out = todos.toMutableList()
            for (i in 1 until sentences.size) {
                val prev = out[i - 1] ?: continue
                if (out[i] != null) continue
                val m = CONTINUATION.matchEntire(sentences[i].text.trim()) ?: continue
                val verb = TITLE_VERB.find(prev.title)?.let { v -> v.groupValues[1].ifEmpty { v.groupValues[2] } } ?: continue
                out[i] = prev.copy(title = "${m.groupValues[1]}を$verb", sourceText = sentences[i].text)
            }
            return out
        }

        /** 期限を探しにいく前の文の数 */
        const val CONTEXT_SENTENCES = 2

        /**
         * 期限のない候補に、近くの文の期限を補う。直前の文、その前の文、直後の文の順に探す。
         * 借りるのは、候補になっていない文（期限を伝えるだけの文・ただの話）からだけ。
         * ほかの ToDo の期限は借りない（「レポート出さなきゃ」「明日までに洗濯しないと」で、
         * レポートに洗濯の期限が付いていた、#70）。
         * @param sentences 文脈用の文も含めたすべての文
         * @param todos sentences と同じ並びの抽出結果（候補にならなかった文は null）
         * @param findDate 文から期限を探す（その文を聞いた日を基準にする）
         */
        fun fillDueFromContext(
            sentences: List<Heard>,
            todos: List<Todo?>,
            findDate: (Heard) -> LocalDate?,
        ): List<Todo?> = todos.mapIndexed { i, todo ->
            if (todo == null || todo.due != null) return@mapIndexed todo
            val due = listOf(i - 1, i - 2, i + 1)
                .filter { it in sentences.indices && todos.getOrNull(it) == null }
                .firstNotNullOfOrNull { findDate(sentences[it]) }
            if (due != null) todo.copy(due = due) else todo
        }
    }
}
