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

    /** ToDo の候補が見つかれば返し、見つからなければ null を返す。 */
    suspend fun extract(sentence: String): Todo? {
        val text = sentence.trim()
        if (!isCandidate(text)) return null

        if (ai.isAvailable()) {
            val result = try {
                ai.extractTask(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AI の失敗はルール抽出で補う
                return extractByRule(text)
            }
            if (result == null || result.title.isBlank()) return null
            val base = today()
            return Todo(
                title = result.title.trim(),
                due = result.dueText?.let { parser.parse(it, base) } ?: parser.parse(text, base),
                tag = result.tag?.takeIf { it.isNotBlank() } ?: guessTag(text),
                sourceText = text,
            )
        }
        return extractByRule(text)
    }

    internal fun isCandidate(sentence: String): Boolean {
        if (sentence.isBlank()) return false
        return keywords().any { it.isNotBlank() && sentence.contains(it) }
    }

    /**
     * 一覧の入力欄に打った1行から ToDo をつくる（キーワードでの絞り込みはしない）。
     * 「明日までにレポート提出」なら、タイトル「レポート提出」・期限・タグを埋める。
     */
    fun fromManualInput(text: String): Todo = extractByRule(text).copy(sourceText = null)

    /** キーワードと日付表現のルールだけで取り出す。 */
    internal fun extractByRule(sentence: String): Todo {
        val text = sentence.trim()
        val match = parser.find(text, today())
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
        }
        s = s.trim().replace(LEADING_FILLER, "").trimStart(*SEPARATORS)

        var prev: String
        do {
            prev = s
            s = s.trimEnd(*TRAILING_PUNCT).replace(TRAILING_POLITE, "")
        } while (s != prev)

        for ((pattern, replacement) in REQUEST_FORMS) {
            if (pattern.containsMatchIn(s)) {
                s = s.replace(pattern, replacement)
                break
            }
        }
        s = s.trim().trimStart(*SEPARATORS)
        return s.ifBlank { sentence.trim().trimEnd(*TRAILING_PUNCT) }
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

        private val SEPARATORS = charArrayOf('、', ',', ' ', '　')
        private val TRAILING_PUNCT = charArrayOf('。', '．', '.', '!', '！', '?', '？', '、', ',', ' ', '　', '〜')

        private val AFTER_DATE = Regex("^(?:までに|まで|中に|には|に|の|は)[、,\\s　]*")

        private val LEADING_FILLER =
            Regex("^(?:(?:えー+と?|ええと|えっと|あのー*|あー+|じゃあ|では|それでは|それと|あと|みなさん|皆さん|みんな)[、,\\s　]*)+")

        private val TRAILING_POLITE =
            Regex("(?:ねー*|よー*|な|ください|下さい|お願いします|お願い|おねがい|ほしい|欲しい)$")

        /** 依頼の言い方を「〜する」の形に直す。上から順に最初に当てはまったものだけ使う */
        private val REQUEST_FORMS = listOf(
            Regex("([一-龯ァ-ヶー]{2,})(?:しといて|しておいて|してきて|して)$") to "$1する",
            Regex("(?:やっといて|やっておいて)$") to "やる",
            Regex("(?:作っといて|作っておいて|作って)$") to "作る",
            Regex("(?:持ってきて|持って来て)$") to "持ってくる",
            Regex("ておいて$") to "ておく",
            Regex("といて$") to "とく",
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
