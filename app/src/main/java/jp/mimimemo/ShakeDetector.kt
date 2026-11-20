package jp.mimimemo

import kotlin.math.sqrt

/**
 * 端末を振ったことを見分ける（issue #146）。Android に依存しないので、テストで確かめられる。
 *
 * 強い揺れ（重力の [threshold] 倍以上）が [windowMs] のうちに [peaks] 回あったら振ったとみなす。
 * ポケットに入れて歩く・机に置くくらいの揺れでは反応しないよう、強さと回数の両方を見る。
 * 一度反応したら [cooldownMs] は反応しない（振り続けても1回だけ）。
 */
class ShakeDetector(
    private val threshold: Float = 2.5f,
    private val peaks: Int = 3,
    private val windowMs: Long = 1200,
    private val cooldownMs: Long = 2000,
) {
    private var count = 0
    private var firstPeakAt = 0L
    private var lastPeakAt = Long.MIN_VALUE / 2
    private var lastShakeAt = Long.MIN_VALUE / 2

    /** 加速度（m/s²）を渡す。振ったと見なした瞬間だけ true */
    fun onAcceleration(atMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (atMs - lastShakeAt < cooldownMs) return false
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g < threshold) return false
        // 1回の振りで続けて来る値は1つに数える
        if (atMs - lastPeakAt < MIN_PEAK_GAP_MS) return false
        if (count == 0 || atMs - firstPeakAt > windowMs) {
            count = 0
            firstPeakAt = atMs
        }
        lastPeakAt = atMs
        count++
        if (count < peaks) return false
        count = 0
        lastShakeAt = atMs
        return true
    }

    private companion object {
        const val GRAVITY = 9.80665f
        const val MIN_PEAK_GAP_MS = 120L
    }
}
