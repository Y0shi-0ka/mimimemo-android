package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 別プロセスの AI とのやりとり（#148） */
class LlmServiceCodecTest {

    @Test
    fun tasksRoundTrip() {
        val tasks = listOf(
            AiTaskPrompt.Task(0, "牛乳を買う", "明日", null),
            AiTaskPrompt.Task(3, "レポートを出す", null, "授業"),
        )
        assertEquals(tasks, LlmService.decode(LlmService.encode(tasks)))
    }

    @Test
    fun missingOrBrokenMeansNoAnswer() {
        assertNull(LlmService.decode(null))
        assertNull(LlmService.decode("not json"))
        assertEquals(emptyList<AiTaskPrompt.Task>(), LlmService.decode("[]"))
    }
}
