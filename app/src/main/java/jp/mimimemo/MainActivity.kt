package jp.mimimemo

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.time.LocalDate

/** ① ToDo 一覧画面（期限順リスト）。アプリの起点。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: TodoDbHelper
    private lateinit var adapter: TodoAdapter
    private var speaker: ReminderSpeaker? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ListeningHub.attach(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = TodoDbHelper(this)
        adapter = TodoAdapter(
            onClick = { todo -> startActivity(TodoEditActivity.intent(this, todo.id)) },
            onDoneChanged = ::markDone,
        )
        binding.listView.layoutManager = LinearLayoutManager(this)
        binding.listView.adapter = adapter

        // 右スワイプで完了、左スワイプで削除（どちらも元に戻せる）
        SwipeActions.attach(
            binding.listView,
            left = SwipeAction(R.string.swipe_delete, R.drawable.ic_delete, getColor(R.color.swipe_delete)) {
                adapter.todoAt(it)?.let(::delete)
            },
            right = SwipeAction(R.string.swipe_done, R.drawable.ic_check, getColor(R.color.swipe_positive)) {
                adapter.todoAt(it)?.let { todo -> markDone(todo, true) }
            },
            // 見出しはスワイプしない（#49）
            canSwipe = { adapter.todoAt(it.bindingAdapterPosition) != null },
        )

        // 文字入力はなくし、マイクだけにする（issue #45）。聞き取りはバックグラウンドで続く（#44）
        binding.buttonListen.setOnClickListener { openListening(start = true) }
        // 札からは候補を決めに行くだけ。止まっていても聞き取りは始めない
        binding.listeningPill.setOnClickListener { openListening(start = false) }
        binding.buttonSpeakReminder.setOnClickListener { speakReminder() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ListeningHub.status.collect { renderListening() } }
                launch { ListeningHub.candidates.collect { renderListening() } }
            }
        }
    }

    private fun openListening(start: Boolean) =
        startActivity(Intent(this, ListeningActivity::class.java).putExtra(ListeningActivity.EXTRA_START, start))

    /** 聞き取り中、または決めていない候補があるときだけ、マイクの上に札を出す */
    private fun renderListening() {
        val running = ListeningHub.isRunning
        val count = ListeningHub.candidates.value.size
        binding.listeningPill.isVisible = running || count > 0
        binding.listeningDot.isVisible = running
        binding.listeningText.text = when {
            ListeningHub.status.value == ListeningHub.Status.PausedLowBattery -> getString(R.string.status_paused_battery)
            count > 0 && running -> getString(R.string.listening_with_candidates, count)
            count > 0 -> getString(R.string.candidates_waiting, count)
            else -> getString(R.string.status_listening)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        remindOncePerDay()
    }

    override fun onStop() {
        speaker?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        db.close()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SUMMARY, Menu.NONE, R.string.menu_summary)
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.menu_settings)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SUMMARY -> {
            startActivity(Intent(this, SummaryActivity::class.java))
            true
        }
        MENU_SETTINGS -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val todos = db.findAllOrderByDue()
        adapter.submitGrouped(todos)
        binding.emptyView.isVisible = todos.isEmpty()

        val dueTomorrow = db.findDueOn(LocalDate.now().plusDays(1))
        binding.reminderCard.isVisible = dueTomorrow.isNotEmpty()
        binding.reminderText.text = getString(R.string.reminder_banner, dueTomorrow.size)
    }

    private fun markDone(todo: Todo, done: Boolean) {
        db.setDone(todo.id, done)
        refresh()
        if (done) {
            showUndo(getString(R.string.done_message, todo.title)) {
                db.setDone(todo.id, false)
                refresh()
            }
        }
    }

    private fun delete(todo: Todo) {
        db.delete(todo.id)
        refresh()
        showUndo(getString(R.string.deleted_message, todo.title)) {
            db.insert(todo.copy(id = 0))
            refresh()
        }
    }

    private fun showUndo(message: String, undo: () -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .setAnchorView(binding.bottomBar)
            .show()
    }

    /** 明日が期限の ToDo を読み上げる。読み上げるものがなければ false */
    private fun speakReminder(): Boolean {
        val todos = db.findDueOn(LocalDate.now().plusDays(1))
        if (todos.isEmpty()) return false
        val s = speaker ?: ReminderSpeaker(this) {
            Snackbar.make(binding.root, R.string.tts_unavailable, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.bottomBar)
                .show()
        }.also { speaker = it }
        return s.remindDueTomorrow(todos)
    }

    /**
     * 期限前日のリマインド：その日はじめて一覧を開いたときに1回だけ読み上げる。
     * 決まった時刻の通知はしない（issue #8 で決定。通知の権限を増やさない）。
     */
    private fun remindOncePerDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_REMIND, null) == today) return
        if (speakReminder()) prefs.edit { putString(KEY_LAST_REMIND, today) }
    }

    private companion object {
        const val MENU_SUMMARY = 1
        const val MENU_SETTINGS = 2
        const val PREFS = "main"
        const val KEY_LAST_REMIND = "last_remind_date"
    }
}
