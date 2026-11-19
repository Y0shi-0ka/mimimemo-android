package jp.mimimemo

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jp.mimimemo.databinding.ItemTodoBinding
import java.time.LocalDate

/**
 * ToDo をカードで一覧表示する。
 * やることと期限を主にし、期限がない・タグが「その他」のときはその表示ごと出さない（issue #20）。
 * 一覧では期限の区切りごとに見出しを入れる（issue #49）。
 * @param onDoneChanged null のときはチェックボックスを出さない
 * @param showSource 元の発言も出す（今日のまとめ用）
 */
class TodoAdapter(
    private val onClick: (Todo) -> Unit,
    private val onDoneChanged: ((Todo, Boolean) -> Unit)? = null,
    private val showSource: Boolean = false,
) : ListAdapter<TodoAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    sealed interface Row {
        data class Header(val section: DueSections.Section, val count: Int) : Row
        data class Item(val todo: Todo) : Row
        /** 完了した ToDo の見出し。押すと開閉する（#87） */
        data class DoneHeader(val count: Int, val expanded: Boolean) : Row
    }

    /** 完了の見出しを押したとき */
    var onToggleDone: () -> Unit = {}

    /** タグの札を押したとき（そのタグだけにする、#109）。null なら押せない */
    var onTagClick: ((String) -> Unit)? = null

    class ItemHolder(val binding: ItemTodoBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    /** 区切らずに並べる（今日のまとめ） */
    fun submitTodos(todos: List<Todo>) = submitList(todos.map { Row.Item(it) })

    /**
     * 期限の区切りごとに見出しを入れて並べる（一覧）。
     * 完了したものは、いちばん下の「完了」の見出しの下に、開いているときだけ並べる（#87）。
     */
    fun submitGrouped(
        todos: List<Todo>,
        done: List<Todo> = emptyList(),
        doneExpanded: Boolean = false,
        today: LocalDate = LocalDate.now(),
    ) = submitList(
        DueSections.group(todos, today).flatMap { (section, items) ->
            listOf(Row.Header(section, items.size)) + items.map { Row.Item(it) }
        } + if (done.isEmpty()) {
            emptyList()
        } else {
            listOf(Row.DoneHeader(done.size, doneExpanded)) + if (doneExpanded) done.map { Row.Item(it) } else emptyList()
        }
    )

    /** その位置の ToDo。見出しなら null */
    fun todoAt(position: Int): Todo? = (currentList.getOrNull(position) as? Row.Item)?.todo

    override fun getItemViewType(position: Int): Int = if (getItem(position) is Row.Item) TYPE_ITEM else TYPE_HEADER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_HEADER) {
            return HeaderHolder(inflater.inflate(R.layout.item_section_header, parent, false) as TextView)
        }
        val b = ItemTodoBinding.inflate(inflater, parent, false)
        // 丸いチェックの色は描画リソース側で持つので、テーマの色付けを外す（issue #35）
        b.checkDone.buttonTintList = null
        return ItemHolder(b)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).text.apply {
                setOnClickListener(null)
                isClickable = false
                text = context.getString(R.string.section_header, row.section.label, row.count)
                // 期限切れの見出しだけ急ぎの色にする
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (row.section == DueSections.Section.OVERDUE) R.color.due_urgent_fg else R.color.on_surface_variant,
                    )
                )
            }
            is Row.Item -> bindItem((holder as ItemHolder).binding, row.todo)
            is Row.DoneHeader -> (holder as HeaderHolder).text.apply {
                text = context.getString(
                    if (row.expanded) R.string.done_header_expanded else R.string.done_header_collapsed,
                    row.count,
                )
                setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
                setOnClickListener { onToggleDone() }
            }
        }
    }

    private fun bindItem(b: ItemTodoBinding, todo: Todo) {
        val today = LocalDate.now()

        b.textTitle.text = todo.title
        b.textTitle.paintFlags = if (todo.done) {
            b.textTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            b.textTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        b.textDue.isVisible = todo.due != null
        // 繰り返しなら「毎週」などを添える（#107）
        val dueText = DueFormatter.format(todo.due, today)
        b.textDue.text = todo.repeat?.let { b.root.context.getString(R.string.due_with_repeat, dueText, it.label) } ?: dueText
        // 急ぎ具合で札の色を変える（issue #35）。完了したものは目立たせない
        val (bg, fg) = dueColors(todo, today)
        b.textDue.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(b.root.context, bg))
        b.textDue.setTextColor(ContextCompat.getColor(b.root.context, fg))
        b.root.alpha = if (todo.done) 0.55f else 1f
        b.textTag.isVisible = todo.tag != Todo.TAG_OTHER
        b.textTag.text = todo.tag
        val tagClick = onTagClick
        if (tagClick != null) b.textTag.setOnClickListener { tagClick(todo.tag) } else b.textTag.setOnClickListener(null)
        b.textTag.isClickable = tagClick != null
        // 押すと何が起きるか読み上げる（#123）
        b.textTag.contentDescription = if (tagClick != null) b.root.context.getString(R.string.filter_tag_action, todo.tag) else null
        b.rowMeta.isVisible = b.textDue.isVisible || b.textTag.isVisible

        b.textSource.isVisible = showSource && !todo.sourceText.isNullOrBlank()
        b.textSource.text = b.root.context.getString(R.string.source_quote, todo.sourceText.orEmpty())

        b.checkDone.isVisible = onDoneChanged != null
        b.checkDone.isChecked = todo.done
        b.checkDone.setOnClickListener { onDoneChanged?.invoke(todo, b.checkDone.isChecked) }
        b.root.setOnClickListener { onClick(todo) }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1

        /** 過ぎた・今日・明日は赤、3日以内は橙、それ以外は灰 */
        fun dueColors(todo: Todo, today: LocalDate): Pair<Int, Int> {
            val due = todo.due ?: return R.color.due_normal_bg to R.color.due_normal_fg
            return when {
                todo.done -> R.color.due_normal_bg to R.color.due_normal_fg
                !due.isAfter(today.plusDays(1)) -> R.color.due_urgent_bg to R.color.due_urgent_fg
                !due.isAfter(today.plusDays(3)) -> R.color.due_soon_bg to R.color.due_soon_fg
                else -> R.color.due_normal_bg to R.color.due_normal_fg
            }
        }

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row) = when {
                oldItem is Row.Header && newItem is Row.Header -> oldItem.section == newItem.section
                oldItem is Row.Item && newItem is Row.Item -> oldItem.todo.id == newItem.todo.id
                oldItem is Row.DoneHeader && newItem is Row.DoneHeader -> true
                else -> false
            }
            override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        }
    }
}
