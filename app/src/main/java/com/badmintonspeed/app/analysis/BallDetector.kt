package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import java.util.HashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 羽毛球检测器（MVP 简化方案，纯 Kotlin 实现）：
 * 羽毛球场上的羽毛球以白色为主（球头+羽毛），对光线变化相对鲁棒。
 * 算法：亮度阈值二值化 -> 四连通域标记 -> 尺寸/形状过滤 -> 运动幅度加分排序。
 * 后续由 BallTracker 做最近邻跟踪，进一步过滤静态白色物体。
 */
class BallDetector(
    private val brightThreshold: Int = 195
) {

    data class Blob(
        val cx: Float,
        val cy: Float,
        val area: Int,
        val meanBrightness: Float,
        val movingRatio: Float,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    data class DetectionResult(
        val blobs: List<Blob>,
        val gray: IntArray
    )

    private val minArea = 8
    private val maxArea = 4000

    fun detect(frame: Bitmap, prevGray: IntArray?): DetectionResult {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        // ---- 四连通域标记（union-find 两遍法） ----
        val labels = IntArray(w * h) { -1 }
        val uf = IntArray(w * h + 1) { it }
        var next = 0

        fun root(a: Int): Int {
            var x = a
            while (uf[x] != x) {
                uf[x] = uf[uf[x]]
                x = uf[x]
            }
            return x
        }

        val thr = brightThreshold
        for (y in 0 until h) {
            val rowBase = y * w
            val upRow = rowBase - w
            for (x in 0 until w) {
                val idx = rowBase + x
                if (gray[idx] < thr) continue
                val left = if (x > 0 && gray[idx - 1] >= thr) labels[idx - 1] else -1
                val up = if (y > 0 && gray[upRow + x] >= thr) labels[upRow + x] else -1
                when {
                    left < 0 && up < 0 -> { next++; labels[idx] = next }
                    left >= 0 && up < 0 -> labels[idx] = root(left)
                    left < 0 && up >= 0 -> labels[idx] = root(up)
                    left >= 0 && up >= 0 -> {
                        val r1 = root(left); val r2 = root(up)
                        if (r1 != r2) uf[r2] = r1
                        labels[idx] = r1
                    }
                }
            }
        }

        // ---- 聚合每个连通域统计 ----
        data class Acc(var area: Int, var sumX: Long, var sumY: Long, var sumGray: Long,
                       var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var moving: Int)
        val acc = HashMap<Int, Acc>()
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                val idx = rowBase + x
                val lab = labels[idx]
                if (lab < 0) continue
                val r = root(lab)
                val a = acc.getOrPut(r) {
                    Acc(0, 0L, 0L, 0L, w, h, -1, -1, 0)
                }
                a.area++
                a.sumX += x
                a.sumY += y
                a.sumGray += gray[idx]
                a.minX = min(a.minX, x); a.minY = min(a.minY, y)
                a.maxX = max(a.maxX, x); a.maxY = max(a.maxY, y)
                if (prevGray != null && abs(gray[idx] - prevGray[idx]) > 20) a.moving++
            }
        }

        // ---- 过滤与打分 ----
        val blobs = ArrayList<Blob>()
        for ((_, a) in acc) {
            if (a.area < minArea || a.area > maxArea) continue
            val bw = a.maxX - a.minX + 1
            val bh = a.maxY - a.minY + 1
            if (bw < 2 || bh < 2) continue
            val aspect = bw.toFloat() / bh
            if (aspect < 0.2f || aspect > 5f) continue
            val meanBright = a.sumGray.toFloat() / a.area
            val movingRatio = if (prevGray != null) a.moving.toFloat() / a.area else 1f
            blobs.add(
                Blob(
                    cx = a.sumX.toFloat() / a.area,
                    cy = a.sumY.toFloat() / a.area,
                    area = a.area,
                    meanBrightness = meanBright,
                    movingRatio = movingRatio,
                    minX = a.minX, maxX = a.maxX, minY = a.minY, maxY = a.maxY
                )
            )
        }

        // 得分：亮度均值 + 运动幅度；大而亮的静止物（场地线、白墙）被降权
        blobs.sortByDescending {
            (it.meanBrightness / 255f) * 0.6f + min(1f, it.movingRatio * 3f) * 0.4f
        }
        return DetectionResult(blobs.take(10), gray)
    }
}
