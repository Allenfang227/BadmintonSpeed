package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * v2.33 场地线两侧颜色校验器（用户要求："AI 标注好之后依然要自行调整，
 * 截取所标黄色的线，判断线两边的颜色是否基本一致……线两边背景色=场地颜色则正确，
 * 出现断节（黑色/其他颜色）则该段标注错误，重新标注"）。
 *
 * 原理：
 *   1. 从场地内部采样主色（蓝色/绿色场地）。
 *   2. 对外边界 4 条线，沿线每隔几像素采样，取线法向两侧各 5px 的像素颜色。
 *   3. 两侧颜色都≈场地主色 → 该段正确；一侧或两侧出现黑色/其他色 → 该段错误。
 *   4. 错误段尝试沿线法向 ±3/±6 像素微调，取两侧最接近场地色的位置。
 *   5. 一条线错误段比例过高时，整体沿法向平移到最佳位置。
 *   6. 4 条微调后的线重新求交，得到修正后的 4 角点。
 *   7. 合理性保护：偏移不超过帧宽 5%，防止过拟合。
 */
object CourtLineColorValidator {

    data class Result(
        val corners: List<PointF>,
        val adjusted: Boolean,
        val passRate: Float   // 外边界线平均正确段比例（0-1）
    )

    private const val SAMPLE_STEP_PX = 8f       // 沿线采样间隔
    private const val SIDE_OFFSET_PX = 5f        // 线两侧取色偏移
    private const val COLOR_DIST_THRESHOLD = 55f // RGB 色差阈值（场地色有光影变化，宽松）
    private const val LINE_PASS_THRESHOLD = 0.55f // 单条线正确段比例低于此则微调
    private const val MAX_SHIFT_PX = 18f          // 单条线最大法向平移
    private val TRY_SHIFTS = intArrayOf(-6, -3, 0, 3, 6) // 错误段尝试的法向偏移

    /** 校验并微调场地 4 角点。corners 顺序 [左上,右上,右下,左下] */
    fun validate(frame: Bitmap, corners: List<PointF>): Result {
        if (corners.size != 4 || frame.width <= 0 || frame.height <= 0) {
            return Result(corners, false, 0f)
        }
        val h = try { Homography.compute(corners, StandardCourt.corners) } catch (e: Exception) {
            return Result(corners, false, 0f)
        }

        // 1. 场地主色
        val courtColor = sampleCourtColor(frame, h) ?: return Result(corners, false, 0f)

        // 2. 外边界 4 条线（场地坐标，米）：顶、右、底、左
        val edges = listOf(
            CourtEdge(0f, 0f, 6.10f, 0f),
            CourtEdge(6.10f, 0f, 6.10f, 13.40f),
            CourtEdge(6.10f, 13.40f, 0f, 13.40f),
            CourtEdge(0f, 13.40f, 0f, 0f)
        )

        // 3. 每条线校验+微调
        var totalPass = 0f
        val adjusted = edges.map { edge ->
            val r = validateEdge(frame, h, edge, courtColor)
            totalPass += r.passRate
            r
        }
        val avgPass = totalPass / edges.size

        // 4. 重新求交（微调后的 4 条一般式线）
        val newCorners = listOf(
            intersect(adjusted[0].line, adjusted[3].line), // 左上 = 顶∩左
            intersect(adjusted[0].line, adjusted[1].line), // 右上 = 顶∩右
            intersect(adjusted[1].line, adjusted[2].line), // 右下 = 右∩底
            intersect(adjusted[2].line, adjusted[3].line)  // 左下 = 底∩左
        )

        // 5. 合理性保护：单角偏移不超过帧宽 5%
        val maxShift = frame.width * 0.05f
        val finalCorners = newCorners.mapIndexed { i, p ->
            if (p == null || hypot(p.x - corners[i].x, p.y - corners[i].y) > maxShift) {
                corners[i]
            } else {
                p
            }
        }
        val changed = finalCorners.withIndex().any { (i, p) ->
            hypot(p.x - corners[i].x, p.y - corners[i].y) > 0.5f
        }
        return Result(finalCorners, changed, avgPass)
    }

    // ---- 内部 ----

    private data class CourtEdge(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private data class LineEq(val a: Float, val b: Float, val c: Float) // ax+by+c=0
    private data class EdgeResult(val line: LineEq, val passRate: Float)

    /** 从场地内部采样主色：取多个场地内点的 3x3 区域平均色，再取中位数 */
    private fun sampleCourtColor(frame: Bitmap, h: FloatArray): IntArray? {
        val probes = listOf(
            3.05f to 6.70f,  // 网中心
            1.50f to 3.00f, 4.60f to 3.00f,
            1.50f to 10.40f, 4.60f to 10.40f,
            3.05f to 2.00f, 3.05f to 11.40f
        )
        val colors = ArrayList<IntArray>()
        for ((cx, cy) in probes) {
            val p = Homography.courtToImage(h, cx, cy)
            val avg = avgColor(frame, p.x.toInt(), p.y.toInt(), 2)
            if (avg != null) colors.add(avg)
        }
        if (colors.size < 3) return null
        // 中位数（RGB 各分量）
        val rs = colors.map { it[0] }.sorted()
        val gs = colors.map { it[1] }.sorted()
        val bs = colors.map { it[2] }.sorted()
        val mid = colors.size / 2
        return intArrayOf(rs[mid], gs[mid], bs[mid])
    }

    /** 取 (cx,cy) 周围 radius 像素的平均 RGB */
    private fun avgColor(frame: Bitmap, cx: Int, cy: Int, radius: Int): IntArray? {
        if (cx < radius || cy < radius || cx >= frame.width - radius || cy >= frame.height - radius) return null
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val c = frame.getPixel(cx + dx, cy + dy)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0) return null
        return intArrayOf(r / n, g / n, b / n)
    }

    /** 校验单条外边界线，返回微调后的一般式线 + 正确段比例 */
    private fun validateEdge(frame: Bitmap, h: FloatArray, edge: CourtEdge, courtColor: IntArray): EdgeResult {
        val p1 = Homography.courtToImage(h, edge.x1, edge.y1)
        val p2 = Homography.courtToImage(h, edge.x2, edge.y2)
        val len = hypot(p2.x - p1.x, p2.y - p1.y)
        if (len < 20f) return EdgeResult(toLineEq(p1, p2), 0f)

        val dx = (p2.x - p1.x) / len
        val dy = (p2.y - p1.y) / len
        val nx = -dy  // 法向
        val ny = dx

        val steps = (len / SAMPLE_STEP_PX).toInt().coerceIn(8, 100)
        var pass = 0
        val bestShifts = ArrayList<Int>()

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val cx = p1.x + dx * len * t
            val cy = p1.y + dy * len * t
            // 原始位置两侧颜色
            val d0 = sideColorDist(frame, cx, cy, nx, ny, courtColor)
            if (d0 < COLOR_DIST_THRESHOLD) {
                pass++
            } else {
                // 错误段：尝试法向微调，取两侧色差和最小的偏移
                var bestShift = 0
                var bestDist = d0
                for (s in TRY_SHIFTS) {
                    val d = sideColorDist(frame, cx + nx * s, cy + ny * s, nx, ny, courtColor)
                    if (d < bestDist) { bestDist = d; bestShift = s }
                }
                bestShifts.add(bestShift)
            }
        }

        val passRate = pass.toFloat() / (steps + 1)
        // 正确段比例低 → 整体沿法向平移（取错误段最佳偏移的中位数）
        var shift = 0f
        if (passRate < LINE_PASS_THRESHOLD && bestShifts.isNotEmpty()) {
            val sorted = bestShifts.sorted()
            shift = sorted[sorted.size / 2].toFloat().coerceIn(-MAX_SHIFT_PX, MAX_SHIFT_PX)
        }
        val np1 = PointF(p1.x + nx * shift, p1.y + ny * shift)
        val np2 = PointF(p2.x + nx * shift, p2.y + ny * shift)
        return EdgeResult(toLineEq(np1, np2), passRate)
    }

    /** 取线法向两侧各 SIDE_OFFSET_PX 处的颜色与场地主色的色差（取两侧较大值，要求两侧都对） */
    private fun sideColorDist(frame: Bitmap, cx: Float, cy: Float, nx: Float, ny: Float, courtColor: IntArray): Float {
        val lx = (cx + nx * SIDE_OFFSET_PX).toInt()
        val ly = (cy + ny * SIDE_OFFSET_PX).toInt()
        val rx = (cx - nx * SIDE_OFFSET_PX).toInt()
        val ry = (cy - ny * SIDE_OFFSET_PX).toInt()
        if (lx < 0 || ly < 0 || lx >= frame.width || ly >= frame.height ||
            rx < 0 || ry < 0 || rx >= frame.width || ry >= frame.height
        ) return Float.MAX_VALUE
        val lc = frame.getPixel(lx, ly)
        val rc = frame.getPixel(rx, ry)
        val dl = colorDist(lc, courtColor)
        val dr = colorDist(rc, courtColor)
        return max(dl, dr) // 两侧都要接近场地色，取较大值
    }

    private fun colorDist(c: Int, ref: IntArray): Float {
        val dr = ((c shr 16) and 0xFF) - ref[0]
        val dg = ((c shr 8) and 0xFF) - ref[1]
        val db = (c and 0xFF) - ref[2]
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun sqrt(v: Int): Float = kotlin.math.sqrt(v.toFloat())

    private fun toLineEq(p1: PointF, p2: PointF): LineEq {
        val a = p2.y - p1.y
        val b = p1.x - p2.x
        val c = -(a * p1.x + b * p1.y)
        return LineEq(a, b, c)
    }

    private fun intersect(l1: LineEq, l2: LineEq): PointF? {
        val det = l1.a * l2.b - l2.a * l1.b
        if (kotlin.math.abs(det) < 1e-6f) return null
        val x = (l1.b * l2.c - l2.b * l1.c) / det
        val y = (l2.a * l1.c - l1.a * l2.c) / det
        return PointF(x, y)
    }
}
