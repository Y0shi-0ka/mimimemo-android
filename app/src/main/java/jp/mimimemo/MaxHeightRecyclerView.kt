package jp.mimimemo

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 高さが画面の [MAX_RATIO] を超えないように伸びる RecyclerView。
 * 聞き取り画面の候補カードが増えても、文字起こし・入力欄・停止ボタンが押し出されないようにする。
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * MAX_RATIO).toInt()
        val size = MeasureSpec.getSize(heightSpec)
        val limit = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) max else minOf(size, max)
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    private companion object {
        const val MAX_RATIO = 0.4f
    }
}
