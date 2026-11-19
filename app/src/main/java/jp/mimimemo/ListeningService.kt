package jp.mimimemo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 画面を閉じても聞き取りを続ける前面サービス（issue #44）。
 *
 * - 端末内の認識（[SpeechController]）で文字にし、文はメモリにためて5分ごとにまとめて候補にする（#36）
 * - 候補・状態は [ListeningHub] に置き、画面はそれを表示する
 * - 動いている間は通知を出し続ける（バックグラウンドでマイクを使うための OS の決まり。動作中であることも示す）。
 *   通知から止められる
 * - 音声・文字起こしは保存しない。DB に書くのは、画面で候補を追加したときだけ
 */
class ListeningService : Service(), SpeechController.ResultListener {

    private lateinit var speech: SpeechController
    private lateinit var extractor: TodoExtractor
    private val parser = DateParser()
    private val batch = SentenceBatch()
    private val batchLineIds = mutableListOf<Long>()
    private val deduper = CandidateDeduper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val flushBatch = Runnable { flush() }
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ListeningHub.attach(this)
        val keywords = KeywordStore(this)
        extractor = TodoExtractor(keywords = keywords::load, ai = UnavailableLocalAiClient)
        speech = SpeechController(
            this,
            this,
            vocabulary = { TodoExtractor.vocabulary(keywords.load()) },
            prefer = extractor::isCandidate,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                return START_NOT_STICKY
            }
            ACTION_FLUSH -> flush()
            ACTION_DOWNLOAD_MODEL -> speech.requestModelDownload()
            else -> startListening()
        }
        // デバッグ版だけ：adb から渡した文を、聞き取った文と同じ流れで扱う（実機で確かめるため）
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getStringArrayExtra(EXTRA_DEBUG_SENTENCES)?.forEach(::addSentence)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech.stop()
        scope.cancel()
        ListeningHub.setPartial("")
        if (ListeningHub.isRunning) ListeningHub.setStatus(ListeningHub.Status.Off)
        super.onDestroy()
    }

    private fun startListening() {
        if (started) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ListeningHub.setStatus(ListeningHub.Status.PermissionDenied)
            stopSelf()
            return
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: RuntimeException) {
            // バックグラウンドから始めようとした・許可がないなど。画面から始め直してもらう
            Log.d(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            ListeningHub.setStatus(ListeningHub.Status.Failed(-1))
            stopSelf()
            return
        }
        started = true
        speech.start()
    }

    /** 話している途中の文を受け取り（#53）、ためた分をまとめてから止める */
    private fun stopListening() {
        val wasStarted = started
        started = false
        val stopAll = {
            flush {
                ListeningHub.setStatus(ListeningHub.Status.Off)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (wasStarted) speech.finish(stopAll) else stopAll()
    }

    // ---- SpeechController.ResultListener ----

    override fun onSentence(text: String) {
        ListeningHub.setPartial("")
        addSentence(text)
    }

    override fun onPartial(text: String) {
        ListeningHub.setPartial(text)
        if (ListeningHub.status.value is ListeningHub.Status.Listening) {
            ListeningHub.setStatus(ListeningHub.Status.Listening(hearing = true))
        }
    }

    override fun onStateChanged(state: SpeechController.State) {
        val status = when (state) {
            SpeechController.State.STARTING -> ListeningHub.Status.Starting
            SpeechController.State.LISTENING -> {
                // 次の聞き取りが始まった。確定せずに打ち切られた途中の文字は消す（#31）
                ListeningHub.setPartial("")
                ListeningHub.Status.Listening(hearing = false)
            }
            SpeechController.State.HEARING -> ListeningHub.Status.Listening(hearing = true)
            SpeechController.State.STOPPED -> return
            SpeechController.State.MODEL_DOWNLOADING -> ListeningHub.Status.ModelDownloading
            SpeechController.State.MODEL_REQUIRED -> ListeningHub.Status.ModelRequired
        }
        ListeningHub.setStatus(status)
        updateNotification()
    }

    override fun onFatalError(error: SpeechController.FatalError) {
        started = false
        ListeningHub.setStatus(
            when (error) {
                SpeechController.FatalError.OnDeviceUnavailable -> ListeningHub.Status.Unavailable
                SpeechController.FatalError.PermissionDenied -> ListeningHub.Status.PermissionDenied
                is SpeechController.FatalError.Repeated -> ListeningHub.Status.Failed(error.code)
            }
        )
        // マイクを使えないのに通知を出し続けない
        flush {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ---- ためてまとめる（#36） ----

    private fun addSentence(text: String) {
        val id = ListeningHub.addLine(text)
        batch.add(text)
        batchLineIds += id
        if (batch.size == 1) handler.postDelayed(flushBatch, BATCH_MS)
        ListeningHub.setPending(batch.size)
    }

    private fun flush(then: () -> Unit = {}) {
        handler.removeCallbacks(flushBatch)
        val (context, sentences) = batch.drain()
        val ids = batchLineIds.toList()
        batchLineIds.clear()
        ListeningHub.setPending(0)
        if (sentences.isEmpty()) {
            then()
            return
        }
        scope.launch {
            val found = context.map { null } + sentences.map { extractor.extract(it) }
            val today = LocalDate.now()
            val filled = SentenceBatch.fillDueFromContext(context + sentences, found) { parser.parse(it, today) }
                .drop(context.size)
            val marked = mutableSetOf<Long>()
            filled.forEachIndexed { i, todo ->
                // 言い直し・聞き返しで同じ内容がまた来たら、候補は増やさない
                if (todo == null || !deduper.isNew(todo)) return@forEachIndexed
                marked += ids[i]
                ListeningHub.addCandidate(todo)
            }
            ListeningHub.markCandidates(marked)
            updateNotification()
            then()
        }
    }

    // ---- 通知 ----

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ListeningActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val count = ListeningHub.candidates.value.size
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.status_listening))
            .setContentText(if (count > 0) getString(R.string.notification_candidates, count) else null)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (!started) return
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "MimiMemo"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "jp.mimimemo.action.STOP"
        private const val ACTION_FLUSH = "jp.mimimemo.action.FLUSH"
        private const val ACTION_DOWNLOAD_MODEL = "jp.mimimemo.action.DOWNLOAD_MODEL"

        /** デバッグ版だけで読む。聞き取った文の代わりに扱う文の配列 */
        const val EXTRA_DEBUG_SENTENCES = "jp.mimimemo.debug.SENTENCES"

        /** 聞き取った文をためておき、この間隔でまとめて調べる（#36） */
        private const val BATCH_MS = 5 * 60_000L

        /** 画面（前面にあるとき）から聞き取りを始める */
        fun start(context: Context, debugSentences: Array<String>? = null) {
            val intent = Intent(context, ListeningService::class.java)
            debugSentences?.let { intent.putExtra(EXTRA_DEBUG_SENTENCES, it) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = send(context, ACTION_STOP)
        fun flush(context: Context) = send(context, ACTION_FLUSH)
        fun requestModelDownload(context: Context) = send(context, ACTION_DOWNLOAD_MODEL)

        private fun send(context: Context, action: String) {
            if (!ListeningHub.isRunning) return
            context.startService(Intent(context, ListeningService::class.java).setAction(action))
        }
    }
}
