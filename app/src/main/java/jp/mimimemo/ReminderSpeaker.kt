package jp.mimimemo

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * TextToSpeech で読み上げる（機能4）。
 * 初期化が終わる前に speak() されたものは、終わってから順に読み上げる。
 */
class ReminderSpeaker(
    context: Context,
    private val onUnavailable: () -> Unit = {},
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var failed = false
    private val pending = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return fail()
        val result = tts.setLanguage(Locale.JAPAN)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return fail()
        ready = true
        pending.forEach(::speakNow)
        pending.clear()
    }

    fun speak(text: String) {
        when {
            failed -> onUnavailable()
            ready -> speakNow(text)
            else -> pending += text
        }
    }

    /** 明日が期限の ToDo を読み上げる。読み上げるものがなければ false */
    fun remindDueTomorrow(todos: List<Todo>): Boolean {
        if (todos.isEmpty()) return false
        speak(SpeechTexts.reminder(todos))
        return true
    }

    fun stop() {
        pending.clear()
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "mimimemo-${System.nanoTime()}")
    }

    private fun fail() {
        failed = true
        if (pending.isNotEmpty()) {
            pending.clear()
            onUnavailable()
        }
    }
}
