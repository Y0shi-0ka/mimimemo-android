package jp.mimimemo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

/**
 * ホーム画面ウィジェット（issue #97）。期限の近い未完了の ToDo を3件と、聞き取りを始めるマイク。
 * ToDo を変えたとき（[TodoDbHelper] の書き込み）に [refresh] で描き直す。
 */
class TodoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    /** 行の丸を押したら完了にする（#105）。描き直しは TodoDbHelper の書き込みで行われる */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DONE) {
            val id = intent.getLongExtra(EXTRA_TODO_ID, 0)
            // 繰り返しの ToDo は期限を次へ進める（#107）
            if (id > 0) runCatching { TodoDbHelper(context).use { it.complete(id) } }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ROWS = 3
        private const val ACTION_DONE = "jp.mimimemo.action.WIDGET_DONE"
        private const val EXTRA_TODO_ID = "jp.mimimemo.extra.TODO_ID"
        private const val REQUEST_DONE_BASE = 10
        private const val REQUEST_OPEN_LIST = 2
        private const val REQUEST_MIC = 1

        /** ウィジェットが置かれていれば描き直す */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val todos = runCatching { TodoDbHelper(context).use { it.findAllOrderByDue() } }.getOrDefault(emptyList())
            val views = RemoteViews(context.packageName, R.layout.widget_todo)
            val rows = listOf(
                Triple(R.id.row0, R.id.title0, R.id.due0),
                Triple(R.id.row1, R.id.title1, R.id.due1),
                Triple(R.id.row2, R.id.title2, R.id.due2),
            )
            val checks = listOf(R.id.check0, R.id.check1, R.id.check2)
            val today = LocalDate.now()
            rows.forEachIndexed { i, (row, title, due) ->
                val todo = todos.getOrNull(i)
                views.setViewVisibility(row, if (todo == null) View.GONE else View.VISIBLE)
                if (todo != null) {
                    views.setTextViewText(title, todo.title)
                    views.setTextViewText(due, todo.due?.let { dueLabel(context, it, today) } ?: "")
                    // 読み上げでどの ToDo か分かるように（#123）
                    views.setContentDescription(checks[i], context.getString(R.string.widget_done_item, todo.title))
                    // 行ごとに番号を分け、押した行の ToDo だけを完了にする
                    views.setOnClickPendingIntent(
                        checks[i],
                        PendingIntent.getBroadcast(
                            context, REQUEST_DONE_BASE + i,
                            Intent(context, TodoWidget::class.java).setAction(ACTION_DONE).putExtra(EXTRA_TODO_ID, todo.id),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            views.setViewVisibility(R.id.widgetEmpty, if (todos.isEmpty()) View.VISIBLE else View.GONE)
            val more = todos.size - ROWS
            views.setViewVisibility(R.id.widgetMore, if (more > 0) View.VISIBLE else View.GONE)
            if (more > 0) views.setTextViewText(R.id.widgetMore, context.getString(R.string.widget_more, more))

            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    // 聞き取り画面が上に重なっていても、一覧を出す。
                    // FLAG_UPDATE_CURRENT では Intent のフラグは変わらないので、フラグを変えたら番号も変える
                    context, REQUEST_OPEN_LIST,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // マイクの前面サービスは画面から始める必要があるので、聞き取り画面を開いて始める
            views.setOnClickPendingIntent(
                R.id.widgetMic,
                PendingIntent.getActivity(
                    context, REQUEST_MIC,
                    Intent(context, ListeningActivity::class.java)
                        .putExtra(ListeningActivity.EXTRA_START, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(ids, views)
        }

        /** 今日・明日は言葉で、ほかは日付で。過ぎたものは「期限切れ」 */
        private fun dueLabel(context: Context, due: LocalDate, today: LocalDate): String = when {
            due.isBefore(today) -> context.getString(R.string.widget_overdue)
            due == today -> context.getString(R.string.widget_today)
            due == today.plusDays(1) -> context.getString(R.string.widget_tomorrow)
            else -> DueFormatter.short(due)
        }
    }
}
