package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 相机去畸变（v2.15，采纳外部专业 AI 建议第1条"相机去畸变"）。
 *
 * 无标定板时的"场景直线自标定"：
 *  羽毛球场地的白线在现实里是直线，桶形/枕形畸变会把它们拍成弧线。
 *  从图像提取若干条长白线段（横向+纵向），对候选径向畸变系数 k1 网格搜索——
 *  选择使所有线段"中间点最贴合两端连线"的 k1（线段弯曲度最小），
 *  再用该 k1 重映射整帧，得到去畸变图像。
 */
object DistortionEstimator {

    private const val WORK_MAX_SIDE = 720

    /** 估算畸变并校正：返回去畸变后的 Bitmap；若畸变可忽略返回 null（用原图） */
    fun undistort(frame: Bitmap): Bitmap? {
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

        // 白线掩码
        val white = BooleanArray(W * H)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            sat[i] = max(r, max(g, b)) - min(r, min(g, b))
            white[i] = gray[i] > 120 && sat[i] < 70
        }

        // 提取长线段（点集：横向段中点 + 纵向段中点）
        val segments = ArrayList<ArrayList<PointD>>()
        val MIN_RUN = (W * 0.08f).toInt().coerceAtLeast(16)
        for (y in 0 until H) {
            var x = 0
            while (x < W) {
                if (!white[y * W + x]) { x++; continue }
                var end = x
                while (end < W && white[y * W + end]) end++
                if (end - x >= MIN_RUN) {
                    val seg = ArrayList<PointD>()
                    for (sx in x..end step max(1, (end - x) / 12)) {
                        seg.add(PointD(sx.toDouble(), y.toDouble()))
                    }
                    if (seg.size >= 3) segments.add(seg)
                }
                x = end + 1
            }
        }
        for (x in 0 until W) {
            var y = 0
            while (y < H) {
                if (!white[y * W + x]) { y++; continue }
                var end = y
                while (end < H && white[end * W + x]) end++
                if (end - y >= MIN_RUN) {
                    val seg = ArrayList<PointD>()
                    for (sy in y..end step max(1, (end - y) / 12)) {
                        seg.add(PointD(x.toDouble(), sy.toDouble()))
                    }
                    if (seg.size >= 3) segments.add(seg)
                }
                y = end + 1
            }
        }
        if (segments.size < 3) return null

        // 网格搜索 k1：使所有线段中间点最贴合两端连线
        val cx = W / 2.0
        val cy = H / 2.0
        var bestK = 0.0
        var bestCost = Double.MAX_VALUE
        var k = -0.6
        while (k <= 0.6) {
            val cost = totalCurvature(segments, cx, cy, k)
            if (cost < bestCost) {
                bestCost = cost
                bestK = k
            }
            k += 0.02
        }
        // 畸变可忽略（|k1| < 0.02 或校正后弯曲度没有显著改善）→ 不校正
        if (abs(bestK) < 0.02) return null

        // 用 bestK 重映射整帧（r' = r * (1 + k1 * r^2)，主点=画面中心）
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(W * H)
        val maxR = hypot(cx, cy)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val dx = x - cx
                val dy = y - cy
                val r = hypot(dx.toDouble(), dy.toDouble())
                val factor = 1.0 + bestK * (r / maxR) * (r / maxR)
                if (factor <= 0.0) {
                    outPixels[y * W + x] = 0xFF000000.toInt()
                    continue
                }
                val sx = cx + dx / factor
                val sy = cy + dy / factor
                if (sx < 0 || sx >= W || sy < 0 || sy >= H) {
                    outPixels[y * W + x] = 0xFF000000.toInt()
                    continue
                }
                val ix = sx.toInt().coerceIn(0, W - 1)
                val iy = sy.toInt().coerceIn(0, H - 1)
                outPixels[y * W + x] = pixels[iy * W + ix]
            }
        }
        out.setPixels(outPixels, 0, W, 0, 0, W, H)
        return if (scale < 1f) {
            // 返回原尺寸去畸变图
            val full = Bitmap.createScaledBitmap(out, srcW, srcH, true)
            out.recycle()
            full
        } else out
    }

    /** 所有线段在校正 k 下的弯曲度之和（中间点到两端连线的距离平方和） */
    private fun totalCurvature(segments: List<ArrayList<PointD>>, cx: Double, cy: Double, k: Double): Double {
        var total = 0.0
        val maxR = hypot(cx, cy)
        for (seg in segments) {
            if (seg.size < 3) continue
            val corrected = ArrayList<PointD>()
            for (p in seg) {
                val dx = p.x - cx
                val dy = p.y - cy
                val r = hypot(dx.toDouble(), dy.toDouble())
                val factor = 1.0 + k * (r / maxR) * (r / maxR)
                corrected.add(PointD(cx + dx / factor, cy + dy / factor))
            }
            val a = corrected.first()
            val b = corrected.last()
            // 中间点集到两端连线的距离平方和
            val vx = b.x - a.x
            val vy = b.y - a.y
            val len2 = vx * vx + vy * vy
            if (len2 < 1e-9) continue
            for (i in 1 until corrected.size - 1) {
                val p = corrected[i]
                val t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / len2
                val projX = a.x + t * vx
                val projY = a.y + t * vy
                val dx = p.x - projX
                val dy = p.y - projY
                total += dx * dx + dy * dy
            }
        }
        return total
    }

    private class PointD(val x: Double, val y: Double)
}
