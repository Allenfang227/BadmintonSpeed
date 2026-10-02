package com.badmintonspeed.app.analysis

import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.domain.HitType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 击球检测器：
 * 原理（文档 5.3.4）：
 * 1. 球速突然增大（速度局部峰值 + 超过阈值）=> 击球
 * 2. 两次击球之间保持最小帧间隔
 * MVP 版本：仅基于球速峰值，过滤误检。
 */
class HitDetector(
    private val minSpeedKmh: Float = 80f,
    private val minAccelerationKmh: Float = 20f,
    private val minIntervalFrames: Int = 15
) {

    /**
     * @param points 带速度的轨迹点（speedKmh 已填充）
     * @return 击球列表
     */
    fun detect(points: List<BallPoint>): List<HitAnalysis> {
        val hits = ArrayList<HitAnalysis>()
        if (points.size < 8) return hits
        val n = points.size

        var lastHitIndex = -minIntervalFrames
        var hitNumber = 0
        for (i in 2 until n - 2) {
            val speed = points[i].speedKmh ?: 0f
            val prevSpeed = points[i - 1].speedKmh ?: 0f
            if (speed < minSpeedKmh) continue
            if (speed - prevSpeed < minAccelerationKmh) continue
            val isLocalMax = speed > (points[i - 1].speedKmh ?: 0f) &&
                speed > (points[i - 2].speedKmh ?: 0f) &&
                speed > (points[i + 1].speedKmh ?: 0f) &&
                speed > (points[i + 2].speedKmh ?: 0f)
            if (!isLocalMax) continue
            if (i - lastHitIndex < minIntervalFrames) continue

            lastHitIndex = i
            hitNumber++

            // 击球后窗口：取击球点及之后若干帧，统计最大/平均速度与角度
            val end = minOf(n - 1, i + 10)
            val window = points.subList(i, end + 1)
            val speeds = window.mapNotNull { it.speedKmh }
            val maxSpeed = (speeds.maxOrNull() ?: speed).coerceAtLeast(speed)
            val avgSpeed = if (speeds.isNotEmpty()) speeds.average().toFloat() else speed

            // 击球角度：击球点与 5 帧后位置在场地坐标中的方向
            val angleDeg = run {
                val a = points[i]
                val b = points[minOf(n - 1, i + 5)]
                val dx = b.courtX - a.courtX
                val dy = b.courtY - a.courtY
                if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) 0f
                else Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            }

            val type = classify(maxSpeed, angleDeg)
            val traj = points.subList(maxOf(0, i - 2), end + 1)
            hits.add(
                HitAnalysis(
                    id = "hit_${String.format("%03d", hitNumber)}",
                    frameIndex = points[i].frame,
                    timeSeconds = points[i].timeSec,
                    hitType = type,
                    maxSpeedKmh = maxSpeed,
                    avgSpeedKmh = avgSpeed,
                    angleDeg = angleDeg,
                    trajectory = traj
                )
            )
        }
        return hits
    }

    /** 规则版击球类型分类（文档 4.4.5 简化） */
    private fun classify(maxSpeedKmh: Float, angleDeg: Float): HitType {
        return when {
            maxSpeedKmh >= 140f -> HitType.SMASH
            maxSpeedKmh >= 80f && abs(angleDeg) > 60f -> HitType.CLEAR
            maxSpeedKmh >= 80f -> HitType.DRIVE
            else -> HitType.DROP
        }
    }
}
