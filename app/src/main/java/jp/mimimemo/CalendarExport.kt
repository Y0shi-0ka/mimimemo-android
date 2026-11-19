package jp.mimimemo

import android.content.Intent
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ToDo を端末のカレンダーアプリの予定作成画面に渡す（issue #111）。
 * カレンダーの権限は使わず、保存するかはカレンダー側で決める。
 */
object CalendarExport {

    /** 終日の予定は UTC の0時で渡す（CalendarContract の決まり） */
    fun allDayStartMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** カレンダーの繰り返しの書き方（RFC 5545） */
    fun rrule(repeat: Repeat?): String? = when (repeat) {
        Repeat.DAILY -> "FREQ=DAILY"
        Repeat.WEEKLY -> "FREQ=WEEKLY"
        Repeat.MONTHLY -> "FREQ=MONTHLY"
        null -> null
    }

    fun intent(title: String, due: LocalDate, repeat: Repeat?): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, allDayStartMillis(due))
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, allDayStartMillis(due.plusDays(1)))
            .apply { rrule(repeat)?.let { putExtra(CalendarContract.Events.RRULE, it) } }
}
