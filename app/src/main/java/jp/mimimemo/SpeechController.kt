package jp.mimimemo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 端末内の音声認識で会話を1文ずつ文字にする（機能1）。
 *
 * - Android 12 以降の createOnDeviceSpeechRecognizer だけを使う
 * - 端末内認識が使えない端末では通常の認識（サーバに音声が送られることがある）には切り替えず、
 *   [FatalError.OnDeviceUnavailable] を返す。画面側は手入力だけで使えるようにする（issue #9）
 * - 1文の認識が終わる・無音で止まるたびに、自動で次の聞き取りを始める
 * - 音声データは受け取らず、保存もしない（onBufferReceived は何もしない）
 *
 * メインスレッドから呼ぶこと。
 */
class SpeechController(
    private val context: Context,
    private val listener: ResultListener,
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

    enum class State { STARTING, LISTENING, HEARING, STOPPED }

    sealed interface FatalError {
        /** 端末内認識がない、または日本語に対応していない */
        data object OnDeviceUnavailable : FatalError
        data object PermissionDenied : FatalError
        data object ModelDownloading : FatalError
        data class Repeated(val code: Int) : FatalError
    }

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var downloadRequested = false
    private var consecutiveErrors = 0
    private val handler = Handler(Looper.getMainLooper())

    private val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun start() {
        if (running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            listener.onFatalError(FatalError.OnDeviceUnavailable)
            return
        }
        running = true
        consecutiveErrors = 0
        createRecognizer()
        listener.onStateChanged(State.STARTING)
        listen()
    }

    fun stop() {
        running = false
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

    private fun fatal(error: FatalError) {
        stop()
        listener.onFatalError(error)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        listener.onStateChanged(State.LISTENING)
    }

    override fun onBeginningOfSpeech() {
        listener.onStateChanged(State.HEARING)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) listener.onPartial(text)
    }

    override fun onResults(results: Bundle?) {
        consecutiveErrors = 0
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) listener.onSentence(text)
        listener.onStateChanged(State.LISTENING)
        restart()
    }

    override fun onError(error: Int) {
        if (!running) return
        when (error) {
            // 無音・聞き取れなかった：そのまま次を聞く
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                restart()
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fatal(FatalError.PermissionDenied)

            ERROR_LANGUAGE_UNAVAILABLE -> {
                // 端末内の日本語モデルが未ダウンロード
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // ダウンロード確認ダイアログを閉じると onResume で再開してまたここに来るので、
                    // 確認を出すのは1回だけにする
                    if (!downloadRequested) {
                        downloadRequested = true
                        recognizer?.triggerModelDownload(recognizeIntent)
                    }
                    fatal(FatalError.ModelDownloading)
                } else {
                    fatal(FatalError.OnDeviceUnavailable)
                }
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
        const val RESTART_DELAY_MS = 200L
        const val BUSY_DELAY_MS = 1000L
        const val MAX_CONSECUTIVE_ERRORS = 5

        // API 31 で追加された定数（minSdk 26 でも比較できるよう値で持つ）
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
