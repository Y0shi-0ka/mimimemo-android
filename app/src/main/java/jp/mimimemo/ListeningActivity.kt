package jp.mimimemo

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import jp.mimimemo.databinding.ActivityListeningBinding
import jp.mimimemo.databinding.DialogCandidateBinding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ② 聞き取り画面。
 * この画面を表示している間だけ聞き取り、離れると止める（onPause）。
 * 聞き取った文はメモリにためて5分ごとにまとめて調べ、候補をカードで出す（issue #36）。
 * 会話の途中で何度もカードが割り込まず、前後の文から期限を補える。文は保存しない。
 * カードは右へ払うと追加・左へ払うと追加しない（issue #33）。
 * カードをタップすると ③ 確認ダイアログで詳細を直してから追加できる（issue #12）。
 *
 * 聞き取り中は画面を点けたままにするので、電池を使いすぎないようにしている（issue #21）：
 * しばらく触れなければ画面を暗くし、声がないまま長く経ったら聞き取りを止める。
 */
class ListeningActivity : AppCompatActivity(), SpeechController.ResultListener {

    private lateinit var binding: ActivityListeningBinding
    private lateinit var speech: SpeechController
    private lateinit var extractor: TodoExtractor
    private lateinit var db: TodoDbHelper

    /** 文字起こしの1行。まとめて調べたあとで候補になったら candidate を true にする */
    private class Line(val text: String, var candidate: Boolean = false)

    private val lines = ArrayDeque<Line>()
    /** 確認待ちの候補（先頭がいま見せているカード。新しいものは後ろに足す）。DB にはまだ入っていないので id は負の仮番号 */
    private val candidates = mutableListOf<Todo>()
    private var nextTempId = -1L

    /** 一度でも声を文字にしたか。したら説明文はもう出さない（issue #31） */
    private var heardAnything = false
    private val deduper = CandidateDeduper()

    /** まだ調べていない文（issue #36）。batchLines は lines の中の同じ行を指す */
    private val batch = SentenceBatch()
    private val batchLines = mutableListOf<Line>()
    private val parser = DateParser()
    private val flushBatch = Runnable { flush() }

    /** 停止を押したあと、残った候補を決めている間。もう一度押すと閉じる */
    private var closing = false
    private var permissionRequested = false
    private var pulse: ObjectAnimator? = null

    /** 聞き取りが動いている間だけ true。画面を点けたままにするのはこの間だけ */
    private var listeningActive = false
    private val timers = Handler(Looper.getMainLooper())
    private val dimScreen = Runnable { setDimmed(true) }
    private val stopForSilence = Runnable { stopBecauseSilent() }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListeningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = TodoDbHelper(this)
        val keywords = KeywordStore(this)
        extractor = TodoExtractor(keywords = keywords::load, ai = UnavailableLocalAiClient)
        speech = SpeechController(
            this,
            this,
            vocabulary = { TodoExtractor.vocabulary(keywords.load()) },
            prefer = extractor::isCandidate,
        )

        binding.candidateStack.onAccept = { acceptCandidate(it) }
        binding.candidateStack.onReject = { rejectCandidate(it) }
        binding.candidateStack.onTap = ::showCandidateDialog
        binding.buttonAdd.setOnClickListener { binding.candidateStack.swipe(accept = true) }
        binding.buttonSkip.setOnClickListener { binding.candidateStack.swipe(accept = false) }

        binding.buttonStop.setOnClickListener { stopAndClose() }
        binding.buttonFlush.setOnClickListener { flush() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = stopAndClose()
        })
        binding.buttonSend.setOnClickListener { sendManual() }
        binding.editManual.onSubmit { sendManual() }

        // デバッグ版だけ：adb から渡した文を、聞き取った文と同じ流れで扱う（実機で候補カードを確かめるため）
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringArrayExtra(EXTRA_DEBUG_SENTENCES)?.forEach(::handleSentence)
        }
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
        timers.removeCallbacksAndMessages(null)
        db.close()
        super.onDestroy()
    }

    /** 触れたら画面の明るさを戻し、暗くするまでの時間を数え直す */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) wakeScreen()
        return super.dispatchTouchEvent(ev)
    }

    override fun onSupportNavigateUp(): Boolean {
        stopAndClose()
        return true
    }

    /**
     * 停止・戻る。ためた文をまとめてから閉じる。
     * 決めていない候補が残っていたら閉じずにカードを出し、もう一度押したら閉じる。
     */
    private fun stopAndClose() {
        if (closing || (batch.size == 0 && candidates.isEmpty())) {
            finish()
            return
        }
        closing = true
        speech.stop()
        binding.buttonStop.setText(R.string.action_close)
        binding.buttonStop.setIconResource(R.drawable.ic_close)
        flush {
            if (candidates.isEmpty()) finish()
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 状態と認識方式の表示は onStateChanged / onFatalError で更新される */
    private fun startListening() {
        if (closing) return
        speech.start()
    }

    // ---- SpeechController.ResultListener ----

    override fun onSentence(text: String) {
        restartSilenceTimer()
        binding.textPartial.text = ""
        handleSentence(text)
    }

    override fun onPartial(text: String) {
        restartSilenceTimer()
        heardAnything = true
        binding.textTranscript.hint = null
        binding.textPartial.text = text
        scrollToBottom()
    }

    override fun onStateChanged(state: SpeechController.State) {
        when (state) {
            SpeechController.State.STARTING -> showStatus(R.string.status_starting, Indicator.ON)
            SpeechController.State.LISTENING -> {
                // 次の聞き取りが始まった。確定せずに打ち切られた途中の文字は消す（issue #31）
                binding.textPartial.text = ""
                showStatus(R.string.status_listening, Indicator.ON)
            }
            // 文字は変えず、丸印の動きだけで声の検出を示す（issue #16）
            SpeechController.State.HEARING -> showStatus(R.string.status_listening, Indicator.PULSE)
            SpeechController.State.STOPPED -> {
                binding.textStatus.setText(R.string.status_stopped)
                setIndicator(Indicator.OFF)
                setListeningActive(false)
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
        // 手で打った文は、待たずにすぐ調べる
        handleSentence(text)
        flush()
    }

    /** 聞き取った文をためる。最初の1文から5分後にまとめて調べる（issue #36） */
    private fun handleSentence(text: String) {
        val line = Line(text)
        addLine(line)
        batch.add(text)
        batchLines += line
        if (batch.size == 1) timers.postDelayed(flushBatch, BATCH_MS)
        renderFlushButton()
    }

    /** ためた文をまとめて調べ、候補をカードの束の後ろに足す */
    private fun flush(then: () -> Unit = {}) {
        timers.removeCallbacks(flushBatch)
        val (context, sentences) = batch.drain()
        val targets = batchLines.toList()
        batchLines.clear()
        renderFlushButton()
        if (sentences.isEmpty()) {
            then()
            return
        }
        lifecycleScope.launch {
            val found = context.map { null } + sentences.map { extractor.extract(it) }
            val today = LocalDate.now()
            val filled = SentenceBatch.fillDueFromContext(context + sentences, found) { parser.parse(it, today) }
                .drop(context.size)
            filled.forEachIndexed { i, todo ->
                // 言い直し・聞き返しで同じ内容がまた来たら、候補は増やさない
                if (todo == null || !deduper.isNew(todo)) return@forEachIndexed
                targets[i].candidate = true
                // 触っている最中のカードは入れ替えない
                addCandidate(todo.copy(id = nextTempId--), candidates.size)
            }
            renderLines()
            then()
        }
    }

    /** ためている文があるときだけ「今まとめる」を出す */
    private fun renderFlushButton() {
        binding.buttonFlush.isVisible = batch.size > 0 && !closing
    }

    private fun addCandidate(candidate: Todo, index: Int = 0) {
        candidates.add(index.coerceIn(0, candidates.size), candidate)
        wakeScreen()
        renderCandidates()
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

    private fun renderCandidates() {
        binding.candidateStack.submit(candidates.toList())
        binding.candidateArea.isVisible = candidates.isNotEmpty()
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
        renderLines()
    }

    private fun renderLines() {
        val highlight = ContextCompat.getColor(this, R.color.candidate_highlight)
        val sb = SpannableStringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(l.text)
            if (l.candidate) {
                // 候補になった文は主色で示す。太字にはしない（重く見えるため、issue #35）
                sb.setSpan(ForegroundColorSpan(highlight), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        setListeningActive(true)
        binding.noticeCard.isVisible = false
        binding.manualRow.isVisible = false
        if (!heardAnything) binding.textTranscript.setHint(R.string.transcript_hint)
    }

    /**
     * 聞き取れない状態。理由と、あればできる操作のボタンを出す。
     * この間も使えるよう、手入力の欄を出す。
     */
    private fun showNotice(status: Int, message: String, action: Int? = null, onAction: () -> Unit = {}) {
        binding.textStatus.setText(status)
        setIndicator(Indicator.OFF)
        setListeningActive(false)
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
     * マイクが許可されていないとき。ボタン1つで許可へ進めるようにする（issue #15）。
     * まだ確認を出せるならその場で出し、出せなくなっていたら（2回断った後）アプリの設定を開く。
     * 設定で許可して戻ると、onResume で聞き取りが始まる。
     */
    private fun showPermissionDenied(): Unit =
        showNotice(R.string.status_mic_denied, getString(R.string.mic_permission_denied), R.string.action_allow_mic) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }

    // ---- 省電力（issue #21） ----

    private fun setListeningActive(active: Boolean) {
        if (listeningActive == active) return
        listeningActive = active
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            restartSilenceTimer()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            timers.removeCallbacks(stopForSilence)
        }
        wakeScreen()
    }

    /** 画面の明るさを戻す。聞き取り中なら、しばらく触れなければまた暗くする */
    private fun wakeScreen() {
        setDimmed(false)
        timers.removeCallbacks(dimScreen)
        if (listeningActive) timers.postDelayed(dimScreen, DIM_AFTER_MS)
    }

    private fun setDimmed(dimmed: Boolean) {
        val target = if (dimmed) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness == target) return
        window.attributes = window.attributes.apply { screenBrightness = target }
    }

    private fun restartSilenceTimer() {
        timers.removeCallbacks(stopForSilence)
        if (listeningActive) timers.postDelayed(stopForSilence, SILENCE_STOP_MS)
    }

    /** 声がないまま長く経ったので止める。画面も点けたままにしなくなる */
    private fun stopBecauseSilent() {
        speech.stop()
        flush()
        showNotice(R.string.status_stopped, getString(R.string.stopped_for_silence), R.string.action_resume) {
            startListening()
        }
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

    private companion object {
        const val MAX_LINES = 30

        /** デバッグ版だけで読む。聞き取った文の代わりに扱う文の配列 */
        const val EXTRA_DEBUG_SENTENCES = "jp.mimimemo.debug.SENTENCES"

        /** 触れずにこの時間が経ったら画面を暗くする */
        const val DIM_AFTER_MS = 20_000L
        const val DIM_BRIGHTNESS = 0.01f

        /** 聞き取った文をためておき、この間隔でまとめて調べる（issue #36） */
        const val BATCH_MS = 5 * 60_000L

        /** 声がないままこの時間が経ったら聞き取りを止める */
        const val SILENCE_STOP_MS = 10 * 60_000L
    }
}
