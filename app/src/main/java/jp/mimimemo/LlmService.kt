package jp.mimimemo

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 端末内 AI を、聞き取りとは別のプロセス（:llm）で動かす（issue #148）。
 *
 * AI は動かすと 3GB 前後のメモリを使い、足りなければ OS にプロセスごと止められる（実機 Pixel 7a で PSS 3.1GB、OOM）。
 * 同じプロセスだと常時の聞き取りまで止まるので分けた。止められても落ちるのはこのプロセスだけで、
 * 頼んだ側（[LlmClient]）はその回をルールだけで続ける。
 * 1回答えたら頼んだ側が切り離し、このプロセスも終わらせてメモリを返す。
 */
class LlmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })

    override fun onCreate() {
        super.onCreate()
        forgiveMemoryKill()
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // AI のメモリを確実に返す（キャッシュとして残さない）
        Process.killProcess(Process.myPid())
    }

    private fun handle(msg: Message) {
        if (msg.what != MSG_FIND) return
        val sentences = msg.data.getStringArrayList(KEY_SENTENCES) ?: return
        val reply = msg.replyTo ?: return
        scope.launch {
            val tasks = LocalLlm(this@LlmService).findTasks(sentences)
            val out = Message.obtain(null, MSG_RESULT).apply {
                data = Bundle().apply { tasks?.let { putString(KEY_TASKS, encode(it)) } }
            }
            runCatching { reply.send(out) }
        }
    }

    /**
     * 前回このプロセスがメモリ不足で止められただけなら、「読み込みで落ちたモデル」の印（#120）を付けない。
     * メモリ不足は他のアプリしだいの一時的なもので、モデルが壊れているわけではないため。
     */
    private fun forgiveMemoryKill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = getSystemService(ActivityManager::class.java) ?: return
        val last = runCatching { am.getHistoricalProcessExitReasons(packageName, 0, 10) }.getOrDefault(emptyList())
            .firstOrNull { it.processName == "$packageName$PROCESS_SUFFIX" } ?: return
        if (last.reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
            Log.d(TAG, "llm: last run was killed for memory; not blocking the model")
            LocalLlm(this).forgiveInterruptedLoad()
        }
    }

    companion object {
        private const val TAG = "MimiMemo"
        const val PROCESS_SUFFIX = ":llm"
        const val MSG_FIND = 1
        const val MSG_RESULT = 2
        const val KEY_SENTENCES = "sentences"
        const val KEY_TASKS = "tasks"

        internal fun encode(tasks: List<AiTaskPrompt.Task>): String = JSONArray().apply {
            tasks.forEach { t ->
                put(JSONObject().put("index", t.index).put("title", t.title).put("due", t.dueText).put("tag", t.tag))
            }
        }.toString()

        internal fun decode(json: String?): List<AiTaskPrompt.Task>? {
            json ?: return null
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                AiTaskPrompt.Task(
                    index = o.getInt("index"),
                    title = o.getString("title"),
                    dueText = o.optString("due").takeIf { !o.isNull("due") && it.isNotEmpty() },
                    tag = o.optString("tag").takeIf { !o.isNull("tag") && it.isNotEmpty() },
                )
            }
        }
    }
}
