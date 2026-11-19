package jp.mimimemo

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.mimimemo.databinding.ActivitySettingsBinding

/** ⑥ 設定画面（キーワード辞書の追加・削除）。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: KeywordStore
    private lateinit var tags: TagStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = KeywordStore(this)
        tags = TagStore(this)
        binding.buttonAddTag.setOnClickListener { addTag() }
        binding.editTag.onSubmit { addTag() }
        binding.editTag.doAfterTextChanged { binding.layoutTag.error = null }
        renderTags()
        ChoiceChips.bindOptions(
            binding.chipsMorning,
            MorningReminder.HOURS.map { it to if (it == 0) getString(R.string.repeat_none) else getString(R.string.morning_hour, it) },
            MorningReminder.hour(this),
        ) { MorningReminder.setHour(this, it) }

        binding.buttonImportModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        binding.buttonRemoveModel.setOnClickListener { confirmRemoveModel() }
        binding.buttonAdd.setOnClickListener { addKeyword() }
        binding.editKeyword.onSubmit { addKeyword() }
        binding.editKeyword.doAfterTextChanged { binding.layoutKeyword.error = null }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (importJob?.isActive != true) renderAi()
    }

    /** 端末内の AI を使っているか（#68）。モデルがなければキーワードと言い方のルールで判定する */
    private fun renderAi() {
        val llm = LocalLlm(this)
        val model = llm.modelFile()
        binding.textAi.text = when {
            model == null -> getString(R.string.ai_off)
            // 前に読み込みで落ちたモデルは使っていない（#120）
            llm.isBlocked() -> getString(R.string.ai_blocked, model.nameWithoutExtension)
            else -> getString(R.string.ai_on, model.nameWithoutExtension)
        }
        binding.buttonImportModel.isEnabled = true
        binding.buttonRemoveModel.isVisible = model != null
    }

    private var importJob: Job? = null

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    /** 選んだファイルをモデルとして取り込む（#74）。取り込み中は進み具合を出す */
    private fun importModel(uri: Uri) {
        binding.buttonImportModel.isEnabled = false
        binding.buttonRemoveModel.isVisible = false
        binding.textAi.text = getString(R.string.model_importing, 0)
        importJob = lifecycleScope.launch {
            val result = ModelImport(this@SettingsActivity).import(uri) { percent ->
                binding.textAi.text = getString(R.string.model_importing, percent)
            }
            if (result is ModelImport.Result.Done) LocalLlm(this@SettingsActivity).resetGuard()
            renderAi()
            val message = when (result) {
                is ModelImport.Result.Done -> null
                ModelImport.Result.NotModel -> getString(R.string.model_not_model)
                is ModelImport.Result.NoSpace -> getString(R.string.model_no_space, result.needBytes / MB, result.freeBytes / MB)
                ModelImport.Result.Failed -> getString(R.string.model_failed)
            }
            message?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun confirmRemoveModel() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.model_remove_confirm)
            .setPositiveButton(R.string.model_remove) { _, _ ->
                ModelImport(this).remove()
                LocalLlm(this).resetGuard()
                renderAi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun addTag() {
        val tag = binding.editTag.text?.toString()?.trim().orEmpty()
        if (tag.isEmpty()) return
        if (tags.add(tag)) {
            binding.editTag.text = null
            renderTags()
        } else {
            binding.layoutTag.error = getString(R.string.keyword_exists, tag)
        }
    }

    /** 足したタグだけを出す（決まった5つは消せないので出さない、#10） */
    private fun renderTags() {
        binding.tagGroup.removeAllViews()
        for (tag in tags.load()) {
            binding.tagGroup.addView(
                Chip(this).apply {
                    text = tag
                    isCloseIconVisible = true
                    setChipBackgroundColorResource(R.color.card)
                    setChipStrokeColorResource(R.color.card_stroke)
                    setOnCloseIconClickListener {
                        tags.remove(tag)
                        renderTags()
                    }
                }
            )
        }
    }

    private fun render() {
        val words = store.load()
        binding.chipGroup.removeAllViews()
        for (word in words) {
            val chip = Chip(this).apply {
                text = word
                isCloseIconVisible = true
                // 一覧のカードと同じ白い面と細い枠（issue #35）
                setChipBackgroundColorResource(R.color.card)
                setChipStrokeColorResource(R.color.card_stroke)
                setOnCloseIconClickListener {
                    store.remove(word)
                    render()
                }
            }
            binding.chipGroup.addView(chip)
        }
        renderSuggestions(words)
    }

    /** 辞書にない、よく使う言い方を「＋」付きの札で出す。タップで足す（issue #51） */
    private fun renderSuggestions(words: List<String>) {
        val suggestions = (TodoExtractor.SUGGESTED_KEYWORDS + TodoExtractor.DEFAULT_KEYWORDS).distinct() - words.toSet()
        binding.suggestionGroup.removeAllViews()
        binding.labelSuggestions.isVisible = suggestions.isNotEmpty()
        for (word in suggestions) {
            binding.suggestionGroup.addView(
                Chip(this).apply {
                    text = word
                    setChipIconResource(R.drawable.ic_add_small)
                    isChipIconVisible = true
                    chipIconTint = textColors
                    setChipBackgroundColorResource(R.color.surface_container)
                    chipStrokeWidth = 0f
                    contentDescription = getString(R.string.keyword_add_suggestion, word)
                    setOnClickListener {
                        store.add(word)
                        render()
                    }
                }
            )
        }
    }

    private companion object {
        const val MENU_RESET = 1
        const val MB = 1024L * 1024
    }
}
