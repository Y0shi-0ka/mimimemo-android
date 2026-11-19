package jp.mimimemo

/**
 * 読み込みでアプリごと落ちるモデルを、二度と読み込まないための印（issue #120）。
 * 読み込む前に「読み込み中」を書き、終わったら消す。次に見たとき印が残っていれば、
 * 前回の読み込みで落ちた（ネイティブの異常終了は捕まえられない）とみなして、そのモデルを使わない。
 * 保存先は渡された関数で読み書きする（アプリでは SharedPreferences、テストでは Map）。
 */
class LoadGuard(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) {
    /** モデルを見分ける印：名前と大きさ */
    fun idOf(name: String, size: Long) = "$name|$size"

    /** 前回の読み込み中の印が残っていれば、そのモデルを「使わない」にする。使ってよければ true */
    fun canLoad(id: String): Boolean {
        read(KEY_LOADING)?.let { crashed ->
            write(KEY_BAD, crashed)
            write(KEY_LOADING, null)
        }
        return read(KEY_BAD) != id
    }

    fun begin(id: String) = write(KEY_LOADING, id)

    fun end() = write(KEY_LOADING, null)

    fun isBad(id: String) = read(KEY_BAD) == id || read(KEY_LOADING) == id

    /** モデルを入れ直したら、印を消す */
    fun reset() {
        write(KEY_BAD, null)
        write(KEY_LOADING, null)
    }

    private companion object {
        const val KEY_LOADING = "loading"
        const val KEY_BAD = "bad"
    }
}
