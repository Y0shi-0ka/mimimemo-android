package jp.mimimemo

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** ToDo を SQLite の todos テーブルに保存する。 */
class TodoDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    /** ToDo を変えたら、ホーム画面ウィジェットも描き直す（#97） */
    private fun changed() = runCatching { TodoWidget.refresh(appContext) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                due TEXT,
                tag TEXT,
                source_text TEXT,
                done INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(todo: Todo): Long = writableDatabase.insert(TABLE, null, toValues(todo)).also { changed() }

    fun update(todo: Todo): Int =
        writableDatabase.update(TABLE, toValues(todo), "id = ?", arrayOf(todo.id.toString())).also { changed() }

    fun setDone(id: Long, done: Boolean) {
        val values = ContentValues().apply { put("done", if (done) 1 else 0) }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun findById(id: Long): Todo? = query("id = ?", arrayOf(id.toString()), null).firstOrNull()

    /** 未完了の ToDo を期限が近い順に返す（期限なしは最後）。 */
    fun findAllOrderByDue(): List<Todo> = query("done = 0", null, "due IS NULL, due, id")

    /** 完了した ToDo。新しく登録したものから [limit] 件（#87） */
    fun findDone(limit: Int = 50): List<Todo> = query("done = 1", null, "id DESC LIMIT $limit")

    /** 指定した日が期限の未完了 ToDo（前日リマインド用）。 */
    fun findDueOn(date: LocalDate): List<Todo> =
        query("done = 0 AND due = ?", arrayOf(date.toString()), "id")

    /** 指定した日に登録した ToDo（今日のまとめ用）。完了済みも含む。 */
    fun findCreatedOn(date: LocalDate): List<Todo> =
        query("created_at LIKE ?", arrayOf("$date%"), "created_at, id")

    private fun query(selection: String, args: Array<String>?, orderBy: String?): List<Todo> {
        val c = readableDatabase.query(TABLE, null, selection, args, null, null, orderBy)
        try {
            return buildList { while (c.moveToNext()) add(fromCursor(c)) }
        } finally {
            c.close()
        }
    }

    private fun toValues(todo: Todo) = ContentValues().apply {
        put("title", todo.title)
        put("due", todo.due?.toString())
        put("tag", todo.tag)
        put("source_text", todo.sourceText)
        put("done", if (todo.done) 1 else 0)
        put("created_at", todo.createdAt.format(DATE_TIME))
    }

    private fun fromCursor(c: Cursor): Todo {
        fun str(name: String): String? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        return Todo(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            title = str("title").orEmpty(),
            due = str("due")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            tag = str("tag") ?: Todo.TAG_OTHER,
            sourceText = str("source_text"),
            done = c.getInt(c.getColumnIndexOrThrow("done")) == 1,
            createdAt = str("created_at")
                ?.let { runCatching { LocalDateTime.parse(it, DATE_TIME) }.getOrNull() }
                ?: LocalDateTime.now(),
        )
    }

    companion object {
        private const val DB_NAME = "mimimemo.db"
        private const val DB_VERSION = 1
        private const val TABLE = "todos"
        private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
