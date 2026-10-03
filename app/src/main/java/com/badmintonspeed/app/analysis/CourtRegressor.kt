package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 关键点配准器（v2.15，采纳外部专业 AI 建议第2条"回归关键点 + 套用刚性模板"）。
 *
 * 思路：从"检测线"改为"配准模板"——
 *  1. 白线掩码 + 行/列扫描出白色长线段（用户建议的"扫长实线"）
 *  2. 线段点 RANSAC 拟合出若干横线/纵线，两两求交 → 候选关键点（等价于"预测 10~14 个交点"的几何实现）
 *  3. 对候选关键点做 RANSAC 单应性拟合到 BWF 标准模板（13.40 x 6.10 m），
 *     内点（重投影误差小）最多的一组胜出
 *  4. 由胜出的单应性反投影模板四个角点 → 输出场地角点（像素）
 *
 * 好处（对应建议）：闭合集配准而非开放检测；拓扑天然正确（线由模板几何生成，不会连错）；
 * 即使只检测到 4~5 个交点也能拟合；鲁棒性远优于纯线段检测。
 */
object CourtRegressor {

    private const val WORK_MAX_SIDE = 720

    /** 依次返回 左上、右上、右下、左下 四个角点（原图像素坐标），失败返回 null。
     *  @param roi 目标场地多边形（原图坐标），非空时白线掩码先做 ROI 拦截（B误检修复） */
    fun regress(frame: Bitmap, roi: List<PointF>? = null): List<PointF>? {
        val srcW = frame.width
        val srcH = frame.height
        val scale = min(1f, WORK_MAX_SIDE.toFloat() / max(srcW, srcH))
        val W = (srcW * scale).roundToInt().coerceAtLeast(1)
        val H = (srcH * scale).roundToInt().coerceAtLeast(1)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(frame, W, H, true) else frame

        val gray = IntArray(W * H)
        val sat = IntArray(W * H)
        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        if (bmp !== frame) bmp.recycle()
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            sat[i] = max(r, max(g, b)) - min(r, min(g, b))
        }

        // ---- 1. 白色长线掩码（场地线 = 亮 + 低饱和） ----
        val white = BooleanArray(W * H)
        for (i in gray.indices) {
            white[i] = gray[i] > 120 && sat[i] < 70
        }
        // ROI 拦截：多边形外的白点全部剔除（邻场线/广告字/地板缝）
        if (roi != null && roi.size >= 3) {
            val roiMask = polygonMask(W, H, roi, scale)
            for (i in white.indices) {
                if (!roiMask[i]) white[i] = false
            }
        }

        // ---- 2. 行/列扫描出线段点集 ----
        // 横向线段（穿过白线的每一行，段长≥帧宽4%）
        val hPts = ArrayList<PointF>()
        val MIN_RUN = (W * 0.04f).toInt().coerceAtLeast(8)
        for (y in 0 until H) {
            var x = 0
            while (x < W) {
                if (!white[y * W + x]) { x++; continue }
                var end = x
                while (end < W && white[y * W + end]) end++
                if (end - x >= MIN_RUN) {
                    hPts.add(PointF((x + end) / 2f, y.toFloat()))
                }
                x = end + 1
            }
        }
        // 纵向线段（每列，段长≥帧高4%）
        val vPts = ArrayList<PointF>()
        val MIN_RUN_V = (H * 0.04f).toInt().coerceAtLeast(8)
        for (x in 0 until W) {
            var y = 0
            while (y < H) {
                if (!white[y * W + x]) { y++; continue }
                var end = y
                while (end < H && white[end * W + x]) end++
                if (end - y >= MIN_RUN_V) {
                    vPts.add(PointF(x.toFloat(), (y + end) / 2f))
                }
                y = end + 1
            }
        }
        if (hPts.size < 12 || vPts.size < 12) return null

        // ---- 3. 点集拟合直线（RANSAC 提取最多 5 条横向 / 5 条纵向） ----
        val hLines = fitParallelLines(hPts, W, H)
        val vLines = fitParallelLines(vPts, W, H)
        if (hLines.size < 2 || vLines.size < 2) return null

        // ---- 4. 横线 x 纵线 两两求交 → 候选关键点 ----
        val keyPoints = ArrayList<PointF>()
        for (hl in hLines) {
            for (vl in vLines) {
                val pt = intersect(hl, vl) ?: continue
                if (pt.x in 0f..W.toFloat() && pt.y in 0f..H.toFloat()) {
                    keyPoints.add(pt)
                }
            }
        }
        if (keyPoints.size < 6) return null

        // ---- 5. RANSAC 拟合 BWF 模板（6.10 x 13.40 米 4 角） ----
        val rng = java.util.Random(42)
        val n = keyPoints.size
        val template = StandardCourt.corners
        var bestScore = -1f
        var bestH: FloatArray? = null
        val iterations = min(240, max(60, n * n / 2))
        val tol = W * 0.10f // 内点重投影误差阈值（像素）
        for (iter in 0 until iterations) {
            // 采样 4 点：要求覆盖画面不同区域（避免共线退化）
            val idx = IntArray(4)
            idx[0] = rng.nextInt(n)
            idx[1] = rng.nextInt(n)
            idx[2] = rng.nextInt(n)
            idx[3] = rng.nextInt(n)
            val p0 = keyPoints[idx[0]]
            val p1 = keyPoints[idx[1]]
            val p2 = keyPoints[idx[2]]
            val p3 = keyPoints[idx[3]]
            // 快速退化检查：四点张成的四边形面积太小则重试
            val quadArea = abs(
                (p1.x - p0.x) * (p3.y - p0.y) - (p1.y - p0.y) * (p3.x - p0.x) +
                (p3.x - p2.x) * (p0.y - p2.y) - (p3.y - p2.y) * (p0.x - p2.x)
            ) / 2f
            if (quadArea < W * H * 0.02f) continue
            val h = try {
                Homography.compute(listOf(p0, p1, p2, p3), template)
            } catch (e: Exception) { null }
            if (h == null) continue
            // 内点计数：把模板角点反投影回像素，与最近关键点距离 < tol
            var score = 0f
            for (c in 0 until 4) {
                val t = template[c]
                val proj = Homography.courtToImage(h, t.x, t.y)
                var minD = Double.MAX_VALUE
                for (kp in keyPoints) {
                    val d = hypot((kp.x - proj.x).toDouble(), (kp.y - proj.y).toDouble())
                    if (d < minD) minD = d
                }
                if (minD < tol.toDouble()) score += 1f
            }
            // 用全部关键点内点数做第二判据
            if (score > bestScore) {
                bestScore = score
                bestH = h
            }
        }
        if (bestH == null || bestScore < 2f) return null

        // ---- 6. 反投影模板 4 角 → 原图像素 ----
        val corners = template.map { Homography.courtToImage(bestH!!, it.x, it.y) }
        val scaled = corners.map { PointF(it.x / scale, it.y / scale) }
        return scaled
    }

    /** 多边形 → 掩码（白色=多边形内） */
    private fun polygonMask(W: Int, H: Int, roiPx: List<PointF>, scale: Float): BooleanArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val paint = Paint().apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val path = Path()
            val p0 = roiPx[0]
            path.moveTo(p0.x * scale, p0.y * scale)
            for (i in 1 until roiPx.size) {
                val p = roiPx[i]
                path.lineTo(p.x * scale, p.y * scale)
            }
            path.close()
            canvas.drawPath(path, paint)
            val px = IntArray(W * H)
            bmp.getPixels(px, 0, W, 0, 0, W, H)
            val mask = BooleanArray(W * H)
            for (i in px.indices) {
                mask[i] = (px[i] ushr 24) > 0x7F
            }
            return mask
        } finally {
            bmp.recycle()
        }
    }

    /** 从点集 RANSAC 拟合最多 maxLines 条直线（横向点/纵向点分别调用） */
    private fun fitParallelLines(pts: List<PointF>, W: Int, H: Int): List<DoubleArray> {
        val lines = ArrayList<DoubleArray>()
        var remaining = pts
        for (t in 0 until 5) {
            if (remaining.size < 20) break
            val line = ransacLine(remaining, W, H) ?: break
            lines.add(line)
            // 移除该线附近的点（距离 < 6px），避免重复拟合同一条线
            val nx = line[0]; val ny = line[1]; val c = line[2]
            remaining = remaining.filter {
                abs((it.x * nx + it.y * ny + c).toDouble()) > 6.0
            }
        }
        // 按线在画面中的位置排序（横向点按 y，纵向点按 x）
        lines.sortBy { it[3] }
        return lines
    }

    /** RANSAC 拟合一条直线，返回 [nx, ny, c, midX, midY]（法向量 + 直线常量 + 中点） */
    private fun ransacLine(pts: List<PointF>, W: Int, H: Int): DoubleArray? {
        val rng = java.util.Random(7)
        var bestInliers = -1
        var best: DoubleArray? = null
        for (iter in 0 until 100) {
            val a = pts[rng.nextInt(pts.size)]
            val b = pts[rng.nextInt(pts.size)]
            if (hypot(a.x - b.x, a.y - b.y) < 10f) continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-6f) continue
            val nx = -dy / len
            val ny = dx / len
            val c = -(nx * a.x + ny * a.y)
            var inliers = 0
            val tol = 6f
            for (p in pts) {
                val d = abs((p.x * nx + p.y * ny + c).toFloat())
                if (d < tol) inliers++
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                best = doubleArrayOf(nx.toDouble(), ny.toDouble(), c.toDouble(), ((a.x + b.x) / 2f).toDouble(), ((a.y + b.y) / 2f).toDouble())
            }
        }
        return best
    }

    /** 两条线（nx1,ny1,c1 / nx2,ny2,c2，即 nx*x+ny*y+c=0）求交 */
    private fun intersect(l1: DoubleArray, l2: DoubleArray): PointF? {
        val a1 = l1[0]; val b1 = l1[1]; val c1 = l1[2]
        val a2 = l2[0]; val b2 = l2[1]; val c2 = l2[2]
        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-6) return null
        val x = (b1 * c2 - b2 * c1) / det
        val y = (a2 * c1 - a1 * c2) / det
        return PointF(x.toFloat(), y.toFloat())
    }
}
