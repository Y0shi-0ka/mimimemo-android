package jp.mimimemo

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import jp.mimimemo.databinding.ItemTodoBinding
import java.time.LocalDate

/**
 * ToDo をカードで一覧表示する。
 * @param onDoneChanged null のときはチェックボックスを出さない
 */
class TodoAdapter(
    private val onClick: (Todo) -> Unit,
    private val onDoneChanged: ((Todo, Boolean) -> Unit)? = null,
) : ListAdapter<Todo, TodoAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemTodoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTodoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

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

        b.textDue.text = DueFormatter.format(todo.due, today)
        val urgent = !todo.done && todo.due != null && !todo.due.isAfter(today.plusDays(1))
        b.textDue.setTextColor(
            MaterialColors.getColor(
                b.textDue,
                if (urgent) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        b.textTag.text = todo.tag

        b.textSource.isVisible = !todo.sourceText.isNullOrBlank()
        b.textSource.text = b.root.context.getString(R.string.source_quote, todo.sourceText.orEmpty())

        b.checkDone.isVisible = onDoneChanged != null
        b.checkDone.isChecked = todo.done
        b.checkDone.setOnClickListener { onDoneChanged?.invoke(todo, b.checkDone.isChecked) }
        b.root.setOnClickListener { onClick(todo) }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Todo>() {
            override fun areItemsTheSame(oldItem: Todo, newItem: Todo) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Todo, newItem: Todo) = oldItem == newItem
        }
    }
}
