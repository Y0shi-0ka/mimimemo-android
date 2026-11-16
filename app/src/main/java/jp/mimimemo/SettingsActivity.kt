package jp.mimimemo

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.mimimemo.databinding.ActivitySettingsBinding

/** ⑥ 設定画面（キーワード辞書の追加・削除）。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: KeywordStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = KeywordStore(this)

        binding.buttonAdd.setOnClickListener { addKeyword() }
        binding.editKeyword.onSubmit { addKeyword() }
        binding.editKeyword.doAfterTextChanged { binding.layoutKeyword.error = null }
        render()
    }

    /** めったに使わない「初期値に戻す」は、上のバーのメニューに入れる（issue #29） */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_RESET, Menu.NONE, R.string.action_reset)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_RESET -> {
            confirmReset()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                store.reset()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun addKeyword() {
        val word = binding.editKeyword.text?.toString()?.trim().orEmpty()
        if (word.isEmpty()) return
        if (store.add(word)) {
            binding.editKeyword.text = null
            render()
        } else {
            binding.layoutKeyword.error = getString(R.string.keyword_exists, word)
        }
    }

    private fun render() {
        val words = store.load()
        binding.chipGroup.removeAllViews()
        for (word in words) {
            val chip = Chip(this).apply {
                text = word
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    store.remove(word)
                    render()
                }
            }
            binding.chipGroup.addView(chip)
        }
    }

    private companion object {
        const val MENU_RESET = 1
    }
}
