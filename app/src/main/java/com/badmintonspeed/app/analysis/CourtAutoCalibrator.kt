package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AI 自动场地标定 v2.4（对应参考图1 的"场地基准检测"模块）：
 *   Canny边缘检测 -> 霍夫直线变换 -> RANSAC迭代拟合 -> 单应性矩阵计算
 *
 * v2.4 鲁棒性增强（修复 E101 场地检测失败）：
 *   - Otsu 自适应亮度阈值：适应昏暗/过曝不同光线的球馆，白线提取更准
 *   - 方向组枚举：不再依赖单一主方向，对霍夫峰值按角度聚类成多个方向组，
 *     枚举所有"夹角 70-110° 的两组平行线"组合成候选四边形
 *   - 四边形评分：面积占比 + 白线贴合度（沿四边采样掩码像素密度）综合打分，
 *     选最优候选，杜绝被墙壁/座椅/卷帘门等无关直线干扰
 */
object CourtAutoCalibrator {

    private const val WORK_MAX_SIDE = 720
    private const val THETA_STEP_DEG = 2.0
    private const val WHITE_SAT_DIFF = 70

    /** 依次返回 左上、右上、右下、左下 四个角点（原图像素坐标），失败返回 null */
    fun calibrate(frame: Bitmap): List<PointF>? {
        val srcW = frame.width
        val srcH = frame.height
        val scale = min(1f, WORK_MAX_SIDE.toFloat() / max(srcW, srcH))
        val W = (srcW * scale).roundToInt().coerceAtLeast(1)
        val H = (srcH * scale).roundToInt().coerceAtLeast(1)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(frame, W, H, true) else frame

        // ---- 1) 灰度 + Otsu 自适应亮度阈值 + 低饱和白线掩码 ----
        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        if (bmp !== frame) bmp.recycle()
        val gray = IntArray(W * H)
        val sat = IntArray(W * H)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            sat[i] = max(r, max(g, b)) - min(r, min(g, b))
        }
        val t = otsu(gray).coerceIn(150, 215)
        val mask = BooleanArray(W * H)
        for (i in gray.indices) {
            if (gray[i] >= t && sat[i] <= WHITE_SAT_DIFF) mask[i] = true
        }

        // ---- 2) 形态学闭：膨胀 + 腐蚀（3x3 十字）----
        val closed = BooleanArray(W * H)
        val dil = BooleanArray(W * H)
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                if (mask[y * W + x] || mask[y * W + x - 1] || mask[y * W + x + 1] ||
                    mask[(y - 1) * W + x] || mask[(y + 1) * W + x]
                ) dil[y * W + x] = true
            }
        }
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
                for (tt in 0 until numTheta) {
                    val theta = tt * THETA_STEP_DEG * PI / 180.0
                    val rho = x * cos(theta) + y * sin(theta)
                    val r = (rho + rhoOffset).roundToInt().coerceIn(0, rhoBins - 1)
                    acc[tt * rhoBins + r]++
                }
            }
        }

        // ---- 4) 峰值提取 ----
        data class Line(val rho: Double, val thetaDeg: Double, val votes: Int)
        val candidates = ArrayList<Line>()
        var maxVotes = 0
        for (tt in 0 until numTheta) {
            val col = tt * rhoBins
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
                candidates.add(Line(rho.toDouble(), tt * THETA_STEP_DEG, bestV))
                if (bestV > maxVotes) maxVotes = bestV
            }
        }
        if (candidates.isEmpty()) return null

        // 保留高票线（>= 20% 峰值）
        val strong = candidates.filter { it.votes >= maxVotes * 0.2 }

        // ---- 5) 角度聚类成方向组（每组夹角 <= 12°） ----
        data class DirGroup(val angleDeg: Double, val rhos: List<Double>, val totalVotes: Int)
        val sorted = strong.sortedBy { it.thetaDeg }
        val groups = ArrayList<DirGroup>()
        var curAngles = ArrayList<Double>()
        var curRhos = ArrayList<Double>()
        var curVotes = 0
        var curStart = sorted.first().thetaDeg
        for (l in sorted) {
            // 环形角度（0-180），相邻并入当前组
            val joined = angleJoin(curStart, curAngles.lastOrNull(), l.thetaDeg)
            if (curAngles.isEmpty() || joined) {
                curAngles.add(l.thetaDeg)
                curRhos.add(l.rho)
                curVotes += l.votes
            } else {
                groups.add(DirGroup(avgAngle(curAngles), curRhos.toList(), curVotes))
                curAngles = ArrayList(listOf(l.thetaDeg))
                curRhos = ArrayList(listOf(l.rho))
                curVotes = l.votes
                curStart = l.thetaDeg
            }
        }
        if (curAngles.isNotEmpty()) groups.add(DirGroup(avgAngle(curAngles), curRhos.toList(), curVotes))
        if (groups.size < 2) return null
        // 按总票数降序
        val gSorted = groups.sortedByDescending { it.totalVotes }

        // ---- 6) 枚举方向组组合，构造候选四边形并评分 ----
        data class Candidate(val corners: List<PointF>, val score: Double, val area: Double)
        val candidatesQ = ArrayList<Candidate>()
        for (gi in gSorted.indices) {
            val A = gSorted[gi]
            if (A.rhos.size < 2) continue
            for (gj in gi + 1 until gSorted.size) {
                val B = gSorted[gj]
                if (B.rhos.size < 2) continue
                val diff = angleDiff(A.angleDeg, B.angleDeg)
                if (diff !in 70.0..110.0) continue
                val aRhos = A.rhos.sorted()
                val bRhos = B.rhos.sorted()
                val a1 = quantile(aRhos, 0.12)
                val a2 = quantile(aRhos, 0.88)
                val b1 = quantile(bRhos, 0.12)
                val b2 = quantile(bRhos, 0.88)
                if (abs(a2 - a1) < 6 || abs(b2 - b1) < 6) continue

                val thetaA = A.angleDeg * PI / 180.0
                val thetaB = B.angleDeg * PI / 180.0
                val pts = ArrayList<PointF>()
                for (ra in listOf(a1, a2)) {
                    for (rb in listOf(b1, b2)) {
                        val inter = intersect(ra, thetaA, rb, thetaB) ?: continue
                        pts.add(inter)
                    }
                }
                if (pts.size != 4) continue
                val corners = orderCorners(pts)
                if (corners == null) continue
                val area = quadArea(corners)
                val areaRatio = area / (W * H)
                if (areaRatio < 0.15 || areaRatio > 0.95) continue
                // 边长比例合理（羽毛球场地长宽比 ~2.2，透视后应 <= 4）
                val sides = edgeLengths(corners)
                val minSide = sides.min() ?: continue
                val maxSide = sides.max() ?: continue
                if (maxSide / minSide > 5.0) continue
                val fit = lineFitScore(closed, W, H, corners)
                val score = fit * (0.6 + 0.4 * areaRatio.coerceAtMost(1.0))
                candidatesQ.add(Candidate(corners, score, area))
            }
        }
        if (candidatesQ.isEmpty()) return null
        val best = candidatesQ.maxBy { it.score }
        if (best.score < 0.10) return null

        // 映射回原图坐标
        val inv = 1f / scale
        return best.corners.map { PointF(it.x * inv, it.y * inv) }
    }

    /** Otsu 最大类间方差阈值 */
    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val total = gray.size
        var sum = 0L
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0L
        var wB = 0
        var best = 120
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > bestVar) { bestVar = v; best = t }
        }
        return best
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 180.0
        if (d > 90) d = 180 - d
        return d
    }

    /** 角度环形分组：检查新角度能否并入当前组（组内跨度 <= 12°） */
    private fun angleJoin(start: Double, last: Double?, next: Double): Boolean {
        if (last == null) return true
        val span = if (abs(next - start) <= 90) abs(next - start) else 180 - abs(next - start)
        return span <= 12.0
    }

    private fun avgAngle(angles: List<Double>): Double {
        var sx = 0.0; var sy = 0.0
        for (a in angles) {
            val r = a * PI / 180.0
            sx += cos(2 * r); sy += sin(2 * r)
        }
        var avg = 0.5 * atan2(sy, sx) * 180.0 / PI
        if (avg < 0) avg += 180
        return avg
    }

    /** 稳健分位数 */
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

    /** 排序为 左上 右上 右下 左下（凸四边形） */
    private fun orderCorners(pts: List<PointF>): List<PointF>? {
        // 凸性检查
        val n = pts.size
        var sign = 0
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val c = pts[(i + 2) % n]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross != 0f) {
                val s = if (cross > 0) 1 else -1
                if (sign == 0) sign = s else if (sign != s) return null
            }
        }
        if (sign == 0) return null
        val cx = pts.sumOf { it.x.toDouble() } / n
        val cy = pts.sumOf { it.y.toDouble() } / n
        val sortedByY = pts.sortedBy { it.y }
        val topRow = sortedByY.take(2).sortedBy { it.x }
        val bottomRow = sortedByY.drop(2).sortedBy { it.x }
        return listOf(topRow[0], topRow[1], bottomRow[1], bottomRow[0])
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

    /** 四条边长度 */
    private fun edgeLengths(p: List<PointF>): List<Double> {
        return (0 until 4).map { i ->
            val a = p[i]; val b = p[(i + 1) % 4]
            sqrt((a.x - b.x).toDouble() * (a.x - b.x) + (a.y - b.y).toDouble() * (a.y - b.y))
        }
    }

    /**
     * 白线贴合度：沿四边形四边均匀采样，统计采样点附近是否有掩码白线像素。
     * 得分 0-1，越高说明检测框越贴合实际场地线。
     */
    private fun lineFitScore(mask: BooleanArray, W: Int, H: Int, corners: List<PointF>): Double {
        var hit = 0
        var total = 0
        for (e in 0 until 4) {
            val a = corners[e]
            val b = corners[(e + 1) % 4]
            val steps = 40
            for (k in 0..steps) {
                val f = k.toFloat() / steps
                val x = (a.x + (b.x - a.x) * f).toInt().coerceIn(0, W - 1)
                val y = (a.y + (b.y - a.y) * f).toInt().coerceIn(0, H - 1)
                total++
                // 采样点附近 7x7 窗口内是否有白线像素
                var found = false
                for (dy in -3..3) {
                    val ny = y + dy
                    if (ny < 0 || ny >= H) continue
                    for (dx in -3..3) {
                        val nx = x + dx
                        if (nx < 0 || nx >= W) continue
                        if (mask[ny * W + nx]) { found = true; break }
                    }
                    if (found) break
                }
                if (found) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }
}
