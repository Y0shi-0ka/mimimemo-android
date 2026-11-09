package jp.mimimemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivityMainBinding
import java.time.LocalDate

/** ① ToDo 一覧画面（期限順リスト）。アプリの起点。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: TodoDbHelper
    private lateinit var adapter: TodoAdapter
    private var speaker: ReminderSpeaker? = null
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Snackbar.make(binding.root, R.string.mic_permission_denied, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabListen)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = TodoDbHelper(this)
        adapter = TodoAdapter(
            onClick = { todo -> startActivity(TodoEditActivity.intent(this, todo.id)) },
            onDoneChanged = ::markDone,
        )
        binding.listView.layoutManager = LinearLayoutManager(this)
        binding.listView.adapter = adapter

        binding.fabListen.setOnClickListener {
            startActivity(Intent(this, ListeningActivity::class.java))
        }
        binding.fabAdd.setOnClickListener { startActivity(TodoEditActivity.intent(this, null)) }
        binding.buttonSpeakReminder.setOnClickListener { speakReminder() }

        askMicPermissionOnFirstLaunch()
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
        adapter.submitList(todos)
        binding.emptyView.isVisible = todos.isEmpty()

        val dueTomorrow = db.findDueOn(LocalDate.now().plusDays(1))
        binding.reminderCard.isVisible = dueTomorrow.isNotEmpty()
        binding.reminderText.text = getString(R.string.reminder_banner, dueTomorrow.size)
    }

    private fun markDone(todo: Todo, done: Boolean) {
        db.setDone(todo.id, done)
        refresh()
        if (done) {
            Snackbar.make(binding.root, getString(R.string.done_message, todo.title), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    db.setDone(todo.id, false)
                    refresh()
                }
                .setAnchorView(binding.fabListen)
                .show()
        }
    }

    /** 明日が期限の ToDo を読み上げる。読み上げるものがなければ false */
    private fun speakReminder(): Boolean {
        val todos = db.findDueOn(LocalDate.now().plusDays(1))
        if (todos.isEmpty()) return false
        val s = speaker ?: ReminderSpeaker(this) {
            Snackbar.make(binding.root, R.string.tts_unavailable, Snackbar.LENGTH_LONG).show()
        }.also { speaker = it }
        return s.remindDueTomorrow(todos)
    }

    /**
     * 期限前日のリマインド：その日はじめて一覧を開いたときに1回だけ読み上げる。
     * 決まった時刻に通知するかどうかは issue #8 で検討中。
     */
    private fun remindOncePerDay() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_REMIND, null) == today) return
        if (speakReminder()) prefs.edit { putString(KEY_LAST_REMIND, today) }
    }

    private fun askMicPermissionOnFirstLaunch() {
        if (prefs.getBoolean(KEY_ASKED_MIC, false)) return
        prefs.edit { putBoolean(KEY_ASKED_MIC, true) }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private companion object {
        const val MENU_SUMMARY = 1
        const val MENU_SETTINGS = 2
        const val PREFS = "main"
        const val KEY_LAST_REMIND = "last_remind_date"
        const val KEY_ASKED_MIC = "asked_mic_permission"
    }
}
