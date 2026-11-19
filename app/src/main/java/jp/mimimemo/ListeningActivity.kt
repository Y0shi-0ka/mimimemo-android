package jp.mimimemo

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivityListeningBinding
import jp.mimimemo.databinding.DialogCandidateBinding
import kotlinx.coroutines.flow.combine
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

    /**
     * 横向きでは高さが足りず、カードの下のボタンや停止が画面の外に出る（#89）。
     * 横向きのときはカードを低くし、✕・✓ はカードの右に縦に並べる。
     * この画面は回転しても作り直さない（聞き取りを切らないため）ので、回転のたびに合わせ直す。
     */
    private fun fitCandidates(landscape: Boolean) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        binding.candidateArea.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        binding.candidateArea.gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        if (landscape) binding.textRemaining.isVisible = false
        binding.candidateStack.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (landscape) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            weight = if (landscape) 1f else 0f
            height = px(if (landscape) 170 else 230)
            bottomMargin = px(if (landscape) 8 else 20)
        }
        binding.candidateButtons.apply {
            orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (landscape) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT
            }
            if (landscape) setPadding(px(8), 0, px(20), 0) else setPadding(0, px(2), 0, px(20))
        }
        binding.buttonSkip.updateLayoutParams<LinearLayout.LayoutParams> {
            marginEnd = if (landscape) 0 else px(40)
            bottomMargin = if (landscape) px(16) else 0
        }
    }

    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitCandidates(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        if (!isLandscape) binding.textRemaining.isVisible = ListeningHub.candidates.value.size > 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ListeningHub.attach(this)
        binding = ActivityListeningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (isLandscape) fitCandidates(true)
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
        when {
            savedInstanceState != null -> Unit
            isShare(intent) -> receiveShared(intent)
            intent.getBooleanExtra(EXTRA_START, true) -> startIfNeeded()
        }
    }

    private fun isShare(intent: Intent) = intent.action == Intent.ACTION_SEND

    /**
     * 共有された文章を候補カードにする（#93）。聞き取りは始めない。
     * すでに一覧にある ToDo と同じものは出さない。
     */
    private fun receiveShared(intent: Intent) {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        intent.removeExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        lifecycleScope.launch {
            val existing = runCatching { db.findAllOrderByDue() }.getOrDefault(emptyList())
            val todos = SharedText.findCandidates(text, extractor).filterNot { CandidateDeduper.isAlreadyListed(it, existing) }
            todos.forEach { ListeningHub.addCandidate(it) }
            val message = if (todos.isEmpty()) getString(R.string.shared_none) else getString(R.string.shared_found, todos.size)
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).setAnchorView(binding.buttonStop).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isShare(intent)) receiveShared(intent) else if (debugSentences() != null) startIfNeeded()
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
                launch {
                    combine(ListeningHub.pending, ListeningHub.analyzing) { pending, analyzing -> pending to analyzing }
                        .collect { (pending, analyzing) ->
                            // まとめている最中は「まとめ中…」にして押せなくする（AI だと十数秒かかる、#68）
                            binding.buttonFlush.isVisible = (pending > 0 || analyzing) && !closing
                            binding.buttonFlush.isEnabled = !analyzing
                            binding.buttonFlush.setText(if (analyzing) R.string.analyzing else R.string.action_flush)
                        }
                }
                launch {
                    ListeningHub.candidates.collect {
                        binding.candidateStack.submit(it)
                        binding.candidateArea.isVisible = it.isNotEmpty()
                        // 外出中などにたまった候補をまとめて決めるとき、残りの枚数がわかるように（#56）
                        binding.textRemaining.isVisible = it.size > 1 && !isLandscape
                        binding.textRemaining.text = getString(R.string.candidates_remaining, it.size)
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
                    // 初めてのときもあるので「再開」ではなく「始める」（#89）
                    setStopButton(R.string.action_start, R.drawable.ic_mic) { startIfNeeded() }
                }
                return
            }
            ListeningHub.Status.Starting -> showState(R.string.status_starting, Indicator.ON)
            is ListeningHub.Status.Listening ->
                // 文字は変えず、丸印の動きだけで声の検出を示す（#16）
                showState(R.string.status_listening, if (status.hearing) Indicator.PULSE else Indicator.ON)
            ListeningHub.Status.PausedLowBattery ->
                showNotice(R.string.status_paused_battery, getString(R.string.paused_battery_message), R.string.action_resume_now) {
                    ListeningService.resume(this)
                }
            ListeningHub.Status.ModelDownloading ->
                showNotice(R.string.status_model_downloading, getString(R.string.mode_model_downloading))
            ListeningHub.Status.ModelRequired ->
                showNotice(R.string.status_model_required, getString(R.string.mode_model_required), R.string.action_download_model) {
                    ListeningService.requestModelDownload(this)
                }
            // 通常の認識には切り替えず、手入力だけで使う（#9）
            ListeningHub.Status.Unavailable ->
                showNotice(R.string.status_manual_only, getString(R.string.error_on_device_unavailable), manual = true)
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
        // 説明は聞き取っているときだけ。止まっているのに「続きます」と出さない
        binding.textTranscript.hint =
            if (indicator != Indicator.OFF && ListeningHub.lines.value.isEmpty() && ListeningHub.partial.value.isEmpty()) {
                getString(R.string.transcript_hint)
            } else {
                null
            }
    }

    /**
     * 聞き取れない状態。理由と、あればできる操作のボタンを出す。
     * 手入力の欄は、端末内の認識がそもそも使えないときだけ出す（文字入力はできるだけ出さない、#45・#64）
     */
    private fun showNotice(
        status: Int,
        message: String,
        action: Int? = null,
        manual: Boolean = false,
        onAction: () -> Unit = {},
    ) {
        binding.textStatus.setText(status)
        setIndicator(Indicator.OFF)
        binding.textNotice.text = message
        binding.buttonAction.isVisible = action != null
        if (action != null) {
            binding.buttonAction.setText(action)
            binding.buttonAction.setOnClickListener { onAction() }
        }
        binding.noticeCard.isVisible = true
        binding.manualRow.isVisible = manual
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
        ListeningHub.dropSameAs(edited)
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
        var tag = candidate.tag

        b.editTitle.setText(candidate.title)
        // 期限・タグは札を1タップで選ぶ（#47）
        ChoiceChips.bindDue(b.chipsDue, due) { due = it }
        ChoiceChips.bindTag(b.chipsTag, Todo.TAGS, tag) { tag = it }
        b.textSource.text = getString(R.string.source_quote, candidate.sourceText.orEmpty())

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.candidate_title)
            .setView(b.root)
            .setPositiveButton(R.string.candidate_add) { _, _ ->
                val title = b.editTitle.text?.toString()?.trim().orEmpty().ifEmpty { candidate.title }
                acceptCandidate(candidate, candidate.copy(title = title, due = due, tag = tag))
            }
            .setNegativeButton(R.string.candidate_reject) { _, _ -> rejectCandidate(candidate) }
            .show()
    }

    companion object {
        /** false なら開いても聞き取りを始めない（候補を決めに来ただけのとき） */
        const val EXTRA_START = "jp.mimimemo.extra.START"
    }
}
