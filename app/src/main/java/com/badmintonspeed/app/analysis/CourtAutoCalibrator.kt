package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AI 自动场地标定（对应参考图2 的"场地基准检测"模块）：
 *   Canny边缘检测 -> 霍夫直线变换 -> RANSAC迭代拟合 -> 单应性矩阵计算
 * 从视频画面中自动提取羽毛球场的白色边线/端线，解出四个角点，
 * 替代原有的人工四角标定流程。
 *
 * 算法管线：
 * 1. 降采样到长边 720，灰度化并提取"白色线"掩码（场地白线，RGB 均高且低饱和）
 * 2. 形态学闭运算连通断裂的白线
 * 3. 标准霍夫变换在掩码上投票，得到候选直线（rho, theta, votes）
 * 4. 取最高票直线定主方向，找与其近似垂直的次方向，构成两组平行线族
 * 5. 每组内按 rho 分位稳健提取两条边界线（RANSAC 思想：取支持最密集的两端）
 * 6. 两两求交得到 4 个角点，按画面位置排序为 左上/右上/右下/左下
 * 7. 面积校验通过后返回；失败返回 null（由上层回退到画面中央矩形）
 */
object CourtAutoCalibrator {

    private const val WORK_MAX_SIDE = 720
    private const val THETA_STEP_DEG = 2.0
    private const val WHITE_MIN = 175
    private const val WHITE_SAT_DIFF = 70

    /** 依次返回 左上、右上、右下、左下 四个角点（原图像素坐标），失败返回 null */
    fun calibrate(frame: Bitmap): List<PointF>? {
        val srcW = frame.width
        val srcH = frame.height
        val scale = min(1f, WORK_MAX_SIDE.toFloat() / max(srcW, srcH))
        val W = (srcW * scale).roundToInt().coerceAtLeast(1)
        val H = (srcH * scale).roundToInt().coerceAtLeast(1)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(frame, W, H, true) else frame

        // ---- 1) 白色线掩码 ----
        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        if (bmp !== frame) bmp.recycle()
        val mask = BooleanArray(W * H)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            if (min(r, min(g, b)) >= WHITE_MIN && (max(r, max(g, b)) - min(r, min(g, b))) <= WHITE_SAT_DIFF) {
                mask[i] = true
            }
        }

        // ---- 2) 形态学闭：膨胀 + 腐蚀（3x3 十字）----
        val closed = BooleanArray(W * H)
        // 膨胀
        val dil = BooleanArray(W * H)
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                if (mask[y * W + x] || mask[y * W + x - 1] || mask[y * W + x + 1] ||
                    mask[(y - 1) * W + x] || mask[(y + 1) * W + x]
                ) dil[y * W + x] = true
            }
        }
        // 腐蚀（十字内核 3 邻域必须都有）
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                if (dil[y * W + x] && dil[y * W + x - 1] && dil[y * W + x + 1] &&
                    dil[(y - 1) * W + x] && dil[(y + 1) * W + x]
                ) closed[y * W + x] = true
            }
        }

        // ---- 3) 霍夫直线投票 ----
        val numTheta = (180.0 / THETA_STEP_DEG).toInt()
        val diag = ceil(sqrt((W * W + H * H).toDouble())).toInt()
        val rhoOffset = diag
        val rhoBins = 2 * diag + 1
        val acc = IntArray(numTheta * rhoBins)
        for (y in 0 until H step 2) {
            for (x in 0 until W step 2) {
                if (!closed[y * W + x]) continue
                for (t in 0 until numTheta) {
                    val theta = t * THETA_STEP_DEG * PI / 180.0
                    val rho = x * cos(theta) + y * sin(theta)
                    val r = (rho + rhoOffset).roundToInt().coerceIn(0, rhoBins - 1)
                    acc[t * rhoBins + r]++
                }
            }
        }

        // ---- 4) 峰值提取：每个 theta 列的局部极大 ----
        data class Line(val rho: Double, val thetaDeg: Double, val votes: Int)
        val candidates = ArrayList<Line>()
        var maxVotes = 0
        for (t in 0 until numTheta) {
            val col = t * rhoBins
            var bestR = -1
            var bestV = 0
            var i = 0
            while (i < rhoBins) {
                var run = i
                var runV = acc[col + i]
                while (run + 1 < rhoBins && acc[col + run + 1] >= runV - 2) {
                    run++
                    runV = max(runV, acc[col + run])
                }
                if (runV >= 3) {
                    if (runV > bestV) { bestV = runV; bestR = (i + run) / 2 }
                    i = run + 1
                } else i++
            }
            if (bestV >= 3) {
                val rho = bestR - rhoOffset
                candidates.add(Line(rho.toDouble(), t * THETA_STEP_DEG, bestV))
                if (bestV > maxVotes) maxVotes = bestV
            }
        }
        if (candidates.isEmpty()) return null

        // 保留高票线（>= 25% 峰值）
        val strong = candidates.filter { it.votes >= maxVotes * 0.25 }

        // ---- 5) 主方向 + 垂直方向 ----
        val top = strong.maxBy { it.votes }
        var vert: Line? = null
        for (l in strong) {
            val diff = angleDiff(l.thetaDeg, top.thetaDeg)
            if (diff in 70.0..110.0 && (vert == null || l.votes > vert.votes)) vert = l
        }
        if (vert == null) return null

        val clusterA = strong.filter { angleDiff(it.thetaDeg, top.thetaDeg) <= 14.0 }
        val clusterB = strong.filter { angleDiff(it.thetaDeg, vert.thetaDeg) <= 14.0 }
        if (clusterA.size < 2 || clusterB.size < 2) return null

        // ---- 6) RANSAC 思想：每组取支持密集的两端作为边界线 ----
        val aRhos = clusterA.map { it.rho }.sorted()
        val bRhos = clusterB.map { it.rho }.sorted()
        val a1 = quantile(aRhos, 0.12)
        val a2 = quantile(aRhos, 0.88)
        val b1 = quantile(bRhos, 0.12)
        val b2 = quantile(bRhos, 0.88)
        if (abs(a2 - a1) < 8 || abs(b2 - b1) < 8) return null

        val thetaA = top.thetaDeg * PI / 180.0
        val thetaB = vert.thetaDeg * PI / 180.0

        // ---- 7) 两两求交 ----
        val pts = ArrayList<PointF>()
        for (ra in listOf(a1, a2)) {
            for (rb in listOf(b1, b2)) {
                val inter = intersect(ra, thetaA, rb, thetaB) ?: continue
                pts.add(inter)
            }
        }
        if (pts.size != 4) return null

        // ---- 8) 排序：上排（y 小）两角 + 下排（y 大）两角；行内按 x ----
        val sortedByY = pts.sortedBy { it.y }
        val topRow = sortedByY.take(2).sortedBy { it.x }
        val bottomRow = sortedByY.drop(2).sortedBy { it.x }
        val corners = listOf(topRow[0], topRow[1], bottomRow[1], bottomRow[0]) // 左上 右上 右下 左下

        // ---- 9) 面积校验 ----
        val area = quadArea(corners)
        if (area < 0.18 * W * H) return null
        if (corners.any { it.x < -0.2 * W || it.x > 1.2 * W || it.y < -0.2 * H || it.y > 1.2 * H }) return null

        // 映射回原图坐标
        val inv = 1f / scale
        return corners.map { PointF(it.x * inv, it.y * inv) }
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 180.0
        if (d > 90) d = 180 - d
        return d
    }

    /** 稳健分位数（支持点较少的输入） */
    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** 两条极坐标直线求交（像素坐标） */
    private fun intersect(r1: Double, t1: Double, r2: Double, t2: Double): PointF? {
        val det = sin(t2 - t1)
        if (abs(det) < 1e-6) return null
        val x = (r2 * sin(t1) - r1 * sin(t2)) / det
        val y = (r1 * cos(t2) - r2 * cos(t1)) / det
        return PointF(x.toFloat(), y.toFloat())
    }

    /** 四边形面积（鞋带公式） */
    private fun quadArea(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2.0
    }
}
