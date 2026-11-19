package jp.mimimemo

import java.time.LocalDateTime

/**
 * ほかのアプリから共有された文章を ToDo 候補にする（issue #93）。
 * 聞き取りと同じ流れ（文ごとの判定 → 直前の候補の続き → 前後の文の期限）で探す。
 * 何も見つからなければ、文章全体を1件の候補にする（共有したのは ToDo にしたいからなので）。決めるのはカードで。
 */
object SharedText {

    private const val MAX_TITLE = 40

    suspend fun findCandidates(
        text: String,
        extractor: TodoExtractor,
        parser: DateParser = DateParser(),
        now: LocalDateTime = LocalDateTime.now(),
    ): List<Todo> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val heard = RecognizedText.splitSentences(trimmed).map { SentenceBatch.Heard(it, now.toLocalDate(), now) }
        val found = SentenceBatch.continueFromPrevious(heard, heard.map { extractor.extract(it.text, it.on) })
        val filled = SentenceBatch.fillDueFromContext(heard, found) { parser.parse(it.text, it.on) }
        val todos = filled.filterNotNull().map { it.copy(createdAt = now) }
        if (todos.isNotEmpty()) return todos
        val oneLine = trimmed.replace(Regex("\\s+"), " ")
        return listOf(
            Todo(
                title = if (oneLine.length > MAX_TITLE) oneLine.take(MAX_TITLE) + "…" else oneLine,
                due = parser.parse(oneLine, now.toLocalDate()),
                tag = extractor.guessTag(oneLine),
                sourceText = oneLine,
                createdAt = now,
            ),
        )
    }
}
