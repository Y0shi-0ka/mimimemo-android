package jp.mimimemo

/** 一覧を送るときの文章（issue #116）。1行1件、期限があれば括弧で添える */
object ListText {
    fun of(todos: List<Todo>): String =
        todos.joinToString("\n") { todo ->
            val due = todo.due?.let { DueFormatter.short(it) }
            val repeat = todo.repeat?.label
            val tail = listOfNotNull(due, repeat).joinToString("・")
            if (tail.isEmpty()) "・${todo.title}" else "・${todo.title}（$tail）"
        }
}
