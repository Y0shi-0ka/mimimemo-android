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
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.mimimemo.databinding.ActivityListeningBinding
import jp.mimimemo.databinding.DialogCandidateBinding
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * ② 聞き取り画面。
 * この画面を表示している間だけ聞き取り、離れると止める（onPause）。
 * ToDo の候補が見つかるたびに ③ 確認ダイアログを出す。
 */
class ListeningActivity : AppCompatActivity(), SpeechController.ResultListener {

    private lateinit var binding: ActivityListeningBinding
    private lateinit var speech: SpeechController
    private lateinit var extractor: TodoExtractor
    private lateinit var db: TodoDbHelper

    private data class Line(val text: String, val candidate: Boolean)

    private val lines = ArrayDeque<Line>()
    private val pendingCandidates = ArrayDeque<Todo>()
    private var dialogShowing = false
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

        binding.buttonStop.setOnClickListener { finish() }
        binding.buttonSend.setOnClickListener { sendManual() }
        binding.editManual.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendManual()
                true
            } else {
                false
            }
        }
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
        }
        binding.textStatus.setText(label)
        setIndicator(active)
        if (state != SpeechController.State.STOPPED) binding.textMode.setText(R.string.mode_on_device)
    }

    override fun onFatalError(error: SpeechController.FatalError) {
        when (error) {
            // 通常の認識には切り替えず、手入力だけで使う（issue #9）
            SpeechController.FatalError.OnDeviceUnavailable ->
                showError(getString(R.string.error_on_device_unavailable), R.string.status_manual_only)
            SpeechController.FatalError.PermissionDenied ->
                showError(getString(R.string.mic_permission_denied))
            SpeechController.FatalError.ModelDownloading ->
                showError(getString(R.string.error_model_download))
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
            if (todo != null) {
                pendingCandidates.addLast(todo)
                showNextCandidate()
            }
        }
    }

    private fun showNextCandidate() {
        if (dialogShowing || isFinishing) return
        val todo = pendingCandidates.removeFirstOrNull() ?: return
        showCandidateDialog(todo)
    }

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

        dialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.candidate_title)
            .setView(b.root)
            .setPositiveButton(R.string.candidate_add) { _, _ ->
                val title = b.editTitle.text?.toString()?.trim().orEmpty()
                    .ifEmpty { candidate.title }
                val todo = candidate.copy(
                    title = title,
                    due = due,
                    tag = b.spinnerTag.selectedItem as String,
                    createdAt = LocalDateTime.now(),
                )
                db.insert(todo)
                addedCount++
                updateAddedCount()
                Toast.makeText(this, getString(R.string.candidate_added, title), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.candidate_reject, null)
            .setOnDismissListener {
                dialogShowing = false
                showNextCandidate()
            }
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
        setIndicator(false)
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
