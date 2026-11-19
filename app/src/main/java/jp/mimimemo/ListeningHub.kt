package jp.mimimemo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 聞き取りの状態・文字起こし・確認待ちの候補を、サービスと画面で共有する（issue #44）。
 * 聞き取りは [ListeningService] が受け持ち、画面はここを見て表示するだけ。
 * 文字起こしはプロセスの中にだけあり、保存しない（アプリが終われば消える）。
 * 決める前の候補だけは [CandidateStore] に残し、アプリが止められても消えないようにする（issue #55）。
 */
object ListeningHub {

    /** 文字起こしの1行。まとめて調べて候補になったら candidate が true */
    data class Line(val id: Long, val text: String, val candidate: Boolean = false)

    sealed interface Status {
        data object Off : Status
        data object Starting : Status
        /** hearing：声を聞き取っている最中 */
        data class Listening(val hearing: Boolean) : Status
        /** 電池が少ないので一時停止中。充電・回復で自動で再開する（#64） */
        data object PausedLowBattery : Status
        data object ModelDownloading : Status
        data object ModelRequired : Status
        data object Unavailable : Status
        data object PermissionDenied : Status
        data class Failed(val code: Int) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    private val _partial = MutableStateFlow("")
    private val _pending = MutableStateFlow(0)
    private val _candidates = MutableStateFlow<List<Todo>>(emptyList())

    val status: StateFlow<Status> = _status
    val lines: StateFlow<List<Line>> = _lines
    /** 認識途中の文字 */
    val partial: StateFlow<String> = _partial
    /** まだまとめていない文の数 */
    val pending: StateFlow<Int> = _pending
    /** 確認待ちの候補。先頭がいま見せているカード。DB にはまだ入っていないので id は負の仮番号 */
    val candidates: StateFlow<List<Todo>> = _candidates

    val isRunning: Boolean
        get() = _status.value != Status.Off && _status.value !is Status.Failed &&
            _status.value != Status.Unavailable && _status.value != Status.PermissionDenied

    private var nextLineId = 1L
    private var nextTempId = -1L
    private var store: CandidateStore? = null

    /**
     * 画面・サービスの onCreate で呼ぶ。最初の1回だけ、残してある候補を読み戻す。
     */
    fun attach(context: Context) {
        if (store != null) return
        val s = CandidateStore(context)
        store = s
        val saved = s.load()
        if (_candidates.value.isEmpty() && saved.isNotEmpty()) {
            _candidates.value = saved
            nextTempId = minOf(-1L, saved.minOf { it.id } - 1)
        }
    }

    private fun updateCandidates(change: (List<Todo>) -> List<Todo>) {
        _candidates.update(change)
        store?.save(_candidates.value)
    }

    internal fun setStatus(status: Status) {
        _status.value = status
    }

    internal fun setPartial(text: String) {
        _partial.value = text
    }

    internal fun setPending(count: Int) {
        _pending.value = count
    }

    /** 文字起こしに1行足して、その行の id を返す */
    internal fun addLine(text: String): Long {
        val id = nextLineId++
        _lines.update { (it + Line(id, text)).takeLast(MAX_LINES) }
        return id
    }

    internal fun markCandidates(ids: Set<Long>) {
        if (ids.isEmpty()) return
        _lines.update { lines -> lines.map { if (it.id in ids) it.copy(candidate = true) else it } }
    }

    /** 候補を束の後ろに足す。触っている最中のカードは入れ替えない */
    internal fun addCandidate(todo: Todo) {
        updateCandidates { it + todo.copy(id = nextTempId--) }
    }

    /** 候補を束から外して、元の位置を返す（元に戻すときに使う） */
    fun removeCandidate(candidate: Todo): Int? {
        val index = _candidates.value.indexOfFirst { it.id == candidate.id }
        if (index < 0) return null
        updateCandidates { it.filterNot { c -> c.id == candidate.id } }
        return index
    }

    /** 元に戻す：外した候補を元の位置に戻す */
    fun restoreCandidate(candidate: Todo, index: Int) {
        updateCandidates { list -> list.toMutableList().apply { add(index.coerceIn(0, size), candidate) } }
    }

    private const val MAX_LINES = 30
}
