package jp.mimimemo

import android.content.Context
import androidx.core.content.edit

/** キーワード辞書（機能5）を SharedPreferences に保存する。 */
class KeywordStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }
            ?: TodoExtractor.DEFAULT_KEYWORDS

    /** 追加できたら true。空文字や登録済みなら false */
    fun add(word: String): Boolean {
        val w = word.trim()
        if (w.isEmpty() || '\n' in w) return false
        val current = load()
        if (w in current) return false
        save(current + w)
        return true
    }

    fun remove(word: String) = save(load() - word)

    fun reset() = prefs.edit { remove(KEY) }

    private fun save(words: List<String>) = prefs.edit { putString(KEY, words.joinToString("\n")) }

    private companion object {
        const val PREFS = "keywords"
        const val KEY = "keywords"
    }
}
