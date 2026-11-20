package jp.mimimemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 振ったことの見分け（#146） */
class ShakeDetectorTest {

    private val strong = 3.2f * 9.8f
    private val walking = 1.6f * 9.8f

    /** 時刻ごとの強さを流し、振ったと見なした回数を返す */
    private fun shakes(d: ShakeDetector, samples: List<Pair<Long, Float>>) =
        samples.count { (t, a) -> d.onAcceleration(t, a, 0f, 0f) }

    @Test
    fun threeStrongPeaksIsAShake() {
        assertEquals(1, shakes(ShakeDetector(), listOf(0L to strong, 250L to strong, 500L to strong)))
    }

    @Test
    fun walkingDoesNotTrigger() {
        val steps = (0 until 40).map { it * 300L to walking }
        assertEquals(0, shakes(ShakeDetector(), steps))
    }

    @Test
    fun peaksTooFarApartDoNotTrigger() {
        // 1回ずつぶつけたような揺れが間をあけて来ても、振ったとは見なさない
        assertEquals(0, shakes(ShakeDetector(), listOf(0L to strong, 900L to strong, 1800L to strong, 2700L to strong)))
    }

    @Test
    fun oneSwingCountsOnce() {
        // 1回の振りの間に続けて来る値は1つに数える
        val d = ShakeDetector()
        assertFalse(d.onAcceleration(0, strong, 0f, 0f))
        assertFalse(d.onAcceleration(20, strong, 0f, 0f))
        assertFalse(d.onAcceleration(40, strong, 0f, 0f))
    }

    @Test
    fun keepShakingTriggersOnlyOnceInCooldown() {
        val samples = (0 until 12).map { it * 200L to strong }
        assertEquals(1, shakes(ShakeDetector(), samples))
    }
}
