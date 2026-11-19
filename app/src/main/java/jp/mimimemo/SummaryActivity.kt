package jp.mimimemo

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivitySummaryBinding
import java.time.LocalDate

/** ⑤ 今日のまとめ画面。今日登録した ToDo と元の発言を一覧にし、読み上げる。 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var db: TodoDbHelper
    private lateinit var adapter: TodoAdapter
    private var speaker: ReminderSpeaker? = null
    private var todos: List<Todo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = TodoDbHelper(this)
        adapter = TodoAdapter(onClick = { startActivity(TodoEditActivity.intent(this, it.id)) }, showSource = true)
        binding.listView.layoutManager = LinearLayoutManager(this)
        binding.listView.adapter = adapter
    }

    /** 読み上げは上のバーのボタンから（issue #29） */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SPEAK, Menu.NONE, R.string.action_speak)
            .setIcon(R.drawable.ic_volume)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SPEAK -> {
            speakSummary()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        todos = db.findCreatedOn(LocalDate.now())
        adapter.submitTodos(todos)
        binding.emptyView.isVisible = todos.isEmpty()
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun speakSummary() {
        val s = speaker ?: ReminderSpeaker(this) {
            Snackbar.make(binding.root, R.string.tts_unavailable, Snackbar.LENGTH_LONG).show()
        }.also { speaker = it }
        s.stop()
        s.speak(SpeechTexts.summary(todos))
        s.remindDueTomorrow(db.findDueOn(LocalDate.now().plusDays(1)))
    }

    private companion object {
        const val MENU_SPEAK = 1
    }
}
