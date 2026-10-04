package com.badmintonspeed.app.analysis

import com.badmintonspeed.app.domain.Highlight
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.domain.HitType

/**
 * 高光片段规则引擎（v2.25 模型七，优化方案原文：纯规则引擎，不需要神经网络）。
 * 规则：
 *   1. 杀球高光：球速 > 100 km/h 判为杀球高光
 *   2. 多拍高光：单回合击球数 > 10 拍
 *   3. 平抽高光：连续快速击球（相邻击球间隔 < 0.5s 且连续 ≥ 4 拍）
 * 输出：高光时间戳区间（startSec ~ endSec）+ 说明文字。
 */
object HighlightEngine {

    /** 规则阈值（方案原文） */
    private const val SMASH_SPEED_KMH = 100f
    private const val RALLY_MAX_BREAK_SEC = 3.0   // 间隔 >3s 视为新回合
    private const val RALLY_LONG_PLAY = 10        // 单回合 >10 拍 => 多拍高光
    private const val RAPID_GAP_SEC = 0.5         // 连续快速击球间隔
    private const val RAPID_MIN_COUNT = 4         // 连续 ≥4 拍 => 平抽高光

    /**
     * @param hits 击球列表（按时间升序）
     * @return 高光片段列表（按时间升序）
     */
    fun detect(hits: List<HitAnalysis>): List<Highlight> {
        if (hits.size < 2) return emptyList()
        val out = ArrayList<Highlight>()

        // ---- 规则 1：杀球高光（球速 > 100 km/h） ----
        for (h in hits) {
            if (h.hitType == HitType.SMASH && h.maxSpeedKmh > SMASH_SPEED_KMH) {
                val t0 = maxOf(0.0, h.timeSeconds - 0.5)
                val t1 = h.timeSeconds + 1.0
                out.add(Highlight("杀球", t0, t1, "杀球 ${h.maxSpeedKmh.toInt()} km/h"))
            }
        }

        // ---- 规则 2：多拍高光（单回合击球数 > 10） ----
        val sorted = hits.sortedBy { it.timeSeconds }
        var rallyStart = sorted.first().timeSeconds
        var rallyCount = 0
        var prevT = sorted.first().timeSeconds
        for (h in sorted) {
            if (h.timeSeconds - prevT > RALLY_MAX_BREAK_SEC) {
                if (rallyCount > RALLY_LONG_PLAY) {
                    out.add(Highlight("多拍", rallyStart, prevT, "多拍回合 ${rallyCount} 拍"))
                }
                rallyStart = h.timeSeconds
                rallyCount = 0
            }
            rallyCount++
            prevT = h.timeSeconds
        }
        if (rallyCount > RALLY_LONG_PLAY) {
            out.add(Highlight("多拍", rallyStart, prevT, "多拍回合 ${rallyCount} 拍"))
        }

        // ---- 规则 3：平抽高光（连续快速击球，间隔 < 0.5s 且连续 ≥ 4 拍） ----
        var runStart = sorted.first().timeSeconds
        var runCount = 1
        var runEnd = sorted.first().timeSeconds
        for (i in 1 until sorted.size) {
            val gap = sorted[i].timeSeconds - sorted[i - 1].timeSeconds
            if (gap < RAPID_GAP_SEC) {
                runCount++
                runEnd = sorted[i].timeSeconds
            } else {
                if (runCount >= RAPID_MIN_COUNT) {
                    out.add(Highlight("平抽", runStart, runEnd, "连续快速对抽 ${runCount} 拍"))
                }
                runStart = sorted[i].timeSeconds
                runCount = 1
                runEnd = sorted[i].timeSeconds
            }
        }
        if (runCount >= RAPID_MIN_COUNT) {
            out.add(Highlight("平抽", runStart, runEnd, "连续快速对抽 ${runCount} 拍"))
        }

        return out.sortedBy { it.startSec }
    }
}
