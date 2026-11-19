package jp.mimimemo

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 決める前の候補を端末に残す（issue #55）。
 * 常時聞き取りでためた候補が、OS にアプリを止められても消えないようにする。
 * 残すのは候補（やること・期限・タグ・元の発言・聞いた時刻）だけで、音声や文字起こしの全文は残さない。
 */
class CandidateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Todo> = decode(prefs.getString(KEY, null))

    fun save(candidates: List<Todo>) = prefs.edit { putString(KEY, encode(candidates)) }

    companion object {
        private const val PREFS = "candidates"
        private const val KEY = "pending"

        internal fun encode(candidates: List<Todo>): String =
            JSONArray(
                candidates.map { c ->
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("due", c.due?.toString() ?: JSONObject.NULL)
                        .put("tag", c.tag)
                        .put("source", c.sourceText ?: JSONObject.NULL)
                        .put("heard", c.createdAt.toString())
                }
            ).toString()

        /** 壊れた値は読み飛ばす（保存できなかった候補で落ちないように） */
        internal fun decode(json: String?): List<Todo> {
            if (json.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    Todo(
                        id = o.getLong("id"),
                        title = o.getString("title"),
                        due = o.optString("due").takeIf { !o.isNull("due") && it.isNotEmpty() }?.let(LocalDate::parse),
                        tag = o.optString("tag", Todo.TAG_OTHER),
                        sourceText = o.optString("source").takeIf { !o.isNull("source") },
                        // 聞いた時刻（#62）。古い形式で残っていなければ今
                        createdAt = o.optString("heard").takeIf { it.isNotEmpty() }
                            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: LocalDateTime.now(),
                    )
                }.getOrNull()
            }
        }
    }
}
