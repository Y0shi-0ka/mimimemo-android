package jp.mimimemo

/**
 * 聞き取り中に、同じ内容の候補を続けて出さないようにする（issue #22）。
 * 言い直しや聞き返しで同じ文が2回来ても、候補は1つだけにする。
 * 追加した・却下した候補も覚えておくので、一度却下したものがまた出ることもない。
 */
class CandidateDeduper {

    private val seen = HashSet<String>()

    /** はじめて見る候補なら true を返して覚える */
    fun isNew(todo: Todo): Boolean = seen.add(key(todo))

    private fun key(todo: Todo): String =
        todo.title.filterNot { it.isWhitespace() || it in IGNORED } + "|" + todo.due

    private companion object {
        const val IGNORED = "、。，．,.!！?？・"
    }
}
