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

    private fun key(todo: Todo): String =
        todo.title.filterNot { it.isWhitespace() || it in IGNORED } + "|" + todo.due

    private companion object {
        const val IGNORED = "、。，．,.!！?？・"
    }
}
