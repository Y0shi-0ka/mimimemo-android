package jp.mimimemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 読み込みで落ちたモデルを使わない印（#120） */
class LoadGuardTest {
    private val store = mutableMapOf<String, String>()
    private fun guard() = LoadGuard({ store[it] }, { k, v -> if (v == null) store.remove(k) else store[k] = v })

    @Test
    fun crashedModelIsSkippedNextTime() {
        val id = guard().idOf("broken.litertlm", 3)
        assertTrue(guard().canLoad(id))
        guard().begin(id)
        // ここでアプリごと落ちた（end が呼ばれない）として、次に起動したとき
        assertFalse(guard().canLoad(id))
        assertTrue(guard().isBad(id))
        // 別のモデル（入れ直し）は使える
        guard().reset()
        assertTrue(guard().canLoad(guard().idOf("good.litertlm", 5)))
    }

    @Test
    fun normalLoadLeavesNoMark() {
        val id = guard().idOf("good.litertlm", 5)
        assertTrue(guard().canLoad(id))
        guard().begin(id)
        guard().end()
        assertTrue(guard().canLoad(id))
        assertFalse(guard().isBad(id))
    }
}
