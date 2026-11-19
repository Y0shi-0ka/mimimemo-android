package jp.mimimemo

/**
 * 聞き取り中に、同じ内容の候補を続けて出さないようにする（issue #22）。
 * 言い直しや聞き返しで同じ文が2回来ても、候補は1つだけにする。
 * 追加した・却下した候補も覚えておくので、一度却下したものがまた出ることもない。
 * 常時聞き取り（#44）で何日も動くので、覚えるのは新しい [limit] 件まで（issue #55）。
 */
class CandidateDeduper(private val limit: Int = 500) {

    /** 覚えた順。古いものから捨てる */
    private val seen = LinkedHashSet<String>()

    /** はじめて見る候補なら true を返して覚える */
    fun isNew(todo: Todo): Boolean {
        if (!seen.add(key(todo))) return false
        while (seen.size > limit) seen.remove(seen.first())
        return true
    }

    private fun key(todo: Todo): String = normalizeTitle(todo.title) + "|" + todo.due

    companion object {
        private const val IGNORED = "、。，．,.!！?？・"

        /** 題名の終わりの「する」「をする」などは、あってもなくても同じとみなす */
        private val TITLE_TAIL = Regex("(?:をする|する|をやる|やる)$")

        /**
         * 題名をそろえる。空白・句読点・「を」・終わりの「する」の違いは同じとみなす
         * （「レポート提出する」「レポートを提出」「レポート 提出。」は同じ）。
         */
        fun normalizeTitle(title: String): String =
            title.filterNot { it.isWhitespace() || it in IGNORED }.replace(TITLE_TAIL, "").replace("を", "")

        /**
         * すでに一覧にある（未完了の）ToDo と同じ候補か（#72）。題名が同じで、期限も同じ（または候補に期限がない）なら同じ。
         * 期限だけ違うときは、期限の変更として候補に出す。
         */
        fun isAlreadyListed(candidate: Todo, existing: List<Todo>): Boolean {
            val title = normalizeTitle(candidate.title)
            return existing.any { !it.done && normalizeTitle(it.title) == title && (candidate.due == null || candidate.due == it.due) }
        }
    }
}
