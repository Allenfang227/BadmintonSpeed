package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 背景差分羽毛球检测器 v2（修复光源误检）：
 * 远景斜拍视频里羽毛球很小很糊，YOLO 模型经常漏检，本检测器作为第二通道，
 * 专门抓"白色 + 在动"的小目标。
 *
 * v2 修复（用户实测反馈）：
 *   1. 顶上的灯光/地板反光是"静止白点"，会被误认成球 → 静止点排除：
 *      候选与上一帧距离 < stillPx（几乎不动）即判为静止，连续静止 ≥ stillFrames 帧后
 *      该 cell 进入黑名单（视为背景的一部分），之后永远不再产生候选
 *   2. 多帧确认改为"移动距离区间"：距离在 [minMovePx, maxFrameJump] 之间才算球，
 *      太近是静止点，太远是瞬移噪点。羽毛球每帧都在动，且相邻帧移动幅度不会太大
 *   3. 白色阈值下调到 140：远景中小球较暗，170 太高抓不到，靠静止排除+面积过滤压制噪声
 */
class BackgroundShuttleDetector(
    private val bgFrames: Int = 8,
    private val diffThreshold: Int = 26,
    private val whiteThreshold: Int = 140,
    private val cellSize: Int = 24,
    private val minCellHits: Int = 3,
    private val maxBlobCells: Int = 8,
    private val minMovePx: Float = 8f,       // 移动距离下限：低于此判定为静止点
    private val maxFrameJump: Float = 70f,   // 帧间最大移动：高于此判定为瞬移噪点
    private val stillFrames: Int = 3         // 连续静止帧数达到此值 → 加入黑名单
) {
    private var bgGray: FloatArray? = null   // 背景灰度均值
    private var frameCount = 0
    private var width = 0
    private var height = 0
    private var prevCandidates: List<PointF> = emptyList()
    // 静止白点黑名单：key = cell 下标，命中过的 cell 不再产生候选
    private val staticCells = HashSet<Int>()
    // cell 连续静止计数
    private val stillCounts = HashMap<Int, Int>()
    // v2.42 复用缓冲：避免每帧新建 IntArray(w*h) 引发 GC 卡死（用户反馈帧间差分处卡死）
    private var grayCache: IntArray? = null
    private var pixelsCache: IntArray? = null

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
                bg[i] += 0.05f * (gray[i] - bg[i])
            }
        }
    }

    /** 检测当前帧的移动白色候选（静止点排除 + 移动距离区间多帧确认） */
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

        // 1) 统计每个 cell 的白色运动像素数（跳过黑名单 cell）
        var y = 0
        while (y < height) {
            var x = 0
            val rowBase = y * width
            while (x < width) {
                val cellIdx = (y / cellSize) * cols + (x / cellSize)
                if (!staticCells.contains(cellIdx)) {
                    val g = gray[rowBase + x]
                    if (g > whiteThreshold && abs(g - bg[rowBase + x]) > diffThreshold) {
                        cellHits[cellIdx]++
                    }
                }
                x++
            }
            y++
        }

        // 2) 命中 cell 聚类成 blob
        val visited = BooleanArray(cols * rows)
        val blobs = ArrayList<Blob>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                if (cellHits[idx] >= minCellHits && !visited[idx] && !staticCells.contains(idx)) {
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
                                    if (cellHits[nIdx] >= minCellHits && !visited[nIdx] && !staticCells.contains(nIdx)) {
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

        // 3) blob → 候选
        val candidates = ArrayList<ShuttleOnnxDetector.Box>()
        for (blob in blobs) {
            val cx = (blob.sumX / blob.cells.size + 0.5f) * cellSize
            val cy = (blob.sumY / blob.cells.size + 0.5f) * cellSize
            candidates.add(ShuttleOnnxDetector.Box(cx, cy, cellSize * 3f, cellSize * 3f, 0.55f))
        }

        // 4) 与上一帧匹配：移动距离区间过滤 + 静止点排除
        val confirmed = ArrayList<ShuttleOnnxDetector.Box>()
        val movedCandidates = ArrayList<PointF>()
        if (prevCandidates.isNotEmpty()) {
            for (cand in candidates) {
                var bestMatch: PointF? = null
                var bestDist = Float.MAX_VALUE
                for (prev in prevCandidates) {
                    val d = sqrt((cand.cx - prev.x) * (cand.cx - prev.x) + (cand.cy - prev.y) * (cand.cy - prev.y))
                    if (d < bestDist) { bestDist = d; bestMatch = prev }
                }
                if (bestMatch == null) continue
                if (bestDist in minMovePx..maxFrameJump) {
                    // 移动距离合理 → 确认是球
                    confirmed.add(cand)
                    movedCandidates.add(PointF(cand.cx, cand.cy))
                } else if (bestDist < minMovePx) {
                    // 几乎不动 → 静止点：累计静止帧数，达到阈值加入黑名单
                    val cellIdx = (cand.cy.toInt() / cellSize) * cols + (cand.cx.toInt() / cellSize)
                    val cnt = (stillCounts[cellIdx] ?: 0) + 1
                    stillCounts[cellIdx] = cnt
                    if (cnt >= stillFrames) {
                        staticCells.add(cellIdx)
                        stillCounts.remove(cellIdx)
                    }
                }
            }
        }
        // 只保留移动候选作为下一帧的匹配基准
        prevCandidates = movedCandidates
        return confirmed
    }

    fun reset() {
        bgGray = null
        frameCount = 0
        prevCandidates = emptyList()
        staticCells.clear()
        stillCounts.clear()
    }

    private class Blob {
        val cells = ArrayList<Int>()
        var sumX = 0
        var sumY = 0
    }

    private fun toGray(frame: Bitmap, w: Int, h: Int): IntArray {
        var pixels = pixelsCache
        if (pixels == null || pixels.size < w * h) {
            pixels = IntArray(w * h)
            pixelsCache = pixels
        }
        var gray = grayCache
        if (gray == null || gray.size < w * h) {
            gray = IntArray(w * h)
            grayCache = gray
        }
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }
}
