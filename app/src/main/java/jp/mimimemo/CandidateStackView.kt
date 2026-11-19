package jp.mimimemo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible
import jp.mimimemo.databinding.ItemCandidateCardBinding
import kotlin.math.abs
import kotlin.math.min

/**
 * 聞き取り画面の候補カードの束（issue #33）。
 * いちばん上のカードを指で左右に払って選ぶ。右が「追加」、左が「追加しない」。
 * 後ろには次のカードを少しだけ見せ、上のカードを動かすほど前に出てくる。
 *
 * 表示するのは先頭の2件だけ。決まった候補は [onAccept] / [onReject] で知らせ、
 * 持ち主が一覧から消して [submit] し直す。
 */
class CandidateStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onAccept: (Todo) -> Unit = {}
    var onReject: (Todo) -> Unit = {}
    var onTap: (Todo) -> Unit = {}

    private val back = ItemCandidateCardBinding.inflate(LayoutInflater.from(context), this, true)
    private val front = ItemCandidateCardBinding.inflate(LayoutInflater.from(context), this, true)

    private var items: List<Todo> = emptyList()

    /** カードが飛んでいる間は、次の操作を受け付けない */
    private var flying = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6

    init {
        clipChildren = false
        back.root.isClickable = false
        setupDrag()
    }

    fun submit(list: List<Todo>) {
        items = list
        if (!flying) render()
    }

    /** ボタンから選ぶ。指で払ったときと同じように飛ばす */
    fun swipe(accept: Boolean) {
        if (flying || items.isEmpty()) return
        flyOut(if (accept) 1 else -1)
    }

    private fun render() {
        val top = items.getOrNull(0)
        val next = items.getOrNull(1)
        front.root.isVisible = top != null
        back.root.isVisible = next != null
        top?.let { bind(front, it) }
        next?.let { bind(back, it) }
        resetFront()
        setBackProgress(0f)
    }

    private fun bind(b: ItemCandidateCardBinding, todo: Todo) {
        b.textTitle.text = todo.title
        b.textDue.isVisible = todo.due != null
        todo.due?.let { b.textDue.text = DueFormatter.short(it) }
        b.textTag.isVisible = todo.tag != Todo.TAG_OTHER
        b.textTag.text = todo.tag
        b.rowMeta.isVisible = b.textDue.isVisible || b.textTag.isVisible
        b.textSource.isVisible = !todo.sourceText.isNullOrBlank()
        b.textSource.text = context.getString(R.string.source_quote, todo.sourceText.orEmpty())
        b.root.contentDescription = todo.title
    }

    private fun resetFront() {
        front.root.apply {
            translationX = 0f
            translationY = 0f
            rotation = 0f
            alpha = 1f
        }
        front.stampAdd.alpha = 0f
        front.stampSkip.alpha = 0f
    }

    /** 後ろのカード。上のカードを動かした割合（0〜1）だけ前に出す */
    private fun setBackProgress(p: Float) {
        val scale = BACK_SCALE + (1 - BACK_SCALE) * p
        back.root.scaleX = scale
        back.root.scaleY = scale
        back.root.translationY = BACK_OFFSET_DP * resources.displayMetrics.density * (1 - p)
    }

    /** 指の位置に合わせて上のカードを動かし、傾け、印を浮かべる */
    private fun follow(dx: Float, dy: Float) {
        val w = width.coerceAtLeast(1)
        val p = min(1f, abs(dx) / (w * DECIDE_RATIO))
        front.root.translationX = dx
        front.root.translationY = dy * 0.3f
        front.root.rotation = MAX_ROTATION * dx / w
        front.stampAdd.alpha = if (dx > 0) p else 0f
        front.stampSkip.alpha = if (dx < 0) p else 0f
        setBackProgress(p)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var downX = 0f
        var downY = 0f
        var dragging = false
        /** 指が動いた（縦も含む）。動いたら離してもタップにしない */
        var moved = false
        var velocity: VelocityTracker? = null

        // タップ（TalkBack のダブルタップも含む）で詳細を開く
        front.root.setOnClickListener { if (!flying) items.firstOrNull()?.let(onTap) }

        front.root.setOnTouchListener { v, e ->
            if (flying) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    moved = false
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    v.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        // 文字起こしのスクロールに指を取られないようにする
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) follow(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    val dx = e.rawX - downX
                    when {
                        !dragging -> if (!moved) v.performClick()
                        abs(dx) > width * DECIDE_RATIO -> flyOut(if (dx > 0) 1 else -1)
                        // 短くても素早く払ったら、その向きに決める
                        abs(vx) > flingVelocity && vx * dx > 0 -> flyOut(if (vx > 0) 1 else -1)
                        else -> springBack()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocity?.recycle()
                    velocity = null
                    if (dragging) springBack()
                }
            }
            true
        }
    }

    private fun springBack() {
        front.root.animate()
            .translationX(0f).translationY(0f).rotation(0f)
            .setInterpolator(OvershootInterpolator(1.2f))
            .setDuration(SPRING_MS)
            .setUpdateListener {
                val p = min(1f, abs(front.root.translationX) / (width * DECIDE_RATIO))
                front.stampAdd.alpha = if (front.root.translationX > 0) p else 0f
                front.stampSkip.alpha = if (front.root.translationX < 0) p else 0f
                setBackProgress(p)
            }
            .setListener(null)
            .start()
    }

    /** direction：1 なら右（追加）、-1 なら左（追加しない） */
    private fun flyOut(direction: Int) {
        val todo = items.firstOrNull() ?: return
        flying = true
        front.stampAdd.alpha = if (direction > 0) 1f else 0f
        front.stampSkip.alpha = if (direction < 0) 1f else 0f
        val startX = front.root.translationX
        val endX = direction * width * 1.4f
        front.root.animate()
            .translationX(endX)
            .rotation(MAX_ROTATION * 1.4f * direction)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(FLY_MS)
            .setUpdateListener {
                val p = ((front.root.translationX - startX) / (endX - startX)).coerceIn(0f, 1f)
                setBackProgress(maxOf(p, min(1f, abs(startX) / (width * DECIDE_RATIO))))
            }
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    front.root.animate().setListener(null).setUpdateListener(null)
                    flying = false
                    // 持ち主が一覧から消して submit し直す。そのとき描き直される
                    if (direction > 0) onAccept(todo) else onReject(todo)
                    if (items.firstOrNull() == todo) render()
                }
            })
            .start()
    }

    private companion object {
        /** 幅のこの割合を超えて動かして離したら決める */
        const val DECIDE_RATIO = 0.3f
        const val MAX_ROTATION = 12f
        const val BACK_SCALE = 0.94f
        const val BACK_OFFSET_DP = 14f
        const val FLY_MS = 220L
        const val SPRING_MS = 260L
    }
}
