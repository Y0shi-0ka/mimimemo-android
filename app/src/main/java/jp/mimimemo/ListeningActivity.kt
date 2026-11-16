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
    private var permissionRequested = false
    private var pulse: ObjectAnimator? = null

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showPermissionDenied()
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
        when (state) {
            SpeechController.State.STARTING -> showStatus(R.string.status_starting, Indicator.ON)
            SpeechController.State.LISTENING -> showStatus(R.string.status_listening, Indicator.ON)
            // 文字は変えず、丸印の動きだけで声の検出を示す（issue #16）
            SpeechController.State.HEARING -> showStatus(R.string.status_listening, Indicator.PULSE)
            SpeechController.State.STOPPED -> {
                binding.textStatus.setText(R.string.status_stopped)
                setIndicator(Indicator.OFF)
            }
            SpeechController.State.MODEL_DOWNLOADING ->
                showNotice(R.string.status_model_downloading, getString(R.string.mode_model_downloading))
            SpeechController.State.MODEL_REQUIRED ->
                showNotice(
                    R.string.status_model_required,
                    getString(R.string.mode_model_required),
                    R.string.action_download_model,
                ) { speech.requestModelDownload() }
        }
    }

    override fun onFatalError(error: SpeechController.FatalError) {
        when (error) {
            // 通常の認識には切り替えず、手入力だけで使う（issue #9）
            SpeechController.FatalError.OnDeviceUnavailable ->
                showNotice(R.string.status_manual_only, getString(R.string.error_on_device_unavailable))
            SpeechController.FatalError.PermissionDenied -> showPermissionDenied()
            is SpeechController.FatalError.Repeated ->
                showNotice(R.string.status_stopped, getString(R.string.error_recognizer, error.code))
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
        showUndo(getString(R.string.candidate_added, edited.title)) {
            db.delete(id)
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
        binding.listCandidates.isVisible = candidates.isNotEmpty()
    }

    private fun showUndo(message: String, undo: () -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .setAnchorView(if (binding.manualRow.isVisible) binding.manualRow else binding.buttonStop)
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

    private enum class Indicator { OFF, ON, PULSE }

    /** 聞き取れている状態。説明や手入力の欄は出さない */
    private fun showStatus(label: Int, indicator: Indicator) {
        binding.textStatus.setText(label)
        setIndicator(indicator)
        binding.noticeCard.isVisible = false
        binding.manualRow.isVisible = false
        binding.textTranscript.setHint(R.string.transcript_hint)
    }

    /**
     * 聞き取れない状態。理由と、あればできる操作のボタンを出す。
     * この間も使えるよう、手入力の欄を出す。
     */
    private fun showNotice(status: Int, message: String, action: Int? = null, onAction: () -> Unit = {}) {
        binding.textStatus.setText(status)
        setIndicator(Indicator.OFF)
        binding.textNotice.text = message
        binding.buttonAction.isVisible = action != null
        if (action != null) {
            binding.buttonAction.setText(action)
            binding.buttonAction.setOnClickListener { onAction() }
        }
        binding.noticeCard.isVisible = true
        binding.manualRow.isVisible = true
        binding.textTranscript.hint = null
    }

    private fun showPermissionDenied() =
        showNotice(R.string.status_stopped, getString(R.string.mic_permission_denied))

    private fun setIndicator(indicator: Indicator) {
        val active = indicator != Indicator.OFF
        val color = ContextCompat.getColor(this, if (active) R.color.listening else R.color.idle)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        if (indicator == Indicator.PULSE) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofFloat(binding.statusDot, "alpha", 1f, 0.3f).apply {
                    duration = 500
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
