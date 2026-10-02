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
 * AI 自动场地标定 v2.5（对应参考图1 的"场地基准检测"模块）：
 *   Canny边缘检测 -> 霍夫直线变换 -> RANSAC迭代拟合 -> 单应性矩阵计算
 *
 * v2.5 多策略增强（修复 E101 场地检测失败）：
 *   - 策略A：Otsu 自适应阈值白线掩码（常规球馆，蓝色/绿色地胶+白线）
 *   - 策略B：固定阈值 170 白线掩码（过曝/低对比球馆）
 *   - 策略C：Sobel 亮度边缘掩码（线不白/彩线/昏暗球馆，不依赖颜色）
 *   任一策略成功即返回；每个策略内部：
 *   - 方向组枚举：霍夫峰值按角度聚类成多组，枚举夹角 70-110° 的平行线组
 *   - 四边形评分：面积占比 + 沿边白线贴合度综合打分，取最优候选
 *   - 面积下限放宽到 10%（适配远景/竖屏拍摄的小场地）
 */
object CourtAutoCalibrator {

    /** 方向组：同方向直线簇（角度 + 各线到原点距离 rho 列表 + 总投票数） */
    data class DirGroup(val angleDeg: Double, val rhos: List<Double>, val totalVotes: Int)

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

        // ---- 1) 灰度 + 饱和度 ----
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

        // ---- 2) 多策略掩码：任一成功即返回 ----
        // 策略A：Otsu 自适应白线（常规球馆）
        val maskA = whiteMask(gray, sat, otsu(gray).coerceIn(150, 215))
        // 策略B：固定阈值 170 白线（过曝/低对比球馆）
        val maskB = whiteMask(gray, sat, 170)
        // 策略C：Sobel 亮度边缘（不依赖颜色，暗场馆/彩线场地可用）
        val maskC = sobelMask(gray, W, H)

        for (mask in listOf(maskA, maskB, maskC)) {
            val corners = calibrateWithMask(mask, W, H, allowPartial = true)
            if (corners != null) {
                val inv = 1f / scale
                return corners.map { PointF(it.x * inv, it.y * inv) }
            }
        }
        return null
    }

    /** 白线掩码：亮度达标且低饱和 */
    private fun whiteMask(gray: IntArray, sat: IntArray, threshold: Int): BooleanArray {
        val mask = BooleanArray(gray.size)
        for (i in gray.indices) {
            if (gray[i] >= threshold && sat[i] <= WHITE_SAT_DIFF) mask[i] = true
        }
        return mask
    }

    /** Sobel 亮度边缘掩码：不依赖颜色，明显的亮度边缘都保留 */
    private fun sobelMask(gray: IntArray, W: Int, H: Int): BooleanArray {
        val mask = BooleanArray(W * H)
        for (y in 1 until H - 1) {
            val row0 = (y - 1) * W
            val row1 = y * W
            val row2 = (y + 1) * W
            for (x in 1 until W - 1) {
                if (gray[row1 + x] < 40) continue
                val gx = (gray[row0 + x + 1] + 2 * gray[row1 + x + 1] + gray[row2 + x + 1]) -
                    (gray[row0 + x - 1] + 2 * gray[row1 + x - 1] + gray[row2 + x - 1])
                val gy = (gray[row2 + x - 1] + 2 * gray[row2 + x] + gray[row2 + x + 1]) -
                    (gray[row0 + x - 1] + 2 * gray[row0 + x] + gray[row0 + x + 1])
                if (abs(gx) + abs(gy) > 160) mask[row1 + x] = true
            }
        }
        return mask
    }

    /** 在给定掩码上执行：形态学闭 -> 霍夫 -> 方向组枚举 -> 四边形评分；失败时尝试"部分线+羽毛球先验"构造 */
    private fun calibrateWithMask(maskIn: BooleanArray, W: Int, H: Int, allowPartial: Boolean): List<PointF>? {
        // ---- 形态学闭：膨胀 + 腐蚀（3x3 十字）----
        val closed = BooleanArray(W * H)
        val dil = BooleanArray(W * H)
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                if (maskIn[y * W + x] || maskIn[y * W + x - 1] || maskIn[y * W + x + 1] ||
                    maskIn[(y - 1) * W + x] || maskIn[(y + 1) * W + x]
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

        // ---- 霍夫直线投票 ----
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

        // ---- 峰值提取 ----
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

        // 保留高票线（>= 15% 峰值）
        val strong = candidates.filter { it.votes >= maxVotes * 0.15 }

        // ---- 角度聚类成方向组 ----
        val sorted = strong.sortedBy { it.thetaDeg }
        val groups = ArrayList<DirGroup>()
        var curAngles = ArrayList<Double>()
        var curRhos = ArrayList<Double>()
        var curVotes = 0
        var curStart = sorted.first().thetaDeg
        for (l in sorted) {
            if (curAngles.isEmpty() || angleJoin(curStart, curAngles.lastOrNull(), l.thetaDeg)) {
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
        val gSorted = groups.sortedByDescending { it.totalVotes }

        // ---- 枚举方向组组合，构造候选四边形并评分 ----
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
                if (areaRatio < 0.10 || areaRatio > 0.97) continue
                val sides = edgeLengths(corners)
                val minSide = sides.min() ?: continue
                val maxSide = sides.max() ?: continue
                if (maxSide / minSide > 6.0) continue
                val fit = lineFitScore(closed, W, H, corners)
                val score = fit * (0.6 + 0.4 * areaRatio.coerceAtMost(1.0))
                candidatesQ.add(Candidate(corners, score, area))
            }
        }
        if (candidatesQ.isEmpty()) return partialConstruct(maskIn, W, H, groups, allowPartial)
        val best = candidatesQ.maxBy { it.score }
        if (best.score < 0.08) return partialConstruct(maskIn, W, H, groups, allowPartial)
        return best.corners
    }

    /**
     * 阶段2：部分线 + 羽毛球先验构造完整场地（用户场景：斜拍远景，场地近端角在画面外）
     *
     * 原理：检测到"一组 ≥2 条平行线（边线）" + "另一组 ≥1 条线（底线/横线）"后，
     * 用羽毛球场固定长宽比（13.40m / 6.10m ≈ 2.196）沿纵深方向把场地延伸到画面边缘，
     * 构造出完整羽毛球场地覆盖在画面上——不要求四条边都可见。
     */
    private fun partialConstruct(
        maskIn: BooleanArray, W: Int, H: Int, groups: List<DirGroup>, allowPartial: Boolean
    ): List<PointF>? {
        if (!allowPartial || groups.size < 2) return null
        // 主组：平行线最多的一组（边线）
        val gMain = groups.maxByOrNull { it.rhos.size } ?: return null
        if (gMain.rhos.size < 2) return null
        // 副组：与主组近似垂直、线数最多的一组（底线/横线）
        val gOther = groups.filter { angleDiff(it.angleDeg, gMain.angleDeg) in 70.0..110.0 }
            .maxByOrNull { it.rhos.size } ?: return null
        if (gOther.rhos.isEmpty()) return null
        // 副组若有 ≥2 条线，正常四边形逻辑应已成功，这里只处理"单条横线"场景
        if (gOther.rhos.size > 1) return null

        val m = gMain.rhos.sorted()
        val r1 = quantile(m, 0.15)
        val r2 = quantile(m, 0.85)
        if (abs(r2 - r1) < 8) return null
        val thetaM = gMain.angleDeg * PI / 180.0
        val thetaO = gOther.angleDeg * PI / 180.0

        val p1 = intersect(r1, thetaM, gOther.rhos[0], thetaO) ?: return null
        val p2 = intersect(r2, thetaM, gOther.rhos[0], thetaO) ?: return null

        // 纵深方向 = 主组线走向；选"远离画面中心"的方向（场地朝画面边缘延伸）
        val dx = cos(thetaM + PI / 2)
        val dy = sin(thetaM + PI / 2)
        val cx = (p1.x + p2.x) / 2.0 - W / 2.0
        val cy = (p1.y + p2.y) / 2.0 - H / 2.0
        val dot = dx * cx + dy * cy
        val ux = if (dot > 0) dx else -dx
        val uy = if (dot > 0) dy else -dy

        // 延伸长度：羽毛球先验 2.196 倍底线长度；若先到达画面边缘则以画面边缘为准
        val baseLen = max(abs(p2.x - p1.x).toDouble(), abs(p2.y - p1.y).toDouble())
        val depthFull = baseLen * 2.196
        val tB = min(rayToBoundary(p1.x.toDouble(), p1.y.toDouble(), ux, uy, W, H),
                     rayToBoundary(p2.x.toDouble(), p2.y.toDouble(), ux, uy, W, H))
        var D = if (tB < depthFull) tB else depthFull
        if (D < min(W, H) * 0.35) D = min(W, H) * 0.35
        if (D <= 0) return null

        val p1n = PointF((p1.x + ux * D).toFloat(), (p1.y + uy * D).toFloat())
        val p2n = PointF((p2.x + ux * D).toFloat(), (p2.y + uy * D).toFloat())
        val quad = listOf(p1, p2, p2n, p1n)
        val corners = orderCorners(quad) ?: return null
        val area = quadArea(corners)
        if (area / (W * H) < 0.06) return null

        // 贴合度验证：4 条边至少 2 条有真实白线支撑（可见边），避免墙面/座椅噪声线构造出假场地
        val fits = edgeFitScores(maskIn, W, H, corners)
        if (fits.count { it > 0.15 } < 2) return null
        return corners
    }

    /** 射线到画面边界的距离（单位：方向向量长度倍数）；射线不出画面则返回极大值 */
    private fun rayToBoundary(px: Double, py: Double, ux: Double, uy: Double, W: Int, H: Int): Double {
        var t = Double.MAX_VALUE
        if (ux > 1e-9) t = min(t, (W - 1 - px) / ux)
        if (ux < -1e-9) t = min(t, px / -ux)
        if (uy > 1e-9) t = min(t, (H - 1 - py) / uy)
        if (uy < -1e-9) t = min(t, py / -uy)
        return t
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
     * 白线贴合度：沿四边形四边均匀采样，统计采样点附近是否有掩码线像素。
     * 得分 0-1，越高说明检测框越贴合实际场地线。画面外采样点跳过（不计分）。
     */
    private fun lineFitScore(mask: BooleanArray, W: Int, H: Int, corners: List<PointF>): Double {
        val fits = edgeFitScores(mask, W, H, corners)
        if (fits.isEmpty()) return 0.0
        return fits.average()
    }

    /** 每条边单独的白线贴合度（返回 4 个值，对应 4 条边） */
    private fun edgeFitScores(mask: BooleanArray, W: Int, H: Int, corners: List<PointF>): List<Double> {
        val scores = ArrayList<Double>(4)
        for (e in 0 until 4) {
            val a = corners[e]
            val b = corners[(e + 1) % 4]
            var hit = 0
            var total = 0
            val steps = 40
            for (k in 0..steps) {
                val f = k.toFloat() / steps
                val x = (a.x + (b.x - a.x) * f).roundToInt()
                val y = (a.y + (b.y - a.y) * f).roundToInt()
                if (x < 0 || x >= W || y < 0 || y >= H) continue // 画面外采样点不计
                total++
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
            scores.add(if (total == 0) 0.0 else hit.toDouble() / total)
        }
        return scores
    }
}
