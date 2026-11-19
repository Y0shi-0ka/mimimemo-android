package jp.mimimemo

import android.app.ActivityManager
import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 端末内の小さな AI（LiteRT-LM）。会話は端末の外に出さない（issue #68、#9）。
 *
 * モデル（.litertlm）はアプリ用フォルダ `Android/data/jp.mimimemo/files/models/` に置く。
 * 常時はメモリに載せず、5分まとめのときだけ読み込んで、終わったら外す（電池とメモリのため）。
 * モデルがない・読み込めない・答えが読めないときは null を返し、呼び出し側はルールで判定する。
 */
class LocalLlm(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Mutex()

    /** 置いてあるモデル。複数あれば名前順で最初のもの */
    fun modelFile(): File? =
        appContext.getExternalFilesDir(MODEL_DIR)
            ?.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > 0 }
            ?.minByOrNull { it.name }

    val isAvailable: Boolean get() = modelFile() != null

    /** 読み込みで落ちたモデルを使わないための印（#120）。落ちる前に残るよう、すぐ書き込む */
    private val guard: LoadGuard = appContext.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE).let { prefs ->
        LoadGuard({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.commit() })
    }

    private fun idOf(file: File) = guard.idOf(file.name, file.length())

    /** 置いてあるモデルが、前に読み込みで落ちたもの（使っていない）か */
    fun isBlocked(): Boolean = modelFile()?.let { guard.isBad(idOf(it)) } ?: false

    /** モデルを入れ直した・外したら呼ぶ */
    fun resetGuard() = guard.reset()

    /**
     * 読み込めるだけのメモリがあるか。足りないまま読み込むと、OS にアプリごと止められ、
     * 常時聞き取りも止まってしまう（2GB のエミュレータで確認、#68）。足りなければルールで判定する
     */
    fun hasEnoughMemory(): Boolean {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return !info.lowMemory && info.totalMem >= MIN_TOTAL_MEMORY && info.availMem >= MIN_AVAILABLE_MEMORY
    }

    /**
     * まとめた文から ToDo を選んでもらう。使えないときは null。
     * 時間がかかる（読み込みに数秒、答えに数秒〜十数秒）ので、メインスレッドでは呼ばない。
     */
    suspend fun findTasks(sentences: List<String>): List<AiTaskPrompt.Task>? = withContext(Dispatchers.Default) {
        val file = modelFile() ?: return@withContext null
        if (sentences.isEmpty()) return@withContext emptyList()
        if (!hasEnoughMemory()) {
            Log.d(TAG, "llm skipped: not enough memory")
            return@withContext null
        }
        lock.withLock {
            val id = idOf(file)
            if (!guard.canLoad(id)) {
                Log.d(TAG, "llm skipped: model crashed before")
                return@withContext null
            }
            val started = System.currentTimeMillis()
            guard.begin(id)
            runCatching {
                Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = cacheDirIfRoomy())).use { engine ->
                    engine.initialize()
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(AiTaskPrompt.SYSTEM),
                        // 毎回同じ答えになるよう、いちばん確からしい言葉だけを選ぶ
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                        maxOutputToken = MAX_OUTPUT_TOKENS,
                        // 考える過程（Qwen3 は既定で出す）は不要。時間と電池を使うので切る
                        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    )
                    // 文が多いと小さなモデルは取りこぼすので、CHUNK 文ずつに分けて聞く（ユーザーの指摘）
                    sentences.chunked(CHUNK).withIndex().flatMap { (n, chunk) ->
                        engine.createConversation(config).use { conversation ->
                            val reply = conversation.sendMessage(AiTaskPrompt.userMessage(chunk))
                            val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            AiTaskPrompt.parse(stripThinking(text), chunk.size).map { it.copy(index = it.index + n * CHUNK) }
                        }
                    }
                }
            }.onSuccess {
                // 文そのものは出さない（#17）
                Log.d(TAG, "llm: sentences=${sentences.size} tasks=${it.size} ms=${System.currentTimeMillis() - started}")
            }.onFailure {
                Log.d(TAG, "llm failed: ${it.javaClass.simpleName}")
            }.also { guard.end() }.getOrNull()
        }
    }

    /**
     * 2回目からの読み込みを速くするキャッシュの置き場所。空きが少ないときは使わない。
     * キャッシュの書き込みに失敗すると、ライブラリの中でアプリごと落ちるため（エミュレータで確認、#68）
     */
    private fun cacheDirIfRoomy(): String {
        val dir = appContext.cacheDir
        // OS が片付けられるキャッシュも含めて、使える容量を数える
        val storage = appContext.getSystemService(StorageManager::class.java)
        val free = runCatching { storage.getAllocatableBytes(storage.getUuidForPath(dir)) }.getOrDefault(0L)
        // 置き場所を渡さないと、ライブラリはモデルの隣などに書こうとするので、「使わない」をはっきり渡す
        return if (free >= MIN_FREE_FOR_CACHE) dir.path else NO_CACHE
    }

    /** 考える過程が混じったときは取り除く */
    private fun stripThinking(text: String): String = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    companion object {
        private const val TAG = "MimiMemo"
        const val MODEL_DIR = "models"
        private const val GUARD_PREFS = "llm_guard"
        private const val MAX_OUTPUT_TOKENS = 512

        /** 1回に聞く文の数。多すぎると小さなモデルは取りこぼす */
        const val CHUNK = 12

        /** AI を使う端末のメモリの下限と、読み込む時点で空いていてほしいメモリ */
        // 既定のモデル（Qwen2.5-1.5B、q8）は動かすときに約 2.2GB 使う
        private const val MIN_TOTAL_MEMORY = 6L * 1024 * 1024 * 1024
        private const val MIN_AVAILABLE_MEMORY = 2560L * 1024 * 1024

        /** LiteRT-LM で「キャッシュを使わない」を表す値 */
        private const val NO_CACHE = ":nocache"

        /** キャッシュを使うのに必要な空き容量 */
        private const val MIN_FREE_FOR_CACHE = 1024L * 1024 * 1024
    }
}
