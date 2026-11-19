package jp.mimimemo

import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * 文字起こしした1文から ToDo の候補を取り出す（機能2）。
 *
 * 1. キーワードでタスクを含みそうな文だけに絞り込む（isCandidate）
 * 2. ローカルAIが使えればそれで取り出し、使えなければルールで取り出す（extractByRule）
 * 3. 期限の表現は DateParser で具体的な日付にする
 */
class TodoExtractor(
    private val keywords: () -> List<String>,
    private val ai: LocalAiClient = UnavailableLocalAiClient,
    private val parser: DateParser = DateParser(),
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    /**
     * ToDo の候補が見つかれば返し、見つからなければ null を返す。
     * @param on 「明日」などを数える基準の日。聞いた日を渡す（issue #60）
     */
    suspend fun extract(sentence: String, on: LocalDate = today()): Todo? {
        val text = sentence.trim()
        if (!isCandidate(text)) return null
        // 「来週月曜が提出の日です」のように期限を伝えるだけの文は候補にしない（issue #39）。
        // その期限は、5分まとめで前後の文に補う手がかりとして使われる
        if (isDateNotice(text, on)) return null

        if (ai.isAvailable()) {
            val result = try {
                ai.extractTask(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AI の失敗はルール抽出で補う
                return extractByRule(text, on)
            }
            if (result == null || result.title.isBlank()) return null
            val base = on
            return Todo(
                title = result.title.trim(),
                due = result.dueText?.let { parser.parse(it, base) } ?: parser.parse(text, base),
                tag = result.tag?.takeIf { it.isNotBlank() } ?: guessTag(text),
                sourceText = text,
            )
        }
        return extractByRule(text, on)
    }

    /**
     * 期限を伝えるだけの文か。日付を取り除くと「提出日」「締め切り」などしか残らない文をそうみなす。
     * 「レポートの締め切りは金曜」のように、やることが残る文は含めない。
     */
    internal fun isDateNotice(sentence: String, on: LocalDate = today()): Boolean {
        val match = parser.find(sentence, on) ?: return false
        var core = match.text.removeRange(match.range).replace(NOTICE_NOISE, "")
        var prev: String
        do {
            prev = core
            core = core.replace(NOTICE_COPULA, "").trim(*NOTICE_PARTICLES)
        } while (core != prev)
        return core.replace("の日", "日") in NOTICE_WORDS
    }

    /**
     * ToDo になりそうな文か。次のどれかに当てはまれば候補にする（issue #66）。
     * 1. キーワード辞書の言葉を含む
     * 2. 依頼・予定・義務の言い方を含む（しなきゃ・しないと・予定・〜ておいて など。辞書がなくても拾う）
     * 3. 期限の言い方と、動作の言い方（する・出す・行く・ある…）がそろう（「明日レポート出す」）
     */
    internal fun isCandidate(sentence: String): Boolean {
        if (sentence.isBlank()) return false
        val body = sentence.trim().trimEnd(*TRAILING_PUNCT).replace(TRAILING_PARTICLE_END, "")
        val task = TASK_EXPRESSION.containsMatchIn(body) || TASK_ENDING.containsMatchIn(body) ||
            // 「返してね」「出してね、先生怒ってた」：「〜てね」の頼み方（#71）。文末の「ね」を落とす前の文で見る
            REQUEST_NE.containsMatchIn(sentence)
        // 過去のこと（「課題も終わった」）と質問（「何の課題？」）は、依頼・義務の言い方がなければ拾わない（#80）
        if (!task && (PAST_ENDING.containsMatchIn(body) || QUESTION.containsMatchIn(body))) return false
        if (task || keywords().any { it.isNotBlank() && sentence.contains(it) }) return true
        // 自分が引き受ける言い方（「私がまとめるね」「俺は図を作るわ」）
        if (COMMITMENT.containsMatchIn(body)) return true
        return ACTION_ENDING.containsMatchIn(body) && parser.find(sentence, today()) != null
    }

    /**
     * 一覧の入力欄に打った1行から ToDo をつくる（キーワードでの絞り込みはしない）。
     * 「明日までにレポート提出」なら、タイトル「レポート提出」・期限・タグを埋める。
     */
    fun fromManualInput(text: String): Todo = extractByRule(text).copy(sourceText = null)

    /** キーワードと日付表現のルールだけで取り出す。 */
    internal fun extractByRule(sentence: String, on: LocalDate = today()): Todo {
        val text = sentence.trim()
        val match = parser.find(text, on)
        return Todo(
            // 日付が見つかったときの範囲は、漢数字を直したあとの文での位置
            title = makeTitle(match?.text ?: text, match?.range),
            due = match?.date,
            tag = guessTag(text),
            sourceText = text,
        )
    }

    /** 文から日付表現・前置き・文末の依頼表現を取り除いて、ToDo のタイトルにする。 */
    internal fun makeTitle(sentence: String, dateRange: IntRange?): String {
        var s = sentence
        if (dateRange != null) {
            // 日付表現の直後の「までに」「の」なども一緒に取り除く
            val rest = s.substring(dateRange.last + 1)
            val tail = AFTER_DATE.find(rest)?.value?.length ?: 0
            s = s.removeRange(dateRange.first, dateRange.last + 1 + tail)
            // 文頭の日付を取り除いたとき、続く助詞を残さない（「来週月曜が〜」→「〜」、issue #39）
            if (dateRange.first == 0) s = s.replace(LEADING_PARTICLE, "")
        }
        s = s.trim().replace(LEADING_FILLER, "").trimStart(*SEPARATORS)
        // 前置き（そういえば・やばい・うん、あ、）と、誰が言ったか・誰がやるか（先生が・私が）を外す（#80）
        s = s.replace(LEADING_INTERJECTION, "").replace(PERSON, "").trimStart(*SEPARATORS)
        // 文末の引用（〜って言ってた・〜って頼まれてた）を外す。残った命令の形（出せ）は「出す」に直す
        QUOTE_TAIL.find(s)?.let { m ->
            s = s.substring(0, m.range.first)
            s = s.replace(IMPERATIVE) { im -> im.groupValues[1] + (E_TO_U[im.groupValues[2][0]] ?: im.groupValues[2]) }
        }

        var prev: String
        do {
            prev = s
            s = s.trimEnd(*TRAILING_PUNCT).replace(TRAILING_POLITE, "")
        } while (s != prev)

        // 「〜する予定です」「〜するつもり」→「〜する」、「出さなきゃ」「しないと」→「出す」「する」（#66）
        s = s.replace(PLAN_TAIL, "")
        OBLIGATION.find(s)?.let { m -> s = s.substring(0, m.range.first) + toDictionaryForm(m.groupValues[1]) }

        for ((pattern, replacement) in REQUEST_FORMS) {
            if (pattern.containsMatchIn(s)) {
                s = s.replace(pattern, replacement)
                break
            }
        }
        s = s.trim().trimStart(*SEPARATORS)
        return s.ifBlank { sentence.trim().trimEnd(*TRAILING_PUNCT) }
    }

    /** 「出さ」「し」「見」など義務の言い方の前の形を、辞書形（出す・する・見る）に直す */
    private fun toDictionaryForm(stem: String): String {
        if (stem.isEmpty()) return stem
        val last = stem.last()
        val head = stem.dropLast(1)
        return when (last) {
            'し' -> "${head}する"
            'こ' -> "${head}くる"
            else -> A_TO_U[last]?.let { head + it } ?: "${stem}る"
        }
    }

    /** 文に含まれる言葉から、科目・活動のタグを推定する。 */
    internal fun guessTag(text: String): String =
        TAG_RULES.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.first ?: Todo.TAG_OTHER

    companion object {
        /**
         * 音声認識に渡す語彙のヒント（issue #22）。
         * キーワード辞書の言葉と、タグの推定に使う言葉（レポート・ゼミ・シフトなど）を渡して聞き取りやすくする。
         */
        fun vocabulary(keywords: List<String>): List<String> =
            (keywords + TAG_RULES.flatMap { it.second }).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** キーワード辞書の初期値（設定画面で追加・削除できる） */
        val DEFAULT_KEYWORDS = listOf(
            "までに", "締め切り", "締切", "提出", "しといて", "しておいて",
            "持ってきて", "持って来て", "作っといて", "作っておいて",
            "忘れずに", "忘れないで", "宿題", "課題", "お願い", "準備して", "用意して",
        )

        /**
         * キーワード辞書の「おすすめ」に出す、よく使う依頼の言い方（issue #51）。
         * 辞書にないものだけを出し、タップで足せるようにする（初期値から消したものも出る）
         */
        val SUGGESTED_KEYWORDS = listOf(
            "やっといて", "やっておいて", "出しといて", "確認して", "読んでおいて", "調べといて",
            "連絡して", "予約して", "買っといて", "送っといて", "見といて", "覚えといて",
        )

        /** 依頼・予定・義務の言い方（#66）。辞書になくても候補にする */
        private val TASK_EXPRESSION = Regex(
            "なきゃ|なくちゃ|ないと(?:いけない|ダメ|だめ)?$|なければ(?:いけない|ならない)|ないといけない|必要がある|" +
                "[えけせてねめれげべろ]って(?:言って|言われ)|ように(?:言われ|って)|予定|つもり|て(?:ください|下さい|くれる|くれない|ほしい|欲しい|もらえる|もらっていい)|ておいて|といて|でおいて|頼む|頼んだ|頼まれ|やっといて|" +
                // 「〜しないとまずい」「買ってきてだって」（伝言）（#71）
                "ないと(?:まずい|やばい|困る)|(?:てきて|できて|して|(?<!にと)って|んで)(?:ほしい)?だって$",
        )

        /** 文の終わりにだけ見る言い方（文の途中の「ちょっとくらい」などで誤って拾わないように） */
        private val TASK_ENDING = Regex(
            "(?:ておく|とく|ておきます|ときます|でおく|どく|でおきます|てきて|できて|" +
                // 「予約しとこう」「書いておこう」（自分に言う、#71）
                "ておこう|とこう|でおこう|どこう)$",
        )

        /** 「〜てね」の頼み方（#71）。「だってね」「ですってね」（聞いた話）は除く */
        private val REQUEST_NE = Regex("(?:して|(?<!だっ)(?<!すっ)[っいき]て|[んい]で)ね")

        /** 過去のこと（依頼・義務の言い方がなければ拾わない、#80） */
        private val PAST_ENDING = Regex("(?:た|だ|ました|でした|ちゃった|じゃった)(?:よ|ね|わ|けど|し)?$")

        /** 質問（依頼・義務の言い方がなければ拾わない、#80） */
        private val QUESTION = Regex("(?:何|なに|どこ|いつ|誰|だれ|どう|だっけ)[^、。]{0,8}$")

        /** 自分が引き受ける言い方：「私がまとめる」「俺は図を作る」（#80） */
        private val COMMITMENT = Regex("(?:私|わたし|俺|僕|ぼく|自分|うち)(?:が|は)[^、。]{1,20}[うくすつぬぶむるぐ]$")

        /** 題名から外す前置き（#80） */
        private val LEADING_INTERJECTION = Regex(
            "^(?:(?:そういえば|そう言えば|やばい|やべ|あっ?|うん|ねえ|ねぇ|ちょっと|まあ|わかった|了解|オッケー|じゃ|それで)[、,\\s　]*)+",
        )

        /** 題名から外す、言った人・やる人（#80） */
        private val PERSON = Regex(
            "(?:先生|教授|店長|先輩|部長|お母さん|お父さん|母|父|私|わたし|俺|僕|ぼく|自分|うち)(?:が|は|に|から)",
        )

        /** 文末の引用（#80） */
        private val QUOTE_TAIL = Regex("(?:って|と)(?:[^、。]{0,10}に)?(?:言って(?:た|いた|ました)?|言われ[^、。]*|頼まれ[^、。]*|聞いた)$|(?:だ)?って$")

        /** 引用の中の命令の形（出せ・書け）。2つ目のかたまりがエ段 */
        private val IMPERATIVE = Regex("([一-龯])([えけせてねめれげべ])$")

        private val E_TO_U = mapOf(
            'え' to "う", 'け' to "く", 'せ' to "す", 'て' to "つ", 'ね' to "ぬ",
            'め' to "む", 'れ' to "る", 'げ' to "ぐ", 'べ' to "ぶ",
        )

        /** 期限と一緒にあれば候補にする、動作の言い方（文の終わり） */
        private val ACTION_ENDING = Regex(
            "(?:する|します|やる|やります|出す|出します|行く|行きます|来る|来ます|持っていく|持ってく|" +
                "買う|送る|読む|書く|見る|調べる|返す|払う|ある|あります|集合|提出)$",
        )

        /** 文末の「ね」「よ」「な」「わ」など（判定の前に落とす） */
        private val TRAILING_PARTICLE_END = Regex("(?:よね|ね|よ|な|わ|ぞ|ぜ)$")

        /** 「予定です」「つもり」など、題名から落とす文末 */
        private val PLAN_TAIL = Regex("(?:予定|つもり)(?:です|だ|でした)?$")

        /** 義務の言い方。1つ目のかたまりは「なきゃ」などの前の形 */
        private val OBLIGATION = Regex(
            "([ぁ-ゖ一-龯]{0,1}[かがさたなばまらわしこ見寝出来着居])(?:なきゃ|なくちゃ|ないと|なければ)(?:いけない|ならない|ダメ|だめ|まずい|やばい|困る)?$",
        )

        /** 五段の「ない」の前の形（ア段）→ 辞書形の終わり（ウ段） */
        private val A_TO_U = mapOf(
            'か' to "く", 'が' to "ぐ", 'さ' to "す", 'た' to "つ", 'な' to "ぬ",
            'ば' to "ぶ", 'ま' to "む", 'ら' to "る", 'わ' to "う",
        )

        private val SEPARATORS = charArrayOf('、', ',', ' ', '　')
        private val TRAILING_PUNCT = charArrayOf('。', '．', '.', '!', '！', '?', '？', '、', ',', ' ', '　', '〜')

        private val LEADING_PARTICLE = Regex("^[がはをでも](?=.)")

        /** 期限を伝えるだけの文で、日付のほかに残ってよい言葉 */
        private val NOTICE_WORDS = setOf("提出", "提出日", "締め切り", "締め切り日", "締切", "締切日", "期限", "期日")
        private val NOTICE_NOISE = Regex("[、。，．,.!！?？\\s　]")
        private val NOTICE_COPULA = Regex("(?:です|でした|だ|になります|になってます|ね|よ|って|で)$")
        private val NOTICE_PARTICLES = charArrayOf('が', 'は', 'の', 'も')

        private val AFTER_DATE = Regex("^(?:までに|まで|中に|には|に|の|は)[、,\\s　]*")

        private val LEADING_FILLER =
            Regex("^(?:(?:えー+と?|ええと|えっと|あのー*|あー+|じゃあ|では|それでは|それと|あと)[、,\\s　]*|(?:みなさん|皆さん|みんな)[、,\\s　]+)+")

        private val TRAILING_POLITE =
            Regex("(?:ねー*|よー*|な|わ|ください|下さい|お願いします|お願い|おねがい|ほしい|欲しい|(?:くれる|くれない|もらえる|もらえない|もらっていい)か?)$")

        /** 依頼の言い方を「〜する」の形に直す。上から順に最初に当てはまったものだけ使う */
        private val REQUEST_FORMS = listOf(
            Regex("([一-龯ァ-ヶー]{2,})(?:しといて|しておいて|してきて|してもらえる|してくれる|してくれない|して)$") to "$1する",
            Regex("([一-龯ァ-ヶー]{2,})(?:しとこう|しておこう)$") to "$1する",
            Regex("(?:読んでおいて|読んどいて|読んでおく|読んどく)$") to "読む",
            // 「返して」「出して」→「返す」「出す」（#71）
            Regex("([返出消貸話探直渡回試戻押干])して$") to "$1す",
            Regex("([わ取帰入切送作守座乗止])って$") to "$1る",
            Regex("(?:やっといて|やっておいて)$") to "やる",
            Regex("(?:作っといて|作っておいて|作って)$") to "作る",
            Regex("(?:持ってきて|持って来て)$") to "持ってくる",
            Regex("ておいて$") to "ておく",
            Regex("といて$") to "とく",
            Regex("(?:ておこう)$") to "ておく",
            Regex("とこう$") to "とく",
            Regex("てきて$") to "てくる",
        )

        private val TAG_RULES = listOf(
            "研究室" to listOf("研究室", "ゼミ", "教授", "論文", "輪講", "卒論", "卒研"),
            "バイト" to listOf("バイト", "シフト", "店長", "勤務"),
            "グループ" to listOf("グループ", "チーム", "班", "サークル", "メンバー", "みんなで"),
            "授業" to listOf("授業", "講義", "レポート", "課題", "宿題", "テスト", "試験", "教科書", "出席", "履修", "演習", "実験"),
        )
    }
}
