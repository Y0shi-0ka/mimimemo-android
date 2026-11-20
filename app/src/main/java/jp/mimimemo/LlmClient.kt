package jp.mimimemo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 別プロセスの端末内 AI（[LlmService]）に、まとめた文から ToDo を選んでもらう（issue #148）。
 * AI のプロセスが止められた・時間がかかりすぎた・モデルがないときは null を返し、呼び出し側はルールだけで続ける。
 */
class LlmClient(context: Context) {

    private val appContext = context.applicationContext

    suspend fun findTasks(sentences: List<String>): List<AiTaskPrompt.Task>? {
        if (sentences.isEmpty()) return emptyList()
        // モデルがなければ、プロセスを起こさない
        if (LocalLlm(appContext).modelFile() == null) return null
        return withTimeoutOrNull(TIMEOUT_MS) { request(sentences) }
    }

    private suspend fun request(sentences: List<String>): List<AiTaskPrompt.Task>? = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun finish(result: List<AiTaskPrompt.Task>?) {
            if (!done.compareAndSet(false, true)) return
            // 切り離すと AI のプロセスも終わり、メモリが返る
            runCatching { appContext.unbindService(connection) }
            if (cont.isActive) cont.resume(result)
        }

        val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == LlmService.MSG_RESULT) finish(LlmService.decode(msg.data.getString(LlmService.KEY_TASKS)))
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                // AI のプロセスが止められたら（メモリ不足など）、その回は AI なしで続ける
                runCatching {
                    binder.linkToDeath({
                        // 答えを受け取ったあとは、こちらから終わらせたので何もしない
                        if (!done.get()) {
                            Log.d(TAG, "llm process died; using rules only")
                            finish(null)
                        }
                    }, 0)
                }.onFailure { finish(null); return }
                val msg = Message.obtain(null, LlmService.MSG_FIND).apply {
                    data = Bundle().apply { putStringArrayList(LlmService.KEY_SENTENCES, ArrayList(sentences)) }
                    replyTo = replies
                }
                runCatching { Messenger(binder).send(msg) }.onFailure { finish(null) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = finish(null)
            override fun onBindingDied(name: ComponentName?) = finish(null)
            override fun onNullBinding(name: ComponentName?) = finish(null)
        }
        cont.invokeOnCancellation { finish(null) }
        val bound = runCatching {
            appContext.bindService(Intent(appContext, LlmService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) finish(null)
    }

    private companion object {
        const val TAG = "MimiMemo"
        /** 読み込みと答えで普段は30秒ほど。これを超えたら AI なしで続ける */
        const val TIMEOUT_MS = 120_000L
    }
}
