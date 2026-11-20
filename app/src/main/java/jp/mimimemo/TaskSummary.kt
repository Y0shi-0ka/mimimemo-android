package jp.mimimemo

/**
 * 題名を「やることの要約」に整える（issue #144）。
 * 聞き取った文から日付や依頼の言い方を外しただけの題名には、前置き・理由・丁寧な終わり方が残り、
 * 長い文はほとんどそのまま題名になる。ここで短く言い切りの形にそろえる。元の発言は別に残す。
 */
object TaskSummary {

    /** これより長い題名は、最後のまとまりを中心に縮める */
    const val MAX_LENGTH = 20

    /** 理由・条件の部分を外すのは、後ろにこの文字数以上残るときだけ（「確認する」だけにしない） */
    private const val MIN_REST = 6

    fun of(title: String): String {
        val original = title.trim()
        var s = original.replace(PREFACE, "").replace(FILLER, "").trimStart(*SEPARATORS, 'の')

        // 言いかけの終わり（「〜なのと」「〜とか」）を落とし、義務の言い方（行かなきゃダメ）を言い切りにする
        var prev: String
        do {
            prev = s
            s = s.trimEnd(*SEPARATORS).replace(TRAILING_FRAGMENT, "")
        } while (s != prev)
        OBLIGATION.find(s)?.let { m ->
            val stem = m.groupValues[1]
            s = s.substring(0, m.range.first) + when (val c = stem.last()) {
                'し' -> stem.dropLast(1) + "する"
                else -> A_TO_U[c]?.let { stem.dropLast(1) + it } ?: (stem + "る")
            }
        }

        for ((pattern, replace) in ENDINGS) {
            val m = pattern.find(s) ?: continue
            s = s.substring(0, m.range.first) + replace(m)
            break
        }

        // 「論文も読む」→「論文を読む」
        s = s.replace(MO_BEFORE_VERB, "$1を")

        if (s.length > MAX_LENGTH) s = shorten(s)
        return s.trim().ifBlank { original }
    }

    /** 動作が入っている最後のまとまりを残し、入るだけ前のまとまりを足す */
    private fun shorten(title: String): String {
        var s = title
        // 理由・条件の部分を外す（「〜だから」「〜ので」）。短い題名では意味が抜けるので、長いときだけ
        REASON.findAll(s).lastOrNull()?.let { m ->
            val rest = s.substring(m.range.last + 1).trimStart(*SEPARATORS)
            if (rest.length >= MIN_REST) s = rest
        }
        if (s.length <= MAX_LENGTH) return s
        val clauses = s.split(*SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        var out = clauses.lastOrNull() ?: return s
        for (c in clauses.dropLast(1).asReversed()) {
            if (c.length + 1 + out.length > MAX_LENGTH) break
            out = "$c、$out"
        }
        if (out.length <= MAX_LENGTH) return out
        // 1つのまとまりが長いときは、助詞の後ろで切って後ろ側（動作に近いほう）を残す
        val cut = PARTICLE.findAll(out).map { it.range.last + 1 }.firstOrNull { out.length - it <= MAX_LENGTH }
        return if (cut != null && out.length - cut >= MIN_REST) out.substring(cut) else "…" + out.takeLast(MAX_LENGTH - 1)
    }

    private val SEPARATORS = charArrayOf('、', ',', '。', ' ', '　')

    private val PREFACE = Regex("^(?:悪いんだけど|悪いけど|ごめん(?:ね)?|すみません|すいません|申し訳ないけど|できれば|なるべく)[、,\\s　]*")

    /** 話の途中に入る言葉（えっと・なんか）。どこにあっても外す */
    private val FILLER = Regex("(?:えっと|えーっと|えーと|ええと|あのー+|なんか)[、,\\s　]*")

    /** 言いかけで終わった部分 */
    private val TRAILING_FRAGMENT = Regex("(?:なのと|のと|とか|なの)$")

    /** 義務の言い方。1つ目のかたまりは「なきゃ」の前の形（行か・出さ・し） */
    // 前の形は五段（行か・出さ）・する（し）のほか、見せ・食べ・来のような形（＋る）もある（#150、実機で「見せないといけない」）
    private val OBLIGATION = Regex("([一-龯ぁ-ゖ]?[一-龯ぁ-ゖ])(?:なきゃ|なくちゃ|ないと)(?:ダメ|だめ|いけない|ならない)$")

    private val A_TO_U = mapOf(
        'か' to "く", 'が' to "ぐ", 'さ' to "す", 'た' to "つ", 'な' to "ぬ",
        'ば' to "ぶ", 'ま' to "む", 'ら' to "る", 'わ' to "う",
    )

    private val REASON =Regex("(?:だから|から|ので|んだけど|けれども|けれど|けど)[、,\\s　]*")

    /** 動作の前の「も」（名詞のあと、終わりまで短い） */
    private val MO_BEFORE_VERB = Regex("([一-龯ァ-ヶー])も(?=[^、]{1,6}$)")

    private val PARTICLE = Regex("[のをにでとがは]")

    /** 「う」で終わる五段の動詞（買って・払っとく → 買う・払う） */
    private const val U_VERB = "買払使洗言会習歌拾貰吸笑"
    /** 「つ」で終わる五段の動詞 */
    private const val TSU_VERB = "待持立打勝"
    /** 「〜す」で終わる動詞（出しとく → 出す） */
    private const val SU_VERB = "出返消貸話探直渡回試戻押干済壊残隠"

    private val E_ROW_TO_U = mapOf(
        'き' to "く", 'ぎ' to "ぐ", 'し' to "す", 'ち' to "つ", 'に' to "ぬ",
        'び' to "ぶ", 'み' to "む", 'り' to "る", 'い' to "う",
    )

    /** 終わりの形を言い切りにそろえる。上から最初に当てはまったものだけ使う */
    private val ENDINGS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // 「朝出しといて」が「朝出する」になったもの。「提出する」などはそのまま
        Regex("(?<![提輸演進支検放排外選抽算露創])出する$") to { _ -> "出す" },
        // 出しとく・出しておく → 出す、連絡しとく → 連絡する
        Regex("([$SU_VERB])し(?:とく|ておく|とこう|ておこう)$") to { m -> m.groupValues[1] + "す" },
        Regex("し(?:とく|ておく|とこう|ておこう)$") to { _ -> "する" },
        // 書いとく → 書く、急いどく → 急ぐ、読んどく → 読む
        Regex("い(?:とく|ておく)$") to { _ -> "く" },
        Regex("い(?:どく|でおく)$") to { _ -> "ぐ" },
        Regex("ん(?:どく|でおく)$") to { _ -> "む" },
        // 買っとく → 買う、待っとく → 待つ、作っとく → 作る
        Regex("([一-龯])っ(?:とく|ておく)$") to { m -> m.groupValues[1] + uTsuRu(m.groupValues[1][0]) },
        // 入れとく・調べておく → 入れる・調べる
        Regex("([ぁ-ゖ一-龯])(?:とく|ておく)$") to { m -> m.groupValues[1] + "る" },
        // 共有します → 共有する、書きます → 書く
        Regex("します$") to { _ -> "する" },
        Regex("ています$") to { _ -> "ている" },
        Regex("([ぁ-ゖ])ます$") to { m -> E_ROW_TO_U[m.groupValues[1][0]] ?: (m.groupValues[1] + "る") },
        // 払って → 払う（依頼の形が残ったもの）
        Regex("([一-龯])って$") to { m -> m.groupValues[1] + uTsuRu(m.groupValues[1][0]) },
        // ていしゅつして → ていしゅつする
        Regex("して$") to { _ -> "する" },
        // 忘れないで → 忘れない
        Regex("ないで$") to { _ -> "ない" },
    )

    private fun uTsuRu(c: Char): String = when {
        c == '行' -> "く"
        c in U_VERB -> "う"
        c in TSU_VERB -> "つ"
        else -> "る"
    }
}
