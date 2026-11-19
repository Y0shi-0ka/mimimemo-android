package jp.mimimemo

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 毎朝、期限切れ・今日・明日が期限の ToDo を通知で知らせる（issue #100）。
 * 正確な時刻にはこだわらない毎日のアラーム（省電力）。オン・オフは Android の通知設定（チャンネル）で切り替える。
 */
object MorningReminder {

    private const val CHANNEL_ID = "due_reminder"
    private const val NOTIFICATION_ID = 100
    private const val ACTION_REMIND = "jp.mimimemo.action.MORNING_REMIND"
    private const val PREFS = "morning"
    private const val KEY_HOUR = "hour"
    private const val DEFAULT_HOUR = 8

    /** 選べる時刻（#125）。0 は「しない」 */
    val HOURS = listOf(7, 8, 9, 0)

    /** お知らせの時刻（時）。0 なら知らせない */
    fun hour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HOUR, DEFAULT_HOUR)

    /** 時刻を変えて予約し直す。0 なら予約を消す */
    fun setHour(context: Context, hour: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_HOUR, hour) }
        schedule(context)
    }

    /** 次に鳴らす時刻（テスト用に分けた） */
    fun nextAt(now: LocalDateTime, hour: Int): LocalDateTime {
        var next = now.toLocalDate().atTime(LocalTime.of(hour, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }
    private const val MAX_TITLES = 3
    private const val WINDOW_MS = 60 * 60 * 1000L

    /** 通知の中身。知らせるものがなければ null */
    data class Message(val title: String, val lines: List<String>)

    /** 未完了の ToDo から通知の中身を作る（テストできるよう、Android に依存しない） */
    fun message(todos: List<Todo>, today: LocalDate): Message? {
        val overdue = todos.filter { it.due != null && it.due.isBefore(today) }
        val dueToday = todos.filter { it.due == today }
        val tomorrow = todos.filter { it.due == today.plusDays(1) }
        if (overdue.isEmpty() && dueToday.isEmpty() && tomorrow.isEmpty()) return null
        val title = listOfNotNull(
            overdue.size.takeIf { it > 0 }?.let { "期限切れ ${it}件" },
            dueToday.size.takeIf { it > 0 }?.let { "今日が期限 ${it}件" },
            tomorrow.size.takeIf { it > 0 }?.let { "明日 ${it}件" },
        ).joinToString("・")
        // 急ぐものから題名を出す
        val lines = (overdue + dueToday + tomorrow).take(MAX_TITLES).map { it.title }
        return Message(title, lines)
    }

    /**
     * 次の朝8時に1回だけ予約する（鳴ったら次の日を予約し直す）。同じものを何度予約しても1つにまとまる。
     * 毎日の繰り返し（setInexactRepeating）にすると最大18時間ずれることがあり、夜中に鳴りかねないので、
     * 選んだ時刻（初期値8時）から1時間の幅で鳴らす（正確な時刻の権限はいらない）。
     */
    fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val hour = hour(context)
        if (hour == 0) {
            alarm.cancel(pendingIntent(context))
            return
        }
        val at = nextAt(now, hour).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarm.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MorningReminderReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal fun isRemindAction(intent: Intent) = intent.action == ACTION_REMIND

    internal fun notifyNow(context: Context) {
        // 日付が変わったので「明日」→「今日」に合わせる
        TodoWidget.refresh(context)
        val todos = runCatching { TodoDbHelper(context).use { it.findAllOrderByDue() } }.getOrDefault(emptyList())
        val message = message(todos, LocalDate.now()) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = message.lines.joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(message.title)
            .setContentText(message.lines.joinToString("・"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}

/** 朝のアラームと、再起動・更新のあとの予約し直し */
class MorningReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> MorningReminder.schedule(context)
            else -> if (MorningReminder.isRemindAction(intent)) {
                MorningReminder.notifyNow(context)
                MorningReminder.schedule(context)
            }
        }
    }
}
