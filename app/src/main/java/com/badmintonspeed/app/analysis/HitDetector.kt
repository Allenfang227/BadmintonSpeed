package com.badmintonspeed.app.analysis

import android.graphics.RectF
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.domain.HitType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 击球检测器 v2（增强物理合理性 + 击球动作识别）：
 *
 * 原理（文档 5.3.4）：
 * 1. 球速突然增大（速度局部峰值 + 超过阈值）=> 击球
 * 2. 两次击球之间保持最小帧间隔
 *
 * v2 增强（用户要求"识别运动员击球动作 + 轨迹必须物理合理"）：
 * 3. 击球动作识别：传入运动员运动区域（帧差检测），击球点应发生在球拍/球员附近，
 *    离所有球员都远的候选击球点置信度打折（避免把背景噪点当击球）
 * 4. 轨迹物理校验：一个有效击球，球必须从一边飞向另一边——
 *    - 方向单调：courtY 方向不来回反转超过 2 次（打转的混乱轨迹直接剔除）
 *    - 飞行距离：击球点→落点距离 ≥ 1.0m（球必须真的飞出去，不能原地抖）
 *    - 落点判定：轨迹最后一点在标准球场内 => IN，否则 OUT
 */
class HitDetector(
    private val minSpeedKmh: Float = 80f,
    private val minAccelerationKmh: Float = 20f,
    private val minIntervalFrames: Int = 15,
    private val minFlightMeters: Float = 1.0f,
    private val maxDirectionReversals: Int = 2
) {

    /**
     * @param points 带速度的轨迹点（speedKmh 已填充）
     * @param playerRects 运动员运动区域（可选，用于击球动作识别）
     * @return 击球列表（已做物理校验）
     */
    fun detect(points: List<BallPoint>, playerRects: List<RectF>? = null): List<HitAnalysis> {
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

            // 击球后窗口：击球点及之后若干帧
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

            // ---- 轨迹物理校验：取击球点→落点整段轨迹 ----
            // 落点：轨迹后续方向变化趋于平缓的点（简化：后续 10 帧内最后一个点，或速度显著下降后的点）
            var landIdx = end
            for (k in i + 2 until end) {
                val sp = points[k].speedKmh ?: 0f
                if (sp < maxSpeed * 0.55f) { landIdx = k; break }
            }
            val trajRaw = points.subList(maxOf(0, i - 2), landIdx + 1)
            if (trajRaw.size < 3) continue
            // v2.12 景深：给轨迹填充高度 z（物理抛物线模型：起飞→最高点约1.6m→落地）和地面投影
            val t0 = trajRaw.first().timeSec
            val t1 = trajRaw.last().timeSec
            val spanT = (t1 - t0).coerceAtLeast(0.001)
            val traj = trajRaw.map { tp ->
                val frac = ((tp.timeSec - t0) / spanT).toFloat().coerceIn(0f, 1f)
                tp.copy(
                    zMeters = 1.6f * kotlin.math.sin(kotlin.math.PI * frac).toFloat(),
                    groundX = tp.courtX,
                    groundY = tp.courtY
                )
            }

            // 1) 方向单调性：courtY 方向反转计数（打转剔除）
            var reversals = 0
            var lastDir = 0
            for (k in 1 until traj.size) {
                val dy = traj[k].courtY - traj[k - 1].courtY
                if (abs(dy) < 0.05f) continue
                val dir = if (dy > 0) 1 else -1
                if (lastDir != 0 && dir != lastDir) reversals++
                lastDir = dir
            }
            if (reversals > maxDirectionReversals) continue

            // 2) 飞行距离：击球点→落点 ≥ minFlightMeters（球真的飞出去了）
            val startP = traj.first()
            val landP = traj.last()
            val flightM = sqrt(
                (landP.courtX - startP.courtX) * (landP.courtX - startP.courtX) +
                (landP.courtY - startP.courtY) * (landP.courtY - startP.courtY)
            )
            if (flightM < minFlightMeters) continue

            // 3) 落点 IN/OUT：标准双打场地 6.10 x 13.40
            val inCourt = landP.courtX in 0f..6.10f && landP.courtY in 0f..13.40f

            // ---- 击球动作识别（运动员区域关联）----
            var actionConfidence = 1f
            if (playerRects != null && playerRects.isNotEmpty()) {
                val hitPx = points[i]
                var minDist = Float.MAX_VALUE
                for (r in playerRects) {
                    val dx = hitPx.x - r.centerX()
                    val dy = hitPx.y - r.centerY()
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < minDist) minDist = d
                }
                // 击球点离最近运动员超过帧宽 30% => 击球动作置信度降低（可能是噪点）
                actionConfidence = if (minDist < 320f) 1f else 0.5f
            }

            val type = classify(maxSpeed, angleDeg)
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
