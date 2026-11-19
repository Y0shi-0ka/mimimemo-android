package jp.mimimemo

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 期限・タグを1タップで選ぶ札（issue #47）。カレンダーやドロップダウンを開かずに済ませる。
 */
object ChoiceChips {

    /** 期限の選択肢。date が null なら「なし」 */
    data class DueOption(val label: String, val date: LocalDate?)

    /**
     * なし・今日・明日・金曜・来週月曜。週は月曜はじまり（[DateParser] と同じ）。
     * 金曜は今日より後のいちばん近い金曜で、来週になるときは「来週金曜」。日付が重なるものは前のほうを残す。
     */
    fun dueOptions(today: LocalDate): List<DueOption> {
        val friday = today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return listOf(
            DueOption("なし", null),
            DueOption("今日", today),
            DueOption("明日", today.plusDays(1)),
            DueOption(if (friday.isAfter(endOfWeek)) "来週金曜" else "金曜", friday),
            DueOption("来週月曜", today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))),
        ).distinctBy { it.date }
    }

    /**
     * 期限の札を並べる。最後の「日付」だけは、選択肢にない日をカレンダーから選ぶ。
     * @param onChanged 選び直すたびに呼ぶ
     */
    fun bindDue(group: ChipGroup, initial: LocalDate?, onChanged: (LocalDate?) -> Unit) {
        prepare(group)
        val options = dueOptions(LocalDate.now())
        var current = initial
        val optionChips = options.map { chip(group, it.label) }
        val other = chip(group, "").apply {
            // 絵文字ではなく、ほかと同じ線のアイコンにする
            setChipIconResource(R.drawable.ic_calendar_small)
            chipIconTint = textColors
            isChipIconVisible = true
        }

        fun render() {
            val index = options.indexOfFirst { it.date == current }
            optionChips.forEachIndexed { i, c -> c.isChecked = i == index }
            other.isChecked = index < 0
            other.text = if (index < 0 && current != null) DueFormatter.short(current!!) else group.context.getString(R.string.due_pick)
        }

        options.forEachIndexed { i, option ->
            optionChips[i].setOnClickListener {
                current = option.date
                render()
                onChanged(current)
            }
        }
        other.setOnClickListener {
            // 選び終えるまでは元の選択のまま見せる（やめたときもそのまま）
            render()
            showDuePicker(group.context, current) { picked ->
                current = picked
                render()
                onChanged(current)
            }
        }
        render()
    }

    /** タグの札を並べる */
    fun bindTag(group: ChipGroup, tags: List<String>, initial: String, onChanged: (String) -> Unit) {
        prepare(group)
        tags.forEach { tag ->
            chip(group, tag).apply {
                isChecked = tag == initial
                setOnClickListener {
                    isChecked = true
                    onChanged(tag)
                }
            }
        }
    }

    /** 文字の札を並べ、1つ選ぶ（朝のお知らせの時刻など、#125） */
    fun <T> bindOptions(group: ChipGroup, options: List<Pair<T, String>>, initial: T, onChanged: (T) -> Unit) {
        prepare(group)
        options.forEach { (value, label) ->
            chip(group, label).apply {
                isChecked = value == initial
                setOnClickListener {
                    isChecked = true
                    onChanged(value)
                }
            }
        }
    }

    /** 繰り返しの札：しない・毎日・毎週・毎月（#107） */
    fun bindRepeat(group: ChipGroup, initial: Repeat?, onChanged: (Repeat?) -> Unit) {
        prepare(group)
        (listOf<Repeat?>(null) + Repeat.entries).forEach { repeat ->
            chip(group, repeat?.label ?: group.context.getString(R.string.repeat_none)).apply {
                isChecked = repeat == initial
                setOnClickListener {
                    isChecked = true
                    onChanged(repeat)
                }
            }
        }
    }

    private fun prepare(group: ChipGroup) {
        group.removeAllViews()
        group.isSingleSelection = true
        group.isSelectionRequired = true
    }

    /** 選ぶと主色で塗る札 */
    private fun chip(group: ChipGroup, text: String): Chip {
        val context = group.context
        fun states(checked: Int, normal: Int) = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ContextCompat.getColor(context, checked), ContextCompat.getColor(context, normal)),
        )
        return Chip(context).apply {
            this.text = text
            isCheckable = true
            isCheckedIconVisible = false
            chipBackgroundColor = states(R.color.primary, R.color.card)
            setTextColor(states(R.color.on_primary, R.color.on_surface))
            setChipStrokeColorResource(R.color.card_stroke)
            chipStrokeWidth = resources.displayMetrics.density
            group.addView(this)
        }
    }
}
