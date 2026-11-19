package jp.mimimemo

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
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
 * @param onDoneChanged null のときはチェックボックスを出さない
 * @param showSource 元の発言も出す（今日のまとめ用）
 */
class TodoAdapter(
    private val onClick: (Todo) -> Unit,
    private val onDoneChanged: ((Todo, Boolean) -> Unit)? = null,
    private val showSource: Boolean = false,
) : ListAdapter<Todo, TodoAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemTodoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemTodoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // 丸いチェックの色は描画リソース側で持つので、テーマの色付けを外す（issue #35）
        b.checkDone.buttonTintList = null
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val todo = getItem(position)
        val b = holder.binding
        val today = LocalDate.now()

        b.textTitle.text = todo.title
        b.textTitle.paintFlags = if (todo.done) {
            b.textTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            b.textTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        b.textDue.isVisible = todo.due != null
        b.textDue.text = DueFormatter.format(todo.due, today)
        // 急ぎ具合で札の色を変える（issue #35）。完了したものは目立たせない
        val (bg, fg) = dueColors(todo, today)
        b.textDue.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(b.root.context, bg))
        b.textDue.setTextColor(ContextCompat.getColor(b.root.context, fg))
        b.root.alpha = if (todo.done) 0.55f else 1f
        b.textTag.isVisible = todo.tag != Todo.TAG_OTHER
        b.textTag.text = todo.tag
        b.rowMeta.isVisible = b.textDue.isVisible || b.textTag.isVisible

        b.textSource.isVisible = showSource && !todo.sourceText.isNullOrBlank()
        b.textSource.text = b.root.context.getString(R.string.source_quote, todo.sourceText.orEmpty())

        b.checkDone.isVisible = onDoneChanged != null
        b.checkDone.isChecked = todo.done
        b.checkDone.setOnClickListener { onDoneChanged?.invoke(todo, b.checkDone.isChecked) }
        b.root.setOnClickListener { onClick(todo) }
    }

    private companion object {
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

        val DIFF = object : DiffUtil.ItemCallback<Todo>() {
            override fun areItemsTheSame(oldItem: Todo, newItem: Todo) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Todo, newItem: Todo) = oldItem == newItem
        }
    }
}
