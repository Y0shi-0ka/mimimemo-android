package jp.mimimemo

import android.content.Context
import androidx.core.content.edit

/**
 * 自分で足したタグ（科目名など、issue #10）。SharedPreferences に保存する。
 * 文中にこの名前があれば、そのタグを自動で付ける（[TodoExtractor.guessTag]）。
 */
class TagStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** 札に出すタグ：足したもの・決まった5つ（「その他」は最後） */
    fun all(): List<String> = allTags(load())

    /** 追加できたら true。空・長すぎる・すでにある（決まった5つを含む）なら false */
    fun add(tag: String): Boolean {
        val t = tag.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH || '\n' in t || t in all()) return false
        prefs.edit { putString(KEY, (load() + t).joinToString("\n")) }
        return true
    }

    fun remove(tag: String) = prefs.edit { putString(KEY, (load() - tag).joinToString("\n")) }

    companion object {
        private const val PREFS = "tags"
        private const val KEY = "tags"
        const val MAX_LENGTH = 12

        fun allTags(custom: List<String>): List<String> =
            (custom + Todo.TAGS.filter { it != Todo.TAG_OTHER } + Todo.TAG_OTHER).distinct()
    }
}
