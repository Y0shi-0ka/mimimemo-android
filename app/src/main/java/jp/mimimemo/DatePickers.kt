package jp.mimimemo

import android.app.DatePickerDialog
import android.content.Context
import java.time.LocalDate

/** 期限を選ぶ DatePickerDialog を出す。 */
fun showDuePicker(context: Context, initial: LocalDate?, onPicked: (LocalDate) -> Unit) {
    val d = initial ?: LocalDate.now()
    DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
        d.year, d.monthValue - 1, d.dayOfMonth,
    ).show()
}
