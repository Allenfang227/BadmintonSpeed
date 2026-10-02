package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 背景差分羽毛球检测器（用户要求："把背景不动的固定下来，识别移动的白色点，多帧差分确保羽毛球"）：
 * 远景斜拍视频里羽毛球很小很糊，YOLO 模型经常漏检。
 * 本检测器作为补充方案，专门抓"白色 + 在动"的小目标：
 *
 *   1. 用前 N 帧建背景灰度模型（逐像素累积均值，相机固定时很稳定）
 *   2. 后续帧滚动更新背景：只有非运动区域才更新，球不会污染背景
 *   3. 差分：|当前灰度 - 背景| > diffThreshold 且 亮度 > whiteThreshold → 白色运动点
 *   4. 下采样成 cell 网格，统计每个 cell 的白色运动点数（防噪点）
 *   5. 相邻 cell 聚类成 blob，面积过大（人体/球网）过小（噪点）的过滤掉
 *   6. 多帧确认：候选必须与上一帧候选距离在 maxFrameJump 内才返回（连续性保证）
 */
class BackgroundShuttleDetector(
    private val bgFrames: Int = 8,
    private val diffThreshold: Int = 28,
    private val whiteThreshold: Int = 170,
    private val cellSize: Int = 24,
    private val minCellHits: Int = 3,
    private val maxBlobCells: Int = 8,
    private val maxFrameJump: Float = 70f
) {
    private var bgGray: FloatArray? = null   // 背景灰度均值
    private var frameCount = 0
    private var width = 0
    private var height = 0
    private var prevCandidates: List<PointF> = emptyList()

    /** 用一帧学习背景（前 bgFrames 帧调用，累积均值） */
    fun learn(frame: Bitmap) {
        val w = frame.width; val h = frame.height
        if (bgGray == null || bgGray!!.size != w * h) {
            bgGray = FloatArray(w * h)
            width = w; height = h
        }
        val gray = toGray(frame, w, h)
        val bg = bgGray!!
        frameCount++
        for (i in gray.indices) {
            bg[i] += (gray[i] - bg[i]) / frameCount.toFloat()
        }
    }

    /** 滚动更新背景：只更新与背景接近的像素（非运动区域），运动区域（球）不污染背景 */
    fun updateBackground(frame: Bitmap) {
        val bg = bgGray ?: return
        if (frame.width != width || frame.height != height) return
        val gray = toGray(frame, width, height)
        for (i in gray.indices) {
            if (abs(gray[i] - bg[i]) < diffThreshold) {
                bg[i] += 0.05f * (gray[i] - bg[i]) // 慢速自适应
            }
        }
    }

    /** 检测当前帧的白色运动候选（多帧确认后返回） */
    fun detectMovingWhite(frame: Bitmap): List<ShuttleOnnxDetector.Box> {
        if (bgGray == null || frame.width != width || frame.height != height) {
            learn(frame)
            return emptyList()
        }
        val gray = toGray(frame, width, height)
        val bg = bgGray!!
        val cols = (width + cellSize - 1) / cellSize
        val rows = (height + cellSize - 1) / cellSize
        val cellHits = IntArray(cols * rows)

        // 1) 统计每个 cell 的白色运动像素数
        var y = 0
        while (y < height) {
            var x = 0
            val rowBase = y * width
            while (x < width) {
                val g = gray[rowBase + x]
                if (g > whiteThreshold && abs(g - bg[rowBase + x]) > diffThreshold) {
                    cellHits[(y / cellSize) * cols + (x / cellSize)]++
                }
                x++
            }
            y++
        }

        // 2) 标记命中 cell，聚类成 blob
        val visited = BooleanArray(cols * rows)
        val blobs = ArrayList<Blob>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                if (cellHits[idx] >= minCellHits && !visited[idx]) {
                    val blob = Blob()
                    val stack = ArrayDeque<Int>()
                    stack.addLast(idx)
                    visited[idx] = true
                    while (stack.isNotEmpty()) {
                        val cur = stack.removeLast()
                        blob.cells.add(cur)
                        blob.sumX += cur % cols
                        blob.sumY += cur / cols
                        val cr = cur / cols; val cc = cur % cols
                        for (dc in -1..1) {
                            for (dr in -1..1) {
                                if (dr == 0 && dc == 0) continue
                                val nr = cr + dr; val nc = cc + dc
                                if (nr in 0 until rows && nc in 0 until cols) {
                                    val nIdx = nr * cols + nc
                                    if (cellHits[nIdx] >= minCellHits && !visited[nIdx]) {
                                        visited[nIdx] = true
                                        stack.addLast(nIdx)
                                    }
                                }
                            }
                        }
                    }
                    if (blob.cells.size in 1..maxBlobCells) blobs.add(blob)
                }
            }
        }

        // 3) 面积过滤：blob 太小是噪点（已在 cellHits 阈值处理），太大是人体/球网/灯光
        val candidates = ArrayList<ShuttleOnnxDetector.Box>()
        for (blob in blobs) {
            val cx = (blob.sumX / blob.cells.size + 0.5f) * cellSize
            val cy = (blob.sumY / blob.cells.size + 0.5f) * cellSize
            val boxW = cellSize * 3f
            val boxH = cellSize * 3f
            candidates.add(ShuttleOnnxDetector.Box(cx, cy, boxW, boxH, 0.55f))
        }

        // 4) 多帧确认：候选必须与上一帧候选距离 ≤ maxFrameJump 才返回（连续两帧同一位置有白色运动点 = 球）
        val confirmed = ArrayList<ShuttleOnnxDetector.Box>()
        if (prevCandidates.isNotEmpty()) {
            for (cand in candidates) {
                for (prev in prevCandidates) {
                    val d = sqrt((cand.cx - prev.x) * (cand.cx - prev.x) + (cand.cy - prev.y) * (cand.cy - prev.y))
                    if (d <= maxFrameJump) {
                        confirmed.add(cand)
                        break
                    }
                }
            }
        }
        prevCandidates = candidates.map { PointF(it.cx, it.cy) }
        return confirmed
    }

    fun reset() {
        bgGray = null
        frameCount = 0
        prevCandidates = emptyList()
    }

    private class Blob {
        val cells = ArrayList<Int>()
        var sumX = 0
        var sumY = 0
    }

    private fun toGray(frame: Bitmap, w: Int, h: Int): IntArray {
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
