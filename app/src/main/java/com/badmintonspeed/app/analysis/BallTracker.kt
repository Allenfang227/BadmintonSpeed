package com.badmintonspeed.app.analysis

import kotlin.math.sqrt

/**
 * 羽毛球轨迹跟踪器（MVP 简化版）：
 * 速度预测（上一帧位移外推）+ 最近邻匹配 + 丢失容忍。
 * 连续 2 帧确认后才输出轨迹点，过滤单帧噪声。
 */
class BallTracker(
    private val maxDisplacement: Float = 140f,
    private val maxLostFrames: Int = 6
) {

    private class Track(
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var confirmed: Int = 0,
        var lost: Int = 0,
        var lastTimeSec: Double = 0.0,
        var lastFrame: Int = -1
    )

    private var track: Track? = null

    /**
     * @param frameWidth 当前帧宽
     * @param frameHeight 当前帧高
     * @return 跟踪到的球心像素坐标；未确认或丢失时返回 null
     */
    fun update(
        candidates: List<BallDetector.Blob>,
        frameIndex: Int,
        timeSec: Double,
        frameWidth: Int,
        frameHeight: Int
    ): Pair<Float, Float>? {
        val t = track

        if (t == null) {
            // 初始化：优先靠近画面中心的候选（球通常从中场附近开始）
            if (candidates.isEmpty()) return null
            val c = candidates.minByOrNull {
                val dx = it.cx - frameWidth / 2f
                val dy = it.cy - frameHeight / 2f
                sqrt(dx * dx + dy * dy)
            }!!
            track = Track(c.cx, c.cy, lastTimeSec = timeSec, lastFrame = frameIndex)
            return null
        }

        // 预测位置（用上一帧速度外推）
        val dt = (timeSec - t.lastTimeSec).toFloat().coerceAtLeast(0f)
        val predictX = t.x + t.vx * dt
        val predictY = t.y + t.vy * dt
        val dispLimit = maxDisplacement * (dt * 30f).coerceAtLeast(1f)

        var bestBlob: BallDetector.Blob? = null
        var bestDist = Float.MAX_VALUE
        for (blob in candidates) {
            val dx = blob.cx - predictX
            val dy = blob.cy - predictY
            val d = sqrt(dx * dx + dy * dy)
            if (d <= dispLimit && d < bestDist) {
                bestDist = d
                bestBlob = blob
            }
        }

        if (bestBlob != null) {
            val prevX = t.x
            val prevY = t.y
            // 平滑更新位置
            t.x += 0.7f * (bestBlob.cx - t.x)
            t.y += 0.7f * (bestBlob.cy - t.y)
            if (dt > 0.001f) {
                t.vx = 0.8f * t.vx + 0.2f * ((t.x - prevX) / dt)
                t.vy = 0.8f * t.vy + 0.2f * ((t.y - prevY) / dt)
            }
            t.confirmed++
            t.lost = 0
            t.lastFrame = frameIndex
            t.lastTimeSec = timeSec
            return if (t.confirmed >= 2) t.x to t.y else null
        }

        // 未匹配：容忍丢帧
        t.lost++
        t.lastTimeSec = timeSec
        if (t.lost > maxLostFrames) {
            track = null
            return null
        }
        return null
    }

    fun reset() {
        track = null
    }
}
