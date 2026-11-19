package jp.mimimemo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** スワイプしたときの操作（背景色・アイコン・文字と、スワイプし終えたときの処理） */
class SwipeAction(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @ColorInt val color: Int,
    val onSwiped: (position: Int) -> Unit,
)

/**
 * 一覧の項目を左右にスワイプして操作できるようにする（issue #12）。
 * どの一覧でも同じ見た目・動きになるよう、ここにまとめている。
 *
 * onSwiped では、その項目を一覧から消す（submitList し直す）こと。
 */
object SwipeActions {

    fun attach(
        recyclerView: RecyclerView,
        left: SwipeAction?,
        right: SwipeAction?,
        /** false を返した行はスワイプしない（見出しなど） */
        canSwipe: (RecyclerView.ViewHolder) -> Boolean = { true },
    ): ItemTouchHelper {
        val directions = (if (left != null) ItemTouchHelper.LEFT else 0) or
            (if (right != null) ItemTouchHelper.RIGHT else 0)

        val callback = object : ItemTouchHelper.SimpleCallback(0, directions) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = sp(recyclerView, 15f)
                isFakeBoldText = true
            }
            private val rect = RectF()

            override fun getSwipeDirs(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (canSwipe(viewHolder)) super.getSwipeDirs(rv, viewHolder) else 0

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val action = if (direction == ItemTouchHelper.LEFT) left else right
                action?.onSwiped?.invoke(position)
            }

            override fun onChildDraw(
                c: Canvas,
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                val action = when {
                    dX > 0 -> right
                    dX < 0 -> left
                    else -> null
                }
                if (action != null && actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    drawBackground(c, viewHolder, dX, action)
                }
                super.onChildDraw(c, rv, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            private fun drawBackground(c: Canvas, holder: RecyclerView.ViewHolder, dX: Float, action: SwipeAction) {
                val item = holder.itemView
                val ctx = item.context
                val radius = dp(item, 20f)
                val margin = dp(item, 20f)

                bgPaint.color = action.color
                if (dX > 0) {
                    rect.set(item.left.toFloat(), item.top.toFloat(), item.left + dX, item.bottom.toFloat())
                } else {
                    rect.set(item.right + dX, item.top.toFloat(), item.right.toFloat(), item.bottom.toFloat())
                }
                c.drawRoundRect(rect, radius, radius, bgPaint)

                // アイコンと文字（スワイプした側の端に寄せる）
                val icon = ContextCompat.getDrawable(ctx, action.icon)?.mutate() ?: return
                icon.setTint(0xFFFFFFFF.toInt())
                val size = dp(item, 24f).toInt()
                val label = ctx.getString(action.label)
                val textWidth = textPaint.measureText(label)
                val gap = dp(item, 8f)
                val centerY = (item.top + item.bottom) / 2
                val iconTop = centerY - size / 2

                c.save()
                c.clipRect(rect)
                if (dX > 0) {
                    val iconLeft = (item.left + margin).toInt()
                    icon.setBounds(iconLeft, iconTop, iconLeft + size, iconTop + size)
                    icon.draw(c)
                    c.drawText(label, iconLeft + size + gap, centerY - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
                } else {
                    val iconRight = (item.right - margin).toInt()
                    icon.setBounds(iconRight - size, iconTop, iconRight, iconTop + size)
                    icon.draw(c)
                    c.drawText(label, iconRight - size - gap - textWidth, centerY - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
                }
                c.restore()
            }
        }
        return ItemTouchHelper(callback).also { it.attachToRecyclerView(recyclerView) }
    }

    private fun dp(view: android.view.View, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, view.resources.displayMetrics)

    private fun sp(view: android.view.View, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, view.resources.displayMetrics)
}
