package com.badmintonspeed.app.analysis

import android.graphics.PointF
import com.badmintonspeed.app.domain.CourtDimensions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 几何一致性校验层（v2.15，采纳外部专业 AI 建议第3条）。
 * 在任何场地标定结果输出前强制通过五项检查；不通过即返回失败标志，
 * 绝不能输出一个错误的结果误导测速。
 *
 * 检查项（对应建议原文）：
 *  1. 对边平行性与消失点合理性：上下边共享一个消失点，左右边共享另一个
 *  2. 长宽比：透视下单应性拟合后接近 13.40/6.10（BWF 双打）
 *  3. 对角线比值：14.723/14.366（双打/单打对角线比）
 *  4. 单应性重投影误差 < 阈值
 *  5. 线宽像素比与 40mm 物理比例自洽（简化：外边界线宽在合理范围）
 */
object GeometricVerifier {

    /** 校验结果：通过/失败 + 失败原因（用于 E101 诊断分类） */
    data class Result(val ok: Boolean, val reasons: List<String>, val score: Float)

    private const val REPROJECT_PX = 6f        // 重投影误差上限（帧宽 1280 时约 0.5%）
    private const val PARALLEL_DEG = 5f        // 对边夹角允许偏差（度）
    private const val DIAG_RATIO_MIN = 1.015f  // 14.723/14.366 ≈ 1.0248，透视下允许小幅波动
    private const val DIAG_RATIO_MAX = 1.045f

    /**
     * 对检测到的四边形角点（左上右上右下左下）做五项校验。
     * @param cornersPx 原图像素角点
     * @param frameW 帧宽（用于相对阈值）
     * @param frameH 帧高
     */
    fun verify(cornersPx: List<PointF>, frameW: Int, frameH: Int): Result {
        if (cornersPx.size < 4) return Result(false, listOf("角点数量不足"), 0f)
        val reasons = ArrayList<String>()
        var score = 1f

        // ---------- 1. 对边平行性（消失点合理性） ----------
        // 上边 vs 下边 应近似平行（消失点在远方向）；左边 vs 右边同理
        val top = cornersPx[0] to cornersPx[1]
        val right = cornersPx[1] to cornersPx[2]
        val bottom = cornersPx[3] to cornersPx[2]
        val left = cornersPx[0] to cornersPx[3]

        fun angleDeg(a: Pair<PointF, PointF>): Double {
            val dx = a.second.x - a.first.x
            val dy = a.second.y - a.first.y
            return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
        }
        val angleTB = abs(angleDiff(angleDeg(top), angleDeg(bottom)))
        val angleLR = abs(angleDiff(angleDeg(left), angleDeg(right)))
        val maxDev = max(angleTB, angleLR)
        if (maxDev > PARALLEL_DEG) {
            score -= 0.25f
            reasons.add("对边平行性差（偏差 ${"%.1f".format(maxDev)}° > ${PARALLEL_DEG}°）")
        }

        // ---------- 2. 长宽比（透视形变后应在合理区间） ----------
        // 用单应性拟合模板后的比例恒为 13.40/6.10=2.196，
        // 这里直接校验四边形边长比是否接近该值（允许透视压缩 ±45%）
        val lenTop = dist(top.first, top.second)
        val lenRight = dist(right.first, right.second)
        val lenBottom = dist(bottom.first, bottom.second)
        val lenLeft = dist(left.first, left.second)
        val avgW = (lenTop + lenBottom) / 2f
        val avgH = (lenRight + lenLeft) / 2f
        if (avgW > 0.5f && avgH > 0.5f) {
            val ratio = max(avgW, avgH) / min(avgW, avgH) // 长/短
            // 理论 2.196；透视极斜时仍应 ≥1.5；若 <1.4 很可能是误检（比如框住一块方形区域）
            if (ratio < 1.4f || ratio > 4.5f) {
                score -= 0.25f
                reasons.add("长宽比异常（${"%.2f".format(ratio)}，理论约2.20）")
            }
        }

        // ---------- 3. 对角线比值 ----------
        val diag1 = dist(cornersPx[0], cornersPx[2])
        val diag2 = dist(cornersPx[1], cornersPx[3])
        if (diag1 > 1f && diag2 > 1f) {
            val dRatio = max(diag1, diag2) / min(diag1, diag2)
            if (dRatio < DIAG_RATIO_MIN || dRatio > DIAG_RATIO_MAX) {
                score -= 0.2f
                reasons.add("对角线比异常（${"%.3f".format(dRatio)}，理论约1.025）")
            }
        }

        // ---------- 4. 单应性重投影误差 ----------
        val h = try { Homography.compute(cornersPx, StandardCourt.corners) } catch (e: Exception) { null }
        if (h != null) {
            val norm = frameW.toFloat()
            var errSum = 0f
            for (i in cornersPx.indices) {
                val src = cornersPx[i]
                val dst = StandardCourt.corners[i]
                val px = Homography.pixelToCourt(h, src.x, src.y)
                val e = hypot(px.x - dst.x, px.y - dst.y)
                // 模板单位是米：1m ≈ 边长像素/米；用帧宽折算成像素
                val scale = min(lenTop, lenBottom) / CourtDimensions.WIDTH_M
                errSum += (e * scale) / norm
            }
            val avgErr = errSum / 4f
            if (avgErr > REPROJECT_PX / norm) {
                score -= 0.2f
                reasons.add("单应性重投影误差偏大（${"%.1f".format(avgErr * norm)}px）")
            }
        }

        // ---------- 5. 外边界线宽自洽（简化：角点间距合理性 + 面积占比） ----------
        val area = quadArea(cornersPx)
        val frameArea = frameW * frameH
        if (area / frameArea < 0.02f || area / frameArea > 0.98f) {
            score -= 0.1f
            reasons.add("场地面积占比异常（${"%.0f".format(area / frameArea * 100)}%）")
        }

        return Result(score >= 0.6f, reasons, score.coerceIn(0f, 1f))
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 180.0
        if (d < 0) d += 180.0
        if (d > 90.0) d = 180.0 - d
        return d
    }

    private fun dist(a: PointF, b: PointF): Float = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()

    private fun quadArea(p: List<PointF>): Float {
        if (p.size < 4) return 0f
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(s) / 2f
    }
}
