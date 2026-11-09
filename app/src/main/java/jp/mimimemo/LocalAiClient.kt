package jp.mimimemo

/**
 * 端末内の生成AI（ローカルAI）で、1文から「やるべきこと」「期限」「科目・活動」を取り出す。
 *
 * 実装方式（ML Kit GenAI / MediaPipe + Gemma / llama.cpp など）は issue #7 で検討中。
 * 決まるまでは [UnavailableLocalAiClient] を使い、TodoExtractor のルール抽出で動かす。
 */
interface LocalAiClient {

    /** この端末でローカルAIが使えるか */
    fun isAvailable(): Boolean

    /**
     * 文からToDoを取り出す。やるべきことが含まれていなければ null を返す。
     * 失敗したときは例外を投げてよい（呼び出し側がルール抽出に切り替える）。
     */
    suspend fun extractTask(sentence: String): Result?

    /**
     * @param title やるべきこと
     * @param dueText 期限の表現（「来週月曜」など）。DateParser で日付に直す
     * @param tag 科目・活動
     */
    data class Result(val title: String, val dueText: String?, val tag: String?)
}

/** ローカルAIを使わない（非対応端末・未実装）ときの実装 */
object UnavailableLocalAiClient : LocalAiClient {
    override fun isAvailable(): Boolean = false
    override suspend fun extractTask(sentence: String): LocalAiClient.Result? = null
}
