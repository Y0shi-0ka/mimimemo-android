package jp.mimimemo

/**
 * 端末内の AI に、まとめた会話から ToDo を選んでもらうための問いと、答えの読み方（issue #68）。
 * 小さなモデルでも崩れにくいよう、JSON ではなく「番号|やること|期限の言葉|タグ」の1行ずつで答えてもらう。
 */
object AiTaskPrompt {

    /** AI が選んだ ToDo。index は渡した文の番号（0 はじまり） */
    data class Task(val index: Int, val title: String, val dueText: String?, val tag: String?)

    val SYSTEM = """
        あなたは、会話の文字起こしから「聞いている人がこれからやること（ToDo）」だけを見つける係です。
        選ぶもの：依頼、頼まれごと、約束、予定、締め切り、持ち物、自分で「やらなきゃ」と言ったこと。
        選ばないもの：雑談、感想、うわさ話、過去の出来事、もう終わったこと、他人だけの予定。
        文字起こしには聞き間違いがあります。前後の文の流れから意味を考えてください。
        答えは1件につき1行で、次の形だけを書きます。説明は書きません。
        番号|やること|期限の言葉|タグ
        ・やることは「〜する」「〜を出す」のような短い形（20文字まで）
        ・期限の言葉は、その文に書いてある言い方だけ。書いてなければ空にする
        ・タグは 授業、研究室、グループ、バイト、その他 のどれか
        ToDo が1つもなければ「なし」とだけ書きます。
        下の見本は形を示すためのもので、内容はまねしません。

        見本1
        1: お腹すいたね
        2: 夕飯の材料、帰りに買ってきて
        3: 金曜までにお風呂掃除しといてね
        4: 先週カレー作ったよ
        答え
        2|夕飯の材料を買う||その他
        3|お風呂を掃除する|金曜まで|その他

        見本2
        1: 眠い
        2: 映画おもしろかった
        答え
        なし
    """.trimIndent()

    /** まとめた文に1から番号を振って渡す */
    fun userMessage(sentences: List<String>): String =
        sentences.mapIndexed { i, s -> "${i + 1}: ${s.replace('\n', ' ').trim()}" }.joinToString("\n") + "\n答え"

    private val LINE = Regex("""^\s*(\d+)\s*[|｜]\s*([^|｜]*?)\s*[|｜]\s*([^|｜]*?)\s*(?:[|｜]\s*([^|｜]*?)\s*)?$""")

    /**
     * 答えを読む。読めない行・番号が範囲外の行・やることが空の行は捨てる。同じ番号は最初の1つだけ。
     * @param count 渡した文の数
     */
    fun parse(reply: String, count: Int): List<Task> {
        val seen = HashSet<Int>()
        return reply.lineSequence()
            .map { it.trim().trimStart('-', '・', '*', ' ') }
            .mapNotNull { LINE.matchEntire(it) }
            .mapNotNull { m ->
                val index = m.groupValues[1].toIntOrNull()?.minus(1) ?: return@mapNotNull null
                val title = m.groupValues[2].trim().trimEnd('。', '.')
                if (index !in 0 until count || title.isEmpty() || title == "なし" || !seen.add(index)) return@mapNotNull null
                Task(
                    index = index,
                    title = title.take(MAX_TITLE),
                    dueText = m.groupValues[3].trim().takeIf { it.isNotEmpty() && it != "なし" && it != "-" },
                    tag = m.groupValues.getOrNull(4)?.trim()?.takeIf { it in Todo.TAGS },
                )
            }
            .toList()
    }

    private const val MAX_TITLE = 40
}
