package jp.mimimemo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.mimimemo.databinding.ActivityTodoEditBinding
import java.time.LocalDate
import java.time.LocalDateTime

/** ④ ToDo 編集画面。id を渡すと編集、渡さないと新規追加。 */
class TodoEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodoEditBinding
    private lateinit var db: TodoDbHelper
    private var existing: Todo? = null
    private var due: LocalDate? = null
    private lateinit var tags: List<String>

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
        tags = if (todo != null && todo.tag !in Todo.TAGS) Todo.TAGS + todo.tag else Todo.TAGS
        binding.spinnerTag.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tags)

        if (savedInstanceState == null) {
            binding.editTitle.setText(todo?.title.orEmpty())
            due = todo?.due
            binding.spinnerTag.setSelection(tags.indexOf(todo?.tag ?: Todo.TAG_OTHER).coerceAtLeast(0))
        } else {
            due = savedInstanceState.getString(STATE_DUE)?.let(LocalDate::parse)
        }
        renderDue()

        binding.sourceSection.isVisible = !todo?.sourceText.isNullOrBlank()
        binding.textSource.text = getString(R.string.source_quote, todo?.sourceText.orEmpty())

        binding.buttonDue.setOnClickListener {
            showDuePicker(this, due) {
                due = it
                renderDue()
            }
        }
        binding.buttonClearDue.setOnClickListener {
            due = null
            renderDue()
        }
        binding.editTitle.doAfterTextChanged { binding.layoutTitle.error = null }
        binding.buttonSave.setOnClickListener { save() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DUE, due?.toString())
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
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
        else -> super.onOptionsItemSelected(item)
    }

    private fun renderDue() {
        binding.buttonDue.text = due?.let { DueFormatter.short(it) } ?: getString(R.string.due_none)
        binding.buttonClearDue.isVisible = due != null
    }

    private fun save() {
        val title = binding.editTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            binding.layoutTitle.error = getString(R.string.error_title_required)
            return
        }
        val tag = binding.spinnerTag.selectedItem as String
        val todo = existing
        if (todo == null) {
            db.insert(Todo(title = title, due = due, tag = tag, createdAt = LocalDateTime.now()))
        } else {
            db.update(todo.copy(title = title, due = due, tag = tag))
        }
        finish()
    }

    private fun confirmDelete() {
        val todo = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, todo.title))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                db.delete(todo.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "todo_id"
        private const val STATE_DUE = "due"
        private const val MENU_DELETE = 1

        fun intent(context: Context, id: Long?): Intent =
            Intent(context, TodoEditActivity::class.java).apply {
                if (id != null) putExtra(EXTRA_ID, id)
            }
    }
}
