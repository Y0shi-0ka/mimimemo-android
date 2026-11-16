package jp.mimimemo

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivityListeningBinding
import jp.mimimemo.databinding.DialogCandidateBinding
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * ② 聞き取り画面。
 * この画面を表示している間だけ聞き取り、離れると止める（onPause）。
 * ToDo の候補は聞き取りを止めずにカードで並べ、右スワイプで追加・左スワイプで却下する。
 * カードをタップすると ③ 確認ダイアログで詳細を直してから追加できる（issue #12）。
 */
class ListeningActivity : AppCompatActivity(), SpeechController.ResultListener {

    private lateinit var binding: ActivityListeningBinding
    private lateinit var speech: SpeechController
    private lateinit var extractor: TodoExtractor
    private lateinit var db: TodoDbHelper

    private data class Line(val text: String, val candidate: Boolean)

    private val lines = ArrayDeque<Line>()
    /** 確認待ちの候補（新しいものが先頭）。DB にはまだ入っていないので id は負の仮番号 */
    private val candidates = mutableListOf<Todo>()
    private var nextTempId = -1L
    private lateinit var candidateAdapter: TodoAdapter
    private var addedCount = 0
    private var permissionRequested = false
    private var pulse: ObjectAnimator? = null

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showError(getString(R.string.mic_permission_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListeningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        db = TodoDbHelper(this)
        val keywords = KeywordStore(this)
        extractor = TodoExtractor(keywords = keywords::load, ai = UnavailableLocalAiClient)
        speech = SpeechController(this, this)

        candidateAdapter = TodoAdapter(onClick = ::showCandidateDialog)
        binding.listCandidates.layoutManager = LinearLayoutManager(this)
        binding.listCandidates.adapter = candidateAdapter
        SwipeActions.attach(
            binding.listCandidates,
            left = SwipeAction(R.string.swipe_reject, R.drawable.ic_close, getColor(R.color.swipe_neutral)) {
                rejectCandidate(candidateAdapter.currentList[it])
            },
            right = SwipeAction(R.string.swipe_add, R.drawable.ic_check, getColor(R.color.swipe_positive)) {
                acceptCandidate(candidateAdapter.currentList[it])
            },
        )

        binding.buttonStop.setOnClickListener { finish() }
        binding.buttonSend.setOnClickListener { sendManual() }
        binding.editManual.onSubmit { sendManual() }
        updateAddedCount()
    }

    override fun onResume() {
        super.onResume()
        if (hasMicPermission()) {
            startListening()
        } else if (!permissionRequested) {
            permissionRequested = true
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onPause() {
        // 画面を離れたら必ず止める（プライバシー方針①）
        speech.stop()
        super.onPause()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 状態と認識方式の表示は onStateChanged / onFatalError で更新される */
    private fun startListening() = speech.start()

    // ---- SpeechController.ResultListener ----

    override fun onSentence(text: String) {
        binding.textPartial.text = ""
        handleSentence(text)
    }

    override fun onPartial(text: String) {
        binding.textPartial.text = text
        scrollToBottom()
    }

    override fun onStateChanged(state: SpeechController.State) {
        val (label, active) = when (state) {
            SpeechController.State.STARTING -> R.string.status_starting to true
            SpeechController.State.LISTENING -> R.string.status_listening to true
            SpeechController.State.HEARING -> R.string.status_hearing to true
            SpeechController.State.STOPPED -> R.string.status_stopped to false
            SpeechController.State.MODEL_DOWNLOADING -> R.string.status_model_downloading to false
            SpeechController.State.MODEL_REQUIRED -> R.string.status_model_required to false
        }
        binding.textStatus.setText(label)
        setIndicator(active)
        when (state) {
            SpeechController.State.STOPPED -> return
            SpeechController.State.MODEL_DOWNLOADING -> {
                binding.textMode.setText(R.string.mode_model_downloading)
                hideAction()
            }
            SpeechController.State.MODEL_REQUIRED -> {
                binding.textMode.setText(R.string.mode_model_required)
                showAction(R.string.action_download_model) { speech.requestModelDownload() }
            }
            else -> {
                binding.textMode.setText(R.string.mode_on_device)
                hideAction()
            }
        }
    }

    override fun onFatalError(error: SpeechController.FatalError) {
        when (error) {
            // 通常の認識には切り替えず、手入力だけで使う（issue #9）
            SpeechController.FatalError.OnDeviceUnavailable ->
                showError(getString(R.string.error_on_device_unavailable), R.string.status_manual_only)
            SpeechController.FatalError.PermissionDenied ->
                showError(getString(R.string.mic_permission_denied))
            is SpeechController.FatalError.Repeated ->
                showError(getString(R.string.error_recognizer, error.code))
        }
    }

    // ---- 抽出と確認ダイアログ ----

    private fun sendManual() {
        val text = binding.editManual.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.editManual.text = null
        handleSentence(text)
    }

    private fun handleSentence(text: String) {
        lifecycleScope.launch {
            val todo = extractor.extract(text)
            addLine(Line(text, todo != null))
            if (todo != null) addCandidate(todo.copy(id = nextTempId--))
        }
    }

    private fun addCandidate(candidate: Todo, index: Int = 0) {
        candidates.add(index.coerceIn(0, candidates.size), candidate)
        // 差分の反映は非同期なので、反映が終わってから先頭（いちばん新しい候補）を見せる
        renderCandidates { binding.listCandidates.scrollToPosition(0) }
    }

    /** 候補を追加する。edited は詳細ダイアログで直した内容 */
    private fun acceptCandidate(candidate: Todo, edited: Todo = candidate) {
        val index = removeCandidate(candidate) ?: return
        val id = db.insert(edited.copy(id = 0, createdAt = LocalDateTime.now()))
        addedCount++
        updateAddedCount()
        showUndo(getString(R.string.candidate_added, edited.title)) {
            db.delete(id)
            addedCount--
            updateAddedCount()
            addCandidate(candidate, index)
        }
    }

    private fun rejectCandidate(candidate: Todo) {
        val index = removeCandidate(candidate) ?: return
        showUndo(getString(R.string.candidate_rejected, candidate.title)) { addCandidate(candidate, index) }
    }

    /** 一覧から消して、元の位置を返す */
    private fun removeCandidate(candidate: Todo): Int? {
        val index = candidates.indexOfFirst { it.id == candidate.id }
        if (index < 0) return null
        candidates.removeAt(index)
        renderCandidates()
        return index
    }

    private fun renderCandidates(onCommitted: () -> Unit = {}) {
        candidateAdapter.submitList(candidates.toList(), onCommitted)
        val any = candidates.isNotEmpty()
        binding.listCandidates.isVisible = any
        binding.textCandidatesHeader.isVisible = any
        binding.textCandidatesHeader.text = getString(R.string.candidates_header, candidates.size)
    }

    private fun showUndo(message: String, undo: () -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .setAnchorView(binding.manualRow)
            .show()
    }

    /** ③ 候補の詳細を直してから追加するダイアログ（カードをタップしたとき） */
    private fun showCandidateDialog(candidate: Todo) {
        val b = DialogCandidateBinding.inflate(LayoutInflater.from(this))
        var due = candidate.due

        b.editTitle.setText(candidate.title)
        fun renderDue() {
            b.buttonDue.text = due?.let { DueFormatter.short(it) } ?: getString(R.string.due_none)
        }
        renderDue()
        b.buttonDue.setOnClickListener {
            showDuePicker(this, due) {
                due = it
                renderDue()
            }
        }
        b.spinnerTag.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Todo.TAGS)
        b.spinnerTag.setSelection(Todo.TAGS.indexOf(candidate.tag).coerceAtLeast(0))
        b.textSource.text = getString(R.string.source_quote, candidate.sourceText.orEmpty())

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.candidate_title)
            .setView(b.root)
            .setPositiveButton(R.string.candidate_add) { _, _ ->
                val title = b.editTitle.text?.toString()?.trim().orEmpty().ifEmpty { candidate.title }
                acceptCandidate(
                    candidate,
                    candidate.copy(title = title, due = due, tag = b.spinnerTag.selectedItem as String),
                )
            }
            .setNegativeButton(R.string.candidate_reject) { _, _ -> rejectCandidate(candidate) }
            .show()
    }

    // ---- 表示 ----

    private fun addLine(line: Line) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()

        val highlight = ContextCompat.getColor(this, R.color.candidate_highlight)
        val sb = SpannableStringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(l.text)
            if (l.candidate) {
                sb.append("  ← ToDo候補")
                sb.setSpan(ForegroundColorSpan(highlight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.textTranscript.text = sb
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollTranscript.post { binding.scrollTranscript.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun updateAddedCount() {
        binding.textAdded.text = getString(R.string.added_count, addedCount)
    }

    private fun showError(message: String, statusLabel: Int = R.string.status_stopped) {
        binding.textStatus.setText(statusLabel)
        binding.textMode.text = message
        hideAction()
        setIndicator(false)
    }

    /** 状態カードの中に、いまできる操作のボタンを1つ出す */
    private fun showAction(label: Int, onClick: () -> Unit) {
        binding.buttonAction.setText(label)
        binding.buttonAction.setOnClickListener { onClick() }
        binding.buttonAction.isVisible = true
    }

    private fun hideAction() {
        binding.buttonAction.isVisible = false
    }

    private fun setIndicator(active: Boolean) {
        val color = ContextCompat.getColor(this, if (active) R.color.listening else R.color.idle)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        if (active) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(binding.statusDot, "alpha", 1f, 0.3f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            pulse?.cancel()
            pulse = null
            binding.statusDot.alpha = 1f
        }
    }

    private companion object {
        const val MAX_LINES = 30
    }
}
