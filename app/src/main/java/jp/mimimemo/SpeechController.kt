package jp.mimimemo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 端末内の音声認識で会話を1文ずつ文字にする（機能1）。
 *
 * - Android 12 以降の createOnDeviceSpeechRecognizer だけを使う
 * - 端末内認識が使えない端末では通常の認識（サーバに音声が送られることがある）には切り替えず、
 *   [FatalError.OnDeviceUnavailable] を返す。画面側は手入力だけで使えるようにする（issue #9）
 * - Android 13 以降は連続認識（segmented session）でマイクを開けたまま、区切りごとに結果を受け取る。
 *   1文ごとに起動し直さないので、区切りの間の言葉を落としにくい（issue #42）。
 *   連続認識に対応していない認識器では、1文の認識が終わる・無音で止まるたびに自動で次を始める
 * - 結果の空白を取り除き、複数の候補からキーワードを含むものを選ぶ（[RecognizedText]）
 * - 日本語モデルが端末にないときはダウンロードを頼み、入るまで待ってから自動で始める（issue #14）
 * - 音声データは受け取らず、保存もしない（onBufferReceived は何もしない）
 *
 * メインスレッドから呼ぶこと。
 */
class SpeechController(
    private val context: Context,
    private val listener: ResultListener,
    /** 聞き取りやすくしたい言葉（キーワード辞書など）。聞き取りを始めるたびに読む */
    private val vocabulary: () -> List<String> = { emptyList() },
    /** 認識の候補が複数あるとき、これに当てはまるもの（キーワードを含むなど）を選ぶ */
    private val prefer: (String) -> Boolean = { false },
) : RecognitionListener {

    interface ResultListener {
        /** 1文の文字起こしが終わった */
        fun onSentence(text: String)

        /** 認識途中の文字列 */
        fun onPartial(text: String)

        fun onStateChanged(state: State)

        /** 続けられないエラー。聞き取りは止まっている */
        fun onFatalError(error: FatalError)
    }

    enum class State {
        STARTING, LISTENING, HEARING, STOPPED,

        /** 日本語モデルをダウンロード中。終わると自動で LISTENING になる */
        MODEL_DOWNLOADING,

        /** 日本語モデルがなく、ダウンロードも始まっていない（確認を閉じたなど）。[requestModelDownload] で頼み直せる */
        MODEL_REQUIRED,
    }

    sealed interface FatalError {
        /** 端末内認識がない、または日本語に対応していない */
        data object OnDeviceUnavailable : FatalError
        data object PermissionDenied : FatalError
        data class Repeated(val code: Int) : FatalError
    }

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    /** ダウンロードの確認を出したか。画面に戻るたびに何度も出さないよう、自動で出すのは1回だけ */
    private var downloadRequested = false
    private var waitingModel = false

    /** 「モデルは入っている」と返るのにエラー 13 が続いた回数 */
    private var installedButUnavailable = 0
    private var consecutiveErrors = 0
    private val handler = Handler(Looper.getMainLooper())

    private val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 連続認識：マイクを開けたまま、区切りごとに onSegmentResults で返してもらう。
            // 1回の長さは SESSION_MS。終わったら onEndOfSegmentedSession が来るので始め直す
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SESSION_MS)
            // 句読点などの整形。対応していない認識器は無視する
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
        }
    }

    fun start() {
        if (running) return
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        // 実機で止まったときに原因を追えるよう、状態とエラーコードだけをログに出す。認識した文は出さない（issue #17）
        Log.d(TAG, "start sdk=${Build.VERSION.SDK_INT} onDevice=$onDevice")
        if (!onDevice) {
            listener.onFatalError(FatalError.OnDeviceUnavailable)
            return
        }
        running = true
        waitingModel = false
        installedButUnavailable = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 語彙のヒント。対応していない認識器は無視する
            val words = vocabulary().take(MAX_BIASING_WORDS)
            if (words.isNotEmpty()) {
                recognizeIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(words))
            } else {
                recognizeIntent.removeExtra(RecognizerIntent.EXTRA_BIASING_STRINGS)
            }
        }
        consecutiveErrors = 0
        createRecognizer()
        listener.onStateChanged(State.STARTING)
        listen()
    }

    fun stop() {
        running = false
        waitingModel = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        listener.onStateChanged(State.STOPPED)
    }

    private fun createRecognizer() {
        destroyRecognizer()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            .also { it.setRecognitionListener(this) }
    }

    private fun destroyRecognizer() {
        recognizer?.run {
            cancel()
            destroy()
        }
        recognizer = null
    }

    private fun listen() {
        if (running) recognizer?.startListening(recognizeIntent)
    }

    private fun restart(delayMs: Long = RESTART_DELAY_MS, recreate: Boolean = false) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (recreate) createRecognizer()
            listen()
        }, delayMs)
    }

    /** 日本語モデルのダウンロードを頼み直す（[State.MODEL_REQUIRED] のときにボタンから呼ぶ） */
    fun requestModelDownload() {
        if (!running || !waitingModel || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        triggerDownload()
        schedulePoll(POLL_AFTER_REQUEST_MS)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun triggerDownload() {
        Log.d(TAG, "triggerModelDownload")
        downloadRequested = true
        recognizer?.triggerModelDownload(recognizeIntent)
    }

    /**
     * モデルが入るまで待つ。ダウンロードの確認は別の画面として出るので、その間この画面は
     * onPause で止まり、戻ってきたときにまたここへ来る。完了の通知には頼らず、入ったかどうかを定期的に調べる。
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun waitForModel() {
        waitingModel = true
        if (!downloadRequested) {
            triggerDownload()
            listener.onStateChanged(State.MODEL_DOWNLOADING)
            schedulePoll(POLL_AFTER_REQUEST_MS)
        } else {
            schedulePoll(RESTART_DELAY_MS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun schedulePoll(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ pollModel() }, delayMs)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun pollModel() {
        val r = recognizer
        if (!running || !waitingModel || r == null) return
        r.checkRecognitionSupport(recognizeIntent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (!running || !waitingModel || recognizer !== r) return
                val installed = support.installedOnDeviceLanguages.any(::isJapanese)
                val pending = support.pendingOnDeviceLanguages.any(::isJapanese)
                Log.d(TAG, "model: installed=$installed pending=$pending")
                when {
                    // 入っているはずなのに使えない状態が続く端末では、調べ続けて電池を使わないよう止める
                    installed && ++installedButUnavailable > MAX_INSTALLED_RETRIES ->
                        fatal(FatalError.OnDeviceUnavailable)

                    installed -> {
                        waitingModel = false
                        listener.onStateChanged(State.STARTING)
                        listen()
                    }
                    pending -> {
                        listener.onStateChanged(State.MODEL_DOWNLOADING)
                        schedulePoll(POLL_INTERVAL_MS)
                    }
                    // ダウンロードが始まっていない。頼み直されるまで調べ続けない（電池を使わない）
                    else -> listener.onStateChanged(State.MODEL_REQUIRED)
                }
            }

            override fun onError(error: Int) {
                // 調べられない端末では、実際に聞き取りを始めてみて確かめる（まだなければ onError(13) でここに戻る）
                if (!running || !waitingModel || recognizer !== r) return
                Log.d(TAG, "checkRecognitionSupport error=$error")
                listener.onStateChanged(State.MODEL_DOWNLOADING)
                handler.postDelayed({ if (waitingModel) listen() }, POLL_INTERVAL_MS)
            }
        })
    }

    /** 認識が実際に動いた（無音・結果が返った）ので、モデル待ちを終える */
    private fun modelIsWorking() {
        waitingModel = false
        installedButUnavailable = 0
    }

    private fun isJapanese(tag: String) = tag.startsWith("ja", ignoreCase = true)

    private fun fatal(error: FatalError) {
        Log.d(TAG, "fatal $error")
        stop()
        listener.onFatalError(error)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        // モデルがないときも、エラー 13 の前後にこれが届く端末がある。待っている間は聞き取り中にしない
        if (!waitingModel) listener.onStateChanged(State.LISTENING)
    }

    override fun onBeginningOfSpeech() {
        if (!waitingModel) listener.onStateChanged(State.HEARING)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            ?.let(RecognizedText::normalize)
        if (!text.isNullOrBlank()) listener.onPartial(text)
    }

    /** 連続認識に対応していない認識器：1文ごとにここへ来るので、次を始める */
    override fun onResults(results: Bundle?) {
        deliver(results, "onResults")
        listener.onStateChanged(State.LISTENING)
        restart()
    }

    /** 連続認識：区切りごとにここへ来る。マイクは開けたままなので始め直さない */
    override fun onSegmentResults(segmentResults: Bundle) {
        deliver(segmentResults, "onSegmentResults")
        listener.onStateChanged(State.LISTENING)
    }

    /** 連続認識の1回分（SESSION_MS）が終わった。続けて次を始める */
    override fun onEndOfSegmentedSession() {
        Log.d(TAG, "onEndOfSegmentedSession")
        if (!running) return
        listener.onStateChanged(State.LISTENING)
        restart()
    }

    private fun deliver(results: Bundle?, from: String) {
        consecutiveErrors = 0
        modelIsWorking()
        val hypotheses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val text = RecognizedText.choose(hypotheses, prefer)
        // 認識した文そのものは出さない（issue #17）
        Log.d(TAG, "$from hypotheses=${hypotheses.size} chars=${text?.length ?: 0}")
        if (!text.isNullOrEmpty()) listener.onSentence(text)
    }

    override fun onError(error: Int) {
        if (!running) return
        Log.d(TAG, "onError code=$error")
        when (error) {
            // 無音・聞き取れなかった：そのまま次を聞く
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                modelIsWorking()
                listener.onStateChanged(State.LISTENING)
                restart()
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal(FatalError.PermissionDenied)

            ERROR_LANGUAGE_UNAVAILABLE -> {
                // 端末内の日本語モデルが未ダウンロード
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) waitForModel()
                else fatal(FatalError.OnDeviceUnavailable)
            }

            ERROR_LANGUAGE_NOT_SUPPORTED -> fatal(FatalError.OnDeviceUnavailable)

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(BUSY_DELAY_MS, recreate = true)

            else -> {
                consecutiveErrors++
                if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) fatal(FatalError.Repeated(error))
                else restart(BUSY_DELAY_MS, recreate = true)
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private companion object {
        const val TAG = "MimiMemo"
        const val POLL_INTERVAL_MS = 2000L
        const val MAX_INSTALLED_RETRIES = 3
        const val MAX_BIASING_WORDS = 100

        /** 受け取る認識の候補の数（issue #42） */
        const val MAX_RESULTS = 5

        /** 連続認識1回の長さ。終わったらすぐ始め直す */
        const val SESSION_MS = 5 * 60_000

        /** 確認の画面が出るまで少しかかるので、頼んだ直後は「まだ始まっていない」と判定しないよう待つ */
        const val POLL_AFTER_REQUEST_MS = 4000L
        const val RESTART_DELAY_MS = 200L
        const val BUSY_DELAY_MS = 1000L
        const val MAX_CONSECUTIVE_ERRORS = 5

        // API 31 で追加された定数（minSdk 26 でも比較できるよう値で持つ）
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
