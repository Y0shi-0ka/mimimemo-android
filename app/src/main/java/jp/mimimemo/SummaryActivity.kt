package jp.mimimemo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
        binding.buttonSpeak.setOnClickListener { speakSummary() }
    }

    override fun onResume() {
        super.onResume()
        todos = db.findCreatedOn(LocalDate.now())
        adapter.submitList(todos)
        binding.textHeader.text = getString(R.string.summary_header, todos.size)
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

    fun speakSummary() {
        val s = speaker ?: ReminderSpeaker(this) {
            Snackbar.make(binding.root, R.string.tts_unavailable, Snackbar.LENGTH_LONG).show()
        }.also { speaker = it }
        s.stop()
        s.speak(SpeechTexts.summary(todos))
        s.remindDueTomorrow(db.findDueOn(LocalDate.now().plusDays(1)))
    }
}
