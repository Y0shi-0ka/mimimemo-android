package jp.mimimemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.mimimemo.databinding.ActivityTodoEditBinding
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ④ ToDo 編集画面。id を渡すと編集、渡さないと新規追加。
 * 期限・タグは札を1タップで選び、保存ボタンは置かずに画面を離れるときに保存する（issue #47）。
 */
class TodoEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodoEditBinding
    private lateinit var db: TodoDbHelper
    private var existing: Todo? = null
    private var due: LocalDate? = null
    private lateinit var tags: List<String>
    private var tag: String = Todo.TAG_OTHER
    private var repeat: Repeat? = null
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTodoEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        db = TodoDbHelper(this)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id > 0) {
            existing = db.findById(id)
            if (existing == null) {
                finish()
                return
            }
        }
        val todo = existing
        setTitle(if (todo == null) R.string.title_add else R.string.title_edit)

        // 保存済みのタグが一覧にない場合も選べるようにする
        tags = TagStore(this).all().let { all -> if (todo != null && todo.tag !in all) all + todo.tag else all }
        if (savedInstanceState == null) {
            binding.editTitle.setText(todo?.title.orEmpty())
            due = todo?.due
            tag = todo?.tag ?: Todo.TAG_OTHER
            repeat = todo?.repeat
        } else {
            due = savedInstanceState.getString(STATE_DUE)?.let(LocalDate::parse)
            tag = savedInstanceState.getString(STATE_TAG) ?: Todo.TAG_OTHER
            repeat = Repeat.fromKey(savedInstanceState.getString(STATE_REPEAT))
        }
        setUpVoiceTitle()
        ChoiceChips.bindDue(binding.chipsDue, due) {
            due = it
            // 期限があるときだけ「カレンダーに入れる」を出す
            invalidateOptionsMenu()
        }
        ChoiceChips.bindTag(binding.chipsTag, tags, tag) { tag = it }
        ChoiceChips.bindRepeat(binding.chipsRepeat, repeat) { repeat = it }

        binding.sourceSection.isVisible = !todo?.sourceText.isNullOrBlank()
        binding.textSource.text = getString(R.string.source_quote, todo?.sourceText.orEmpty())

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DUE, due?.toString())
        outState.putString(STATE_TAG, tag)
        outState.putString(STATE_REPEAT, repeat?.key)
    }

    /** 画面を離れるとき（戻る・ホーム・別の画面）に保存する */
    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (oneShot as? OneShotSpeech)?.cancel()
        db.close()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (repeatDue() != null) menu.add(Menu.NONE, MENU_CALENDAR, Menu.NONE, R.string.menu_calendar)
        if (existing != null) {
            menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.menu_delete)
                .setIcon(R.drawable.ic_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_DELETE -> {
            confirmDelete()
            true
        }
        MENU_CALENDAR -> {
            addToCalendar()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private var oneShot: Any? = null

    /**
     * 題名の欄のマイク：1回だけ聞き取って題名を置き換える（#114）。
     * 端末内の認識が使えない（Android 11 以前）ときはマイクを出さない。
     */
    private fun setUpVoiceTitle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            binding.layoutTitle.isEndIconVisible = false
            return
        }
        binding.layoutTitle.setEndIconOnClickListener { listenTitle() }
    }

    @SuppressLint("NewApi")
    private fun listenTitle() {
        when {
            // 常時の聞き取りがマイクを使っている間は使えない
            ListeningHub.isRunning -> Toast.makeText(this, R.string.voice_title_busy, Toast.LENGTH_SHORT).show()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                val before = binding.editTitle.text?.toString().orEmpty()
                binding.layoutTitle.hint = getString(R.string.voice_title_listening)
                binding.editTitle.text = null
                val speech = OneShotSpeech(this) { text ->
                    binding.layoutTitle.hint = getString(R.string.label_title)
                    binding.editTitle.setText(text ?: before)
                    binding.editTitle.setSelection(binding.editTitle.length())
                    if (text == null) Toast.makeText(this, R.string.voice_title_failed, Toast.LENGTH_SHORT).show()
                }
                oneShot = speech
                speech.start()
            }
        }
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenTitle()
    }

    /** 端末のカレンダーアプリの予定作成画面を開く（#111）。カレンダーアプリがなければ知らせる */
    private fun addToCalendar() {
        val day = repeatDue() ?: return
        val title = binding.editTitle.text?.toString()?.trim().orEmpty().ifEmpty { existing?.title.orEmpty() }
        try {
            startActivity(CalendarExport.intent(title, day, repeat))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.calendar_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** 繰り返すのに期限がなければ今日からにする（期限がないと次の日付へ進められないため、#107） */
    private fun repeatDue(): LocalDate? = due ?: if (repeat != null) LocalDate.now() else null

    /** 変わったところがあれば保存する。題名が空なら保存しない（元の題名のまま） */
    private fun save() {
        val title = binding.editTitle.text?.toString()?.trim().orEmpty()
        val todo = existing
        if (todo == null) {
            if (title.isEmpty()) return
            existing = db.findById(
                db.insert(Todo(title = title, due = repeatDue(), tag = tag, createdAt = LocalDateTime.now(), repeat = repeat)),
            )
            return
        }
        val updated = todo.copy(title = title.ifEmpty { todo.title }, due = repeatDue(), tag = tag, repeat = repeat)
        if (updated != todo) {
            db.update(updated)
            existing = updated
        }
    }

    private fun confirmDelete() {
        val todo = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, todo.title))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                deleted = true
                db.delete(todo.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "todo_id"
        private const val STATE_DUE = "due"
        private const val STATE_TAG = "tag"
        private const val STATE_REPEAT = "repeat"
        private const val MENU_DELETE = 1
        private const val MENU_CALENDAR = 2

        fun intent(context: Context, id: Long?): Intent =
            Intent(context, TodoEditActivity::class.java).apply {
                if (id != null) putExtra(EXTRA_ID, id)
            }
    }
}
