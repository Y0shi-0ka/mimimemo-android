package jp.mimimemo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
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
import java.time.LocalDateTime

/**
 * 画面を閉じても聞き取りを続ける前面サービス（issue #44）。
 *
 * - 端末内の認識（[SpeechController]）で文字にし、文はメモリにためて5分ごとにまとめて候補にする（#36）
 * - 候補・状態は [ListeningHub] に置き、画面はそれを表示する
 * - 動いている間は通知を出し続ける（バックグラウンドでマイクを使うための OS の決まり。動作中であることも示す）。
 *   通知から止められる
 * - 音声・文字起こしは保存しない。DB に書くのは、画面で候補を追加したときだけ
 * - 電池が少なくなったら（充電中でなければ）一時停止し、充電・回復で再開する（#64）
 */
class ListeningService : Service(), SpeechController.ResultListener {

    private lateinit var speech: SpeechController
    private lateinit var extractor: TodoExtractor
    private lateinit var llm: LocalLlm
    private val parser = DateParser()
    private val batch = SentenceBatch()
    private val batchLineIds = mutableListOf<Long>()
    private val deduper = CandidateDeduper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val flushBatch = Runnable { flush() }
    private var started = false

    /** 電池が少ないので一時停止中（#64） */
    private var paused = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_LOW -> if (!isCharging()) pauseForBattery()
                Intent.ACTION_BATTERY_OKAY, Intent.ACTION_POWER_CONNECTED -> resume()
            }
        }
    }
    private var batteryReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ListeningHub.attach(this)
        // 聞き取りが始まった・止まったら、クイック設定のタイルを合わせる（#85）
        val app = applicationContext
        ListeningHub.onStatusChanged = { ListeningTileService.refresh(app) }
        val keywords = KeywordStore(this)
        extractor = TodoExtractor(keywords = keywords::load, ai = UnavailableLocalAiClient, customTags = TagStore(this)::load)
        llm = LocalLlm(this)
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
            ACTION_RESUME -> resume()
            ACTION_ACCEPT, ACTION_REJECT -> {
                decideFromNotification(intent.action == ACTION_ACCEPT, intent.getLongExtra(EXTRA_CANDIDATE_ID, 0))
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        // デバッグ版だけ：adb から渡した文を、聞き取った文と同じ流れで扱う（実機で確かめるため）
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent?.getStringArrayExtra(EXTRA_DEBUG_SENTENCES)?.forEach(::addSentence)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (batteryReceiverRegistered) unregisterReceiver(batteryReceiver)
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
        scope.launch { ListeningHub.candidates.collect { updateNotification() } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(this, batteryReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        batteryReceiverRegistered = true
        // 始めた時点ですでに少なく、充電もしていなければ、一時停止の状態で始める
        if (isBatteryLow() && !isCharging()) {
            paused = true
            ListeningHub.setStatus(ListeningHub.Status.PausedLowBattery)
            updateNotification()
        } else {
            speech.start()
        }
    }

    // ---- 電池が少ないときの一時停止（#64） ----

    private fun batteryManager() = getSystemService(BatteryManager::class.java)

    private fun isCharging(): Boolean = batteryManager()?.isCharging == true

    private fun isBatteryLow(): Boolean =
        (batteryManager()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) <= LOW_BATTERY_PERCENT

    /** 話している途中の文を受け取り、ためた分をまとめてから一時停止する */
    private fun pauseForBattery() {
        if (!started || paused) return
        paused = true
        Log.d(TAG, "pause: low battery")
        speech.finish {
            flush {
                ListeningHub.setPartial("")
                ListeningHub.setStatus(ListeningHub.Status.PausedLowBattery)
                updateNotification()
            }
        }
    }

    private fun resume() {
        if (!started || !paused) return
        paused = false
        Log.d(TAG, "resume")
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
        // 連続認識の区切りには文がいくつも入るので、文に分けてためる（#69）
        RecognizedText.splitSentences(text).forEach(::addSentence)
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
        batch.add(text, LocalDateTime.now())
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
            ListeningHub.setAnalyzing(true)
            // 「あと卵もね」のような直前の候補の続きも候補にする（#80）
            val found = context.map { null } + SentenceBatch.continueFromPrevious(sentences, findCandidates(sentences))
            val filled = SentenceBatch.fillDueFromContext(context + sentences, found) { parser.parse(it.text, it.on) }
                .drop(context.size)
            val marked = mutableSetOf<Long>()
            // すでに一覧にある ToDo と同じ候補は出さない（#72）
            val existing = runCatching { TodoDbHelper(this@ListeningService).use { it.findAllOrderByDue() } }.getOrDefault(emptyList())
            filled.forEachIndexed { i, todo ->
                // 言い直し・聞き返しで同じ内容がまた来たら、候補は増やさない
                if (todo == null || CandidateDeduper.isAlreadyListed(todo, existing) || !deduper.isNew(todo)) return@forEachIndexed
                marked += ids[i]
                // 候補には聞いた時刻を持たせる（カードに出す、#62）。追加するときに登録日時は付け直す
                ListeningHub.addCandidate(todo.copy(createdAt = sentences[i].at))
            }
            ListeningHub.markCandidates(marked)
            ListeningHub.setAnalyzing(false)
            updateNotification()
            then()
        }
    }

    /**
     * まとめた文から候補を探す。ルール（#66）と端末内の AI（#68）の、どちらかが拾った文を候補にする。
     * 評価セット（#71）で、ルールだけ：適合率 0.79・再現率 0.65、AI だけ：0.63・0.74、
     * どちらか：0.63・0.96 だった。候補は左に払えば捨てられるが、見逃しは戻らないので、見逃しの少なさを取る。
     * 題名は AI のもの（「牛乳を買う」のように整っている）を優先する。AI が使えないときはルールだけ。
     * 期限は、その文を聞いた日を基準に数える（#60）。
     */
    private suspend fun findCandidates(sentences: List<SentenceBatch.Heard>): List<Todo?> {
        val byRule = sentences.map { extractor.extract(it.text, it.on) }
        val byAi = llm.findTasks(sentences.map { it.text })?.associateBy { it.index } ?: return byRule
        return sentences.mapIndexed { i, heard ->
            val rule = byRule[i]
            val task = byAi[i] ?: return@mapIndexed rule
            Todo(
                title = task.title,
                due = task.dueText?.let { parser.parse(it, heard.on) } ?: rule?.due ?: parser.parse(heard.text, heard.on),
                // 自分で足したタグ（科目名）が文中にあれば、AI のタグより優先する（#10）
                tag = extractor.customTag(heard.text) ?: task.tag ?: rule?.tag ?: extractor.guessTag(heard.text),
                sourceText = heard.text,
            )
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
        val candidates = ListeningHub.candidates.value
        val top = candidates.firstOrNull()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (paused) R.string.status_paused_battery else R.string.status_listening))
            .setContentIntent(open)
        if (top != null) {
            // 先頭の候補を出し、通知から直接「追加」「しない」で決められるようにする（#83）
            val detail = listOfNotNull(top.title, top.due?.let { DueFormatter.short(it) }).joinToString("・")
            builder.setContentText(getString(R.string.notification_top_candidate, candidates.size, detail))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_top_candidate, candidates.size, detail)))
                .addAction(R.drawable.ic_check, getString(R.string.swipe_add), candidateAction(ACTION_ACCEPT, top.id, 2))
                .addAction(R.drawable.ic_close, getString(R.string.swipe_skip), candidateAction(ACTION_REJECT, top.id, 3))
        }
        return builder
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 通知のボタンから候補を決める。押したときの候補（id）だけを決める（その間に変わっていたら何もしない） */
    private fun candidateAction(action: String, candidateId: Long, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, ListeningService::class.java).setAction(action).putExtra(EXTRA_CANDIDATE_ID, candidateId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 通知の「追加」「しない」（#83） */
    private fun decideFromNotification(accept: Boolean, candidateId: Long) {
        val candidate = ListeningHub.candidates.value.firstOrNull { it.id == candidateId } ?: return
        ListeningHub.removeCandidate(candidate) ?: return
        if (accept) {
            runCatching {
                TodoDbHelper(this).use { it.insert(candidate.copy(id = 0, createdAt = java.time.LocalDateTime.now())) }
                ListeningHub.dropSameAs(candidate)
            }.onFailure {
                // 保存できなければ候補に戻す
                ListeningHub.restoreCandidate(candidate, 0)
            }
        }
        updateNotification()
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
        private const val ACTION_RESUME = "jp.mimimemo.action.RESUME"
        private const val ACTION_ACCEPT = "jp.mimimemo.action.ACCEPT"
        private const val ACTION_REJECT = "jp.mimimemo.action.REJECT"
        private const val EXTRA_CANDIDATE_ID = "jp.mimimemo.extra.CANDIDATE_ID"

        /** この残量（%）以下で充電していなければ、始めるときに一時停止にする */
        private const val LOW_BATTERY_PERCENT = 15

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
        fun resume(context: Context) = send(context, ACTION_RESUME)

        private fun send(context: Context, action: String) {
            if (!ListeningHub.isRunning) return
            context.startService(Intent(context, ListeningService::class.java).setAction(action))
        }
    }
}
