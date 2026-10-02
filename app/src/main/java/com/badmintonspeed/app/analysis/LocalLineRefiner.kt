package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 手动标定角点精修器（用户要求："不是死套模板，AI 对着4个框框起来的区域参考，识别出非常准确的贴合线"）：
 * 用户大致点出4个角后，本精修器在每个角点邻域内沿场地线方向搜索"连续长白线"，
 * 用白色像素带的加权中心做最小二乘直线拟合，4条精修直线两两求交得到精确角点。
 * 场地线是很细的连续长白线，比灯光/反光好识别得多。
 */
object LocalLineRefiner {

    /** 精修4角点：rough 顺序 [左上, 右上, 右下, 左下] */
    fun refine(frame: Bitmap, rough: List<PointF>): List<PointF> {
        if (rough.size != 4) return rough
        val gray = toGray(frame)

        // 4条边：0-1 顶边(上), 1-2 右边(右), 2-3 底边(下), 3-0 左边(左)
        val edges = listOf(
            0 to 1,
            1 to 2,
            2 to 3,
            3 to 0
        )

        // 每条边精修为一条直线 (k, b) 或 (m, n)（按 x/y 两种参数化）
        val lines = ArrayList<Line>()
        var fallbackUsed = 0
        for ((a, b) in edges) {
            val p1 = rough[a]
            val p2 = rough[b]
            val fitted = fitEdgeLine(gray, p1, p2, frame.width, frame.height)
            if (fitted != null) {
                lines.add(fitted)
            } else {
                fallbackUsed++
                lines.add(Line.fromPoints(p1, p2))
            }
        }

        // 相邻精修直线求交：line[0]&line[3]=角0(左上), line[0]&line[1]=角1(右上),
        // line[1]&line[2]=角2(右下), line[2]&line[3]=角3(左下)
        val refined = ArrayList<PointF>(4)
        refined.add(intersect(lines[0], lines[3]))
        refined.add(intersect(lines[0], lines[1]))
        refined.add(intersect(lines[1], lines[2]))
        refined.add(intersect(lines[2], lines[3]))

        // 合理性保护：精修角点若离原始角点太远（>10% 帧宽），说明拟合失败，回退原始点
        val maxShift = frame.width * 0.12f
        for (i in 0 until 4) {
            val orig = rough[i]
            val ref = refined[i]
            if (abs(ref.x - orig.x) > maxShift || abs(ref.y - orig.y) > maxShift) {
                refined[i] = orig
            }
        }
        return refined
    }

    /**
     * 沿边 (p1→p2) 法向搜索白色像素带，拟合精确直线。
     * 在边上均匀采样 sampleN 个点，每个采样点沿法向 ±halfW 范围内找亮度>threshold 的
     * 连续像素带，取加权中心作为该处线的精确位置；再用这些点做最小二乘直线拟合。
     */
    private fun fitEdgeLine(
        gray: IntArray,
        p1: PointF, p2: PointF,
        frameW: Int, frameH: Int
    ): Line? {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 8f) return null
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux

        val sampleN = 18
        val halfW = 18
        val fitPoints = ArrayList<PointF>()
        for (s in 0 until sampleN) {
            val t = s.toFloat() / (sampleN - 1)
            val cx = p1.x + ux * len * t
            val cy = p1.y + uy * len * t
            // 沿法向扫描找白色像素带（连续白线）
            var bestOffset = Float.NaN
            var bestScore = 0f
            for (off in -halfW..halfW) {
                val px = (cx + nx * off).toInt()
                val py = (cy + ny * off).toInt()
                if (px in 1 until frameW - 1 && py in 1 until frameH - 1) {
                    val g = gray[py * frameW + px]
                    if (g > 170) { // 白线
                        var score = 1f
                        // 左右（沿法向）都有更暗的像素 → 是线不是大片白
                        val before = gray[py * frameW + (px - 1).coerceIn(0, frameW - 1)]
                        val after = gray[py * frameW + (px + 1).coerceIn(0, frameW - 1)]
                        if (before < g - 25) score += 0.5f
                        if (after < g - 25) score += 0.5f
                        if (score > bestScore) {
                            bestScore = score
                            bestOffset = off.toFloat()
                        }
                    }
                }
            }
            if (bestOffset.isNaN()) return null // 该边上找不到白线 → 用原边
            fitPoints.add(PointF(cx + nx * bestOffset, cy + ny * bestOffset))
        }
        return fitLeastSquares(fitPoints)
    }

    /** 最小二乘直线拟合：根据主导方向选择 y=kx+b 或 x=my+n */
    private fun fitLeastSquares(pts: List<PointF>): Line {
        var sumX = 0f; var sumY = 0f
        for (p in pts) { sumX += p.x; sumY += p.y }
        val mx = sumX / pts.size
        val my = sumY / pts.size
        val spanX = pts.maxOf { it.x } - pts.minOf { it.x }
        val spanY = pts.maxOf { it.y } - pts.minOf { it.y }
        return if (spanX >= spanY) {
            // y = kx + b
            var sxy = 0f; var sxx = 0f
            for (p in pts) { sxy += (p.x - mx) * (p.y - my); sxx += (p.x - mx) * (p.x - mx) }
            val k = if (sxx < 1e-6f) 0f else sxy / sxx
            Line.kb(k, my - k * mx)
        } else {
            // x = my + n
            var sxy = 0f; var syy = 0f
            for (p in pts) { sxy += (p.y - my) * (p.x - mx); syy += (p.y - my) * (p.y - my) }
            val m = if (syy < 1e-6f) 0f else sxy / syy
            Line.mn(m, mx - m * my)
        }
    }

    private fun intersect(l1: Line, l2: Line): PointF {
        return when {
            l1.mode == 0 && l2.mode == 0 -> { // 两条都是 y=kx+b
                val k1 = l1.a; val b1 = l1.b
                val k2 = l2.a; val b2 = l2.b
                if (abs(k1 - k2) < 1e-6f) PointF(0f, 0f)
                else {
                    val x = (b2 - b1) / (k1 - k2)
                    PointF(x, k1 * x + b1)
                }
            }
            l1.mode == 0 && l2.mode == 1 -> // y=k1 x+b1 与 x=m2 y+n2
                intersectKbMn(l1.a, l1.b, l2.a, l2.b)
            l1.mode == 1 && l2.mode == 0 -> intersectKbMn(l2.a, l2.b, l1.a, l1.b)
            else -> { // 两条都是 x=my+n
                val m1 = l1.a; val n1 = l1.b
                val m2 = l2.a; val n2 = l2.b
                if (abs(m1 - m2) < 1e-6f) PointF(0f, 0f)
                else {
                    val y = (n2 - n1) / (m1 - m2)
                    PointF(m1 * y + n1, y)
                }
            }
        }
    }

    /** 直线1 y=kx+b，直线2 x=my+n → 交点：x = m y + n, y = k x + b → y(1-km) = kn + b */
    private fun intersectKbMn(k: Float, b: Float, m: Float, n: Float): PointF {
        if (abs(m) < 1e-6f) return PointF(n, k * n + b)
        val yy = if (abs(1f - k * m) < 1e-6f) 0f else (k * n + b) / (1f - k * m)
        return PointF(m * yy + n, yy)
    }

    /** 直线表示：mode=0 → y = a*x + b；mode=1 → x = a*y + b */
    private class Line(val mode: Int, val a: Float, val b: Float) {
        companion object {
            fun kb(k: Float, b: Float) = Line(0, k, b)
            fun mn(m: Float, n: Float) = Line(1, m, n)
            fun fromPoints(p1: PointF, p2: PointF): Line {
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                return if (abs(dx) >= abs(dy)) {
                    val k = if (abs(dx) < 1e-6f) 0f else dy / dx
                    Line(0, k, p1.y - k * p1.x)
                } else {
                    val m = if (abs(dy) < 1e-6f) 0f else dx / dy
                    Line(1, m, p1.x - m * p1.y)
                }
            }
        }
    }

    private fun toGray(frame: Bitmap): IntArray {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }
}
