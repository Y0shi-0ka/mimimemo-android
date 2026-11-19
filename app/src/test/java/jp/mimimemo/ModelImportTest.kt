package jp.mimimemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** モデルとして取り込めるファイル名（#74） */
class ModelImportTest {
    @Test
    fun onlyLitertlm() {
        assertTrue(ModelImport.isModelName("Qwen2.5-1.5B-Instruct_q8.litertlm"))
        assertTrue(ModelImport.isModelName("model.LITERTLM"))
        assertFalse(ModelImport.isModelName("model.task"))
        assertFalse(ModelImport.isModelName("photo.jpg"))
    }
}
