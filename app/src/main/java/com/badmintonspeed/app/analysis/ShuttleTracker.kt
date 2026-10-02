package com.badmintonspeed.app.analysis

import android.graphics.PointF
import kotlin.math.hypot

/**
 * 羽毛球轨迹跟踪器（融合自 lzylovec/AI-YuJian-AI 的 ShuttlecockTracker）：
 * 在 YOLO 检测结果之上做帧间跟踪过滤，大幅减少误检、提升轨迹稳定性。
 *
 * 过滤逻辑：
 *   1. 检测框面积过滤（≤画面 0.4%，过滤把场地线/灯光误检成球的大框）
 *   2. 宽高比过滤（≤4.0，羽毛球接近圆形）
 *   3. ROI 限制（只在场地范围内接受候选，场地未知时不限制）
 *   4. 帧间跳跃门限（≤maxJumpPixels，连续帧间球不可能瞬移太远）
 *   5. 速度预测门限（≤predictionGatePixels，基于前两帧速度外推预测下一帧位置）
 *   6. 丢帧容忍（≤maxMissingFrames，允许短暂被遮挡/未检出，不中断轨迹）
 *   7. 候选打分：confidence×1000 - 距离×1.4 - 面积惩罚，选最优候选
 */
class ShuttleTracker(
    private val maxJumpPixels: Float = 220f,
    private val predictionGatePixels: Float = 260f,
    private val maxMissingFrames: Int = 5,
    private val maxBoxAreaRatio: Float = 0.004f,
    private val maxAspectRatio: Float = 4.0f,
    private val roiPaddingRatio: Float = 0.08f,
    trajectoryLength: Int = 30
) {
    private val trajectory = ArrayDeque<PointF>(trajectoryLength)
    private var lastValid: PointF? = null
    private var missingFrames = 0

    data class Candidate(
        val point: PointF,
        val confidence: Float,
        val areaRatio: Float,
        val aspectRatio: Float
    )

    /**
     * 更新一帧的跟踪。
     * @param boxes YOLO 检测框列表
     * @param frameWidth 帧宽（用于面积比计算）
     * @param frameHeight 帧高
     * @param courtCorners 场地4角点（可选，用于ROI限制）；null 时不限制
     * @return 跟踪到的球心像素坐标；未检出/被过滤时返回 null
     */
    fun update(
        boxes: List<ShuttleOnnxDetector.Box>,
        frameWidth: Int,
        frameHeight: Int,
        courtCorners: List<PointF>? = null
    ): PointF? {
        val frameArea = (frameWidth * frameHeight).toFloat()
        val candidates = boxes.mapNotNull { b ->
            if (b.w <= 0f || b.h <= 0f) return@mapNotNull null
            val areaRatio = (b.w * b.h) / frameArea
            val aspectRatio = maxOf(b.w / b.h, b.h / b.w)
            if (areaRatio > maxBoxAreaRatio || aspectRatio > maxAspectRatio) return@mapNotNull null
            val pt = PointF(b.cx, b.cy)
            if (!pointInRoi(pt, courtCorners, frameWidth, frameHeight)) return@mapNotNull null
            Candidate(pt, b.conf, areaRatio, aspectRatio)
        }

        val selected = selectCandidate(candidates)
        if (selected == null) {
            missingFrames++
            if (missingFrames > maxMissingFrames) lastValid = null
            return null
        }

        // 异常点过滤：跳跃过大或偏离预测太远
        if (trajectory.isNotEmpty()) {
            val last = trajectory.last()
            val jump = hypot(selected.point.x - last.x, selected.point.y - last.y)
            if (jump > maxJumpPixels && missingFrames <= maxMissingFrames) {
                missingFrames++
                return null
            }
            val predicted = predictNext()
            if (predicted != null) {
                val predDist = hypot(selected.point.x - predicted.x, selected.point.y - predicted.y)
                if (predDist > predictionGatePixels && missingFrames <= maxMissingFrames) {
                    missingFrames++
                    return null
                }
            }
        }

        trajectory.addLast(selected.point)
        lastValid = selected.point
        missingFrames = 0
        return selected.point
    }

    private fun selectCandidate(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        if (trajectory.isEmpty()) return candidates.maxByOrNull { it.confidence }
        val predicted = predictNext() ?: return candidates.maxByOrNull { it.confidence }
        return candidates.maxByOrNull { c ->
            val dist = hypot(c.point.x - predicted.x, c.point.y - predicted.y)
            c.confidence * 1000f - dist * 1.4f - c.areaRatio * 4000f
        }
    }

    private fun predictNext(): PointF? {
        if (trajectory.size < 2) return trajectory.lastOrNull()
        val prev = trajectory[trajectory.size - 2]
        val last = trajectory.last()
        return PointF(last.x + (last.x - prev.x), last.y + (last.y - prev.y))
    }

    private fun pointInRoi(
        point: PointF, courtCorners: List<PointF>?, frameWidth: Int, frameHeight: Int
    ): Boolean {
        if (courtCorners == null || courtCorners.size < 4) return true
        val minX = courtCorners.minOf { it.x }
        val maxX = courtCorners.maxOf { it.x }
        val minY = courtCorners.minOf { it.y }
        val maxY = courtCorners.maxOf { it.y }
        val padding = maxOf(maxX - minX, maxY - minY) * roiPaddingRatio
        return point.x in (minX - padding)..(maxX + padding) &&
               point.y in (minY - padding)..(maxY + padding)
    }

    fun getTrajectory(): List<PointF> = trajectory.toList()

    fun clear() {
        trajectory.clear()
        lastValid = null
        missingFrames = 0
    }
}
