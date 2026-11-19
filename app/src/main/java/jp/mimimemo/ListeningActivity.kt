package jp.mimimemo

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivityListeningBinding
import jp.mimimemo.databinding.DialogCandidateBinding
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * ② 聞き取り画面。
 *
 * 聞き取りそのものは [ListeningService] がバックグラウンドで続ける（issue #44）。
 * この画面は [ListeningHub] の状態・文字起こし・候補を表示し、候補を決める場所。
 * 戻る・閉じるでは止めない。停止ボタン（または通知の「停止」）で止める。
 *
 * 候補はカードで1枚ずつ出し、右へ払うと追加・左へ払うと追加しない（#33）。
 * タップで ③ 詳細を直してから追加できる（#12）。
 */
class ListeningActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListeningBinding
    private lateinit var extractor: TodoExtractor
    private lateinit var db: TodoDbHelper
    private var pulse: ObjectAnimator? = null

    /** 停止を押したあと、残った候補を決めている間 */
    private var closing = false
    private var permissionRequested = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) startService() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListeningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = TodoDbHelper(this)
        extractor = TodoExtractor(keywords = KeywordStore(this)::load, ai = UnavailableLocalAiClient)

        binding.candidateStack.onAccept = { acceptCandidate(it) }
        binding.candidateStack.onReject = { rejectCandidate(it) }
        binding.candidateStack.onTap = ::showCandidateDialog
        binding.buttonAdd.setOnClickListener { binding.candidateStack.swipe(accept = true) }
        binding.buttonSkip.setOnClickListener { binding.candidateStack.swipe(accept = false) }
        binding.buttonFlush.setOnClickListener { ListeningService.flush(this) }
        binding.buttonSend.setOnClickListener { sendManual() }
        binding.editManual.onSubmit { sendManual() }

        observe()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START, true)) startIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (debugSentences() != null) startIfNeeded()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        // 閉じても聞き取りは続く
        finish()
        return true
    }

    // ---- 聞き取りの開始・停止 ----

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startIfNeeded() {
        if (ListeningHub.isRunning && debugSentences() == null) return
        when {
            hasMicPermission() -> startService()
            !permissionRequested -> requestPermission()
            else -> showPermissionDenied()
        }
    }

    /** マイクと（Android 13 以降は）通知の許可を、1回だけまとめて聞く */
    private fun requestPermission() {
        permissionRequested = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions.launch(permissions.toTypedArray())
    }

    private fun startService() {
        closing = false
        ListeningService.start(this, debugSentences())
        intent.removeExtra(ListeningService.EXTRA_DEBUG_SENTENCES)
    }

    /** デバッグ版だけ：adb から渡した文 */
    private fun debugSentences(): Array<String>? =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringArrayExtra(ListeningService.EXTRA_DEBUG_SENTENCES)
        } else {
            null
        }

    /**
     * 停止ボタン。ためた分をまとめてから止める。
     * 決めていない候補が残っていれば閉じずにカードを出し、ボタンを「閉じる」に変える。
     */
    private fun stopOrClose() {
        if (closing || !ListeningHub.isRunning) {
            finish()
            return
        }
        closing = true
        binding.buttonFlush.isVisible = false
        ListeningService.stop(this)
        binding.buttonStop.setText(R.string.action_close)
        binding.buttonStop.setIconResource(R.drawable.ic_close)
    }

    // ---- 表示 ----

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ListeningHub.status.collect(::renderStatus) }
                launch { ListeningHub.lines.collect(::renderLines) }
                launch {
                    ListeningHub.partial.collect {
                        binding.textPartial.text = it
                        if (it.isNotEmpty()) binding.textTranscript.hint = null
                        scrollToBottom()
                    }
                }
                launch { ListeningHub.pending.collect { binding.buttonFlush.isVisible = it > 0 && !closing } }
                launch {
                    ListeningHub.candidates.collect {
                        binding.candidateStack.submit(it)
                        binding.candidateArea.isVisible = it.isNotEmpty()
                        // 停止したあと、決めていない候補がなくなったら閉じる
                        if (closing && it.isEmpty() && !ListeningHub.isRunning) finish()
                    }
                }
            }
        }
    }

    private fun renderStatus(status: ListeningHub.Status) {
        when (status) {
            ListeningHub.Status.Off -> {
                showState(R.string.status_stopped, Indicator.OFF)
                if (closing) {
                    if (ListeningHub.candidates.value.isEmpty()) finish()
                } else {
                    // 止まっている。押すと始める
                    setStopButton(R.string.action_resume, R.drawable.ic_mic) { startIfNeeded() }
                }
                return
            }
            ListeningHub.Status.Starting -> showState(R.string.status_starting, Indicator.ON)
            is ListeningHub.Status.Listening ->
                // 文字は変えず、丸印の動きだけで声の検出を示す（#16）
                showState(R.string.status_listening, if (status.hearing) Indicator.PULSE else Indicator.ON)
            ListeningHub.Status.ModelDownloading ->
                showNotice(R.string.status_model_downloading, getString(R.string.mode_model_downloading))
            ListeningHub.Status.ModelRequired ->
                showNotice(R.string.status_model_required, getString(R.string.mode_model_required), R.string.action_download_model) {
                    ListeningService.requestModelDownload(this)
                }
            // 通常の認識には切り替えず、手入力だけで使う（#9）
            ListeningHub.Status.Unavailable ->
                showNotice(R.string.status_manual_only, getString(R.string.error_on_device_unavailable))
            ListeningHub.Status.PermissionDenied -> showPermissionDenied()
            is ListeningHub.Status.Failed ->
                showNotice(R.string.status_stopped, getString(R.string.error_recognizer, status.code), R.string.action_resume) {
                    startIfNeeded()
                }
        }
        if (!closing) setStopButton(R.string.action_stop, R.drawable.ic_stop) { stopOrClose() }
    }

    private fun setStopButton(text: Int, icon: Int, onClick: () -> Unit) {
        binding.buttonStop.setText(text)
        binding.buttonStop.setIconResource(icon)
        binding.buttonStop.setOnClickListener { onClick() }
    }

    private enum class Indicator { OFF, ON, PULSE }

    /** 聞き取れている（または止めた）状態。説明や手入力の欄は出さない */
    private fun showState(label: Int, indicator: Indicator) {
        binding.textStatus.setText(label)
        setIndicator(indicator)
        binding.noticeCard.isVisible = false
        binding.manualRow.isVisible = false
        if (ListeningHub.lines.value.isEmpty() && ListeningHub.partial.value.isEmpty()) {
            binding.textTranscript.setHint(R.string.transcript_hint)
        }
    }

    /** 聞き取れない状態。理由と、あればできる操作のボタンを出す。この間も使えるよう手入力の欄を出す */
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

    /**
     * マイクが許可されていないとき。ボタン1つで許可へ進めるようにする（#15）。
     * まだ確認を出せるならその場で出し、出せなくなっていたら（2回断った後）アプリの設定を開く。
     */
    private fun showPermissionDenied(): Unit =
        showNotice(R.string.status_mic_denied, getString(R.string.mic_permission_denied), R.string.action_allow_mic) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                requestPermission()
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }

    private fun renderLines(lines: List<ListeningHub.Line>) {
        if (lines.isNotEmpty()) binding.textTranscript.hint = null
        val highlight = ContextCompat.getColor(this, R.color.candidate_highlight)
        val sb = SpannableStringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(l.text)
            // 候補になった文は主色で示す。太字にはしない（#35）
            if (l.candidate) sb.setSpan(ForegroundColorSpan(highlight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.textTranscript.text = sb
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.scrollTranscript.post { binding.scrollTranscript.fullScroll(android.view.View.FOCUS_DOWN) }
    }

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

    // ---- 候補 ----

    /** 端末内の認識が使えないときだけ出る手入力。待たずにすぐ調べる */
    private fun sendManual() {
        val text = binding.editManual.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.editManual.text = null
        val id = ListeningHub.addLine(text)
        lifecycleScope.launch {
            val todo = extractor.extract(text) ?: return@launch
            ListeningHub.markCandidates(setOf(id))
            ListeningHub.addCandidate(todo)
        }
    }

    /** 候補を追加する。edited は詳細ダイアログで直した内容 */
    private fun acceptCandidate(candidate: Todo, edited: Todo = candidate) {
        val index = ListeningHub.removeCandidate(candidate) ?: return
        val id = db.insert(edited.copy(id = 0, createdAt = LocalDateTime.now()))
        showUndo(getString(R.string.candidate_added, edited.title)) {
            db.delete(id)
            ListeningHub.restoreCandidate(candidate, index)
        }
    }

    private fun rejectCandidate(candidate: Todo) {
        val index = ListeningHub.removeCandidate(candidate) ?: return
        showUndo(getString(R.string.candidate_rejected, candidate.title)) { ListeningHub.restoreCandidate(candidate, index) }
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
        b.spinnerTag.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Todo.TAGS)
        b.spinnerTag.setSelection(Todo.TAGS.indexOf(candidate.tag).coerceAtLeast(0))
        b.textSource.text = getString(R.string.source_quote, candidate.sourceText.orEmpty())

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.candidate_title)
            .setView(b.root)
            .setPositiveButton(R.string.candidate_add) { _, _ ->
                val title = b.editTitle.text?.toString()?.trim().orEmpty().ifEmpty { candidate.title }
                acceptCandidate(candidate, candidate.copy(title = title, due = due, tag = b.spinnerTag.selectedItem as String))
            }
            .setNegativeButton(R.string.candidate_reject) { _, _ -> rejectCandidate(candidate) }
            .show()
    }

    companion object {
        /** false なら開いても聞き取りを始めない（候補を決めに来ただけのとき） */
        const val EXTRA_START = "jp.mimimemo.extra.START"
    }
}
