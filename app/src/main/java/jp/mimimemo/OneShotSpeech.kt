package jp.mimimemo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/**
 * 1回だけ聞き取る（題名の言い直し、issue #114）。端末内の認識だけを使い、サーバには送らない。
 * 結果は1つにまとめて [onResult] に渡す。聞き取れなければ null。
 */
@RequiresApi(Build.VERSION_CODES.S)
class OneShotSpeech(private val context: Context, private val onResult: (String?) -> Unit) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        cancel()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            it.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
            )
        }
    }

    fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish(text: String?) {
        cancel()
        onResult(text?.let(RecognizedText::normalize)?.takeIf { it.isNotBlank() })
    }

    override fun onResults(results: Bundle?) =
        finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

    override fun onError(error: Int) = finish(null)
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
