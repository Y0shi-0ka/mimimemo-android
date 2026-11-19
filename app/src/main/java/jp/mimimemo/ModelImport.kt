package jp.mimimemo

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 端末内 AI のモデル（.litertlm）を、選んだファイルからアプリのフォルダへ取り込む（issue #74）。
 * インターネットの権限は使わない。取り込めたら、前のモデルは消す（1つだけ持つ）。
 */
class ModelImport(context: Context) {

    private val appContext = context.applicationContext

    sealed interface Result {
        data class Done(val file: File) : Result
        data object NotModel : Result
        data class NoSpace(val needBytes: Long, val freeBytes: Long) : Result
        data object Failed : Result
    }

    /** 名前と大きさ（わからなければ -1） */
    fun describe(uri: Uri): Pair<String, Long>? {
        val c = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?: return null
        try {
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: return null
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            return name to size
        } finally {
            c.close()
        }
    }

    /** OS が片付けられるキャッシュも含めて、使える容量 */
    private fun freeBytes(dir: File): Long {
        val storage = appContext.getSystemService(StorageManager::class.java) ?: return 0L
        return runCatching { storage.getAllocatableBytes(storage.getUuidForPath(dir)) }.getOrDefault(0L)
    }

    suspend fun import(uri: Uri, onProgress: (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val (name, size) = describe(uri) ?: return@withContext Result.Failed
        if (!isModelName(name)) return@withContext Result.NotModel
        val dir = appContext.getExternalFilesDir(LocalLlm.MODEL_DIR) ?: return@withContext Result.Failed
        dir.mkdirs()
        // 大きさがわかるときは、余裕（200MB）を見て空きを確かめる
        val free = freeBytes(dir)
        if (size > 0 && free < size + MARGIN_BYTES) {
            return@withContext Result.NoSpace(size + MARGIN_BYTES, free)
        }
        val part = File(dir, "$name.part")
        try {
            val inp = appContext.contentResolver.openInputStream(uri) ?: return@withContext Result.Failed
            try {
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        ensureActive()
                        val n = inp.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (size > 0) {
                            val percent = (copied * 100 / size).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
            } finally {
                inp.close()
            }
            val target = File(dir, name)
            // 前のモデルは消して、1つだけにする
            dir.listFiles { f -> f.isFile && f != part }?.forEach { it.delete() }
            if (!part.renameTo(target)) throw IOException("rename")
            Result.Done(target)
        } catch (e: IOException) {
            part.delete()
            Result.Failed
        } finally {
            // 取り消されたときも半端なファイルは残さない
            if (part.exists()) part.delete()
        }
    }

    /** 取り込んだモデルを消す（AI を使わずルールだけにする） */
    fun remove(): Boolean {
        val dir = appContext.getExternalFilesDir(LocalLlm.MODEL_DIR) ?: return false
        return dir.listFiles()?.all { it.delete() } ?: true
    }

    companion object {
        private const val BUFFER = 1 shl 16
        private const val MARGIN_BYTES = 200L * 1024 * 1024

        fun isModelName(name: String) = name.endsWith(".litertlm", ignoreCase = true)
    }
}
