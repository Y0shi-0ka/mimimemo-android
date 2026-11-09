package jp.mimimemo

import android.os.Bundle
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
        binding.buttonReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.action_reset) { _, _ ->
                    store.reset()
                    render()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        render()
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
        binding.textCount.text = getString(R.string.keyword_count, words.size)
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
}
