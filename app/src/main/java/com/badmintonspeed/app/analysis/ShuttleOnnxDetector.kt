package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/**
 * 真实 AI 羽毛球检测器（YOLO11n-Shuttle ONNX）。
 *
 * v2.7 优化（针对麒麟9000S 八核 + 小目标召回）：
 *   - ONNX Runtime：intraOp=4 线程 + interOp=2 + ALL_OPT 图优化 + NNAPI 加速
 *   - 置信度阈值 0.15 → 0.05（提升小目标/远球召回）
 *   - 三阶段检测：全帧640 → 提示局部放大 → 全帧1280（高分辨率兜底）
 *   - 尺寸合理性过滤：羽毛球框面积不超过画面 30%，避免把场地线/白墙当球
 *
 * 模型：yolo11n_shuttle.pt -> shuttle.onnx（输入 [1,3,640,640]，输出 [1,5,8400]）
 * 单类别 "Shuttlecock"，输出格式 cx,cy,w,h,conf。
 */
class ShuttleOnnxDetector(
    context: Context,
    private val confThreshold: Float = 0.05f,
    private val iouThreshold: Float = 0.45f
) {

    companion object {
        const val INPUT_SIZE = 640
        private const val MODEL_ASSET = "models/shuttle.onnx"
    }

    data class Box(
        val cx: Float, val cy: Float, val w: Float, val h: Float, val conf: Float
    )

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = loadSession(context)

    private fun loadSession(context: Context): OrtSession {
        val cacheFile = File(context.filesDir, "shuttle.onnx")
        if (!cacheFile.exists()) {
            cacheFile.outputStream().use { out ->
                context.assets.open(MODEL_ASSET).use { ins -> ins.copyTo(out) }
            }
        }
        val opts = OrtSession.SessionOptions().apply {
            // 麒麟9000S：1大核+3中核+4小核，分配4线程给算子内部并行
            setIntraOpNumThreads(4)
            // 算子间并行2线程
            setInterOpNumThreads(2)
            // 全图优化（常量折叠、算子融合）
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // 尝试启用 NNAPI（华为NPU），失败自动回退CPU
            runCatching { addNnapi() }
        }
        return env.createSession(cacheFile.absolutePath, opts)
    }

    /**
     * 三阶段检测：
     *   1. 全帧 640 检测（快）
     *   2. 若空且有 hint，在 hint 周围裁剪放大重检（中等开销）
     *   3. 若仍空，全帧 1280 高分辨率检测（慢但召回高，作为兜底）
     */
    fun detect(frame: Bitmap, hint: Pair<Float, Float>? = null): List<Box> {
        // 阶段1：全帧 640
        val boxes = detectFull(frame, INPUT_SIZE)
        if (boxes.isNotEmpty()) return filterBySize(boxes, frame.width, frame.height)

        // 阶段2：局部放大
        if (hint != null) {
            val local = detectLocal(frame, hint)
            if (local.isNotEmpty()) return filterBySize(local, frame.width, frame.height)
        }

        // 阶段3：全帧 1280 高分辨率兜底（对远景小球更友好）
        val hiRes = detectFull(frame, 1280)
        return filterBySize(hiRes, frame.width, frame.height)
    }

    /** 过滤掉尺寸不合理的框（羽毛球不可能占画面 30% 以上） */
    private fun filterBySize(boxes: List<Box>, frameW: Int, frameH: Int): List<Box> {
        val maxArea = frameW * frameH * 0.3f
        return boxes.filter { b ->
            val area = b.w * b.h
            area > 4f && area < maxArea && b.w > 2f && b.h > 2f
        }
    }

    /** 全帧 letterbox 检测（可指定输入尺寸，640 或 1280） */
    private fun detectFull(frame: Bitmap, inputSize: Int): List<Box> {
        val srcW = frame.width
        val srcH = frame.height

        val scale = minOf(inputSize.toFloat() / srcW, inputSize.toFloat() / srcH)
        val resizedW = (srcW * scale).toInt().coerceAtLeast(1)
        val resizedH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (inputSize - resizedW) / 2f
        val padY = (inputSize - resizedH) / 2f

        val resized = Bitmap.createScaledBitmap(frame, resizedW, resizedH, true)
        val pixels = IntArray(resizedW * resizedH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)

        val input = FloatArray(3 * inputSize * inputSize)
        val padValue = 114f / 255f
        java.util.Arrays.fill(input, padValue)
        val n = inputSize * inputSize
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val row = i / resizedW
            val col = i % resizedW
            val y = row + padY.toInt()
            val x = col + padX.toInt()
            if (y in 0 until inputSize && x in 0 until inputSize) {
                val idx = y * inputSize + x
                input[idx] = r
                input[n + idx] = g
                input[2 * n + idx] = b
            }
        }
        resized.recycle()

        val boxes = runInferenceOnInput(input, inputSize)

        return boxes.map { b ->
            val x0 = b.cx - b.w / 2f
            val y0 = b.cy - b.h / 2f
            val x1 = b.cx + b.w / 2f
            val y1 = b.cy + b.h / 2f
            val ox0 = ((x0 - padX) / scale).coerceIn(0f, srcW.toFloat())
            val oy0 = ((y0 - padY) / scale).coerceIn(0f, srcH.toFloat())
            val ox1 = ((x1 - padX) / scale).coerceIn(0f, srcW.toFloat())
            val oy1 = ((y1 - padY) / scale).coerceIn(0f, srcH.toFloat())
            val cw = max(1f, ox1 - ox0)
            val ch = max(1f, oy1 - oy0)
            Box(ox0 + cw / 2f, oy0 + ch / 2f, cw, ch, b.conf)
        }
    }

    /** 局部放大检测：在 hint 周围裁剪并放大到 inputSize */
    private fun detectLocal(frame: Bitmap, hint: Pair<Float, Float>): List<Box> {
        val w = frame.width
        val h = frame.height
        val cropSize = minOf(w, h) * 0.45f
        if (cropSize < 40f) return emptyList()
        val half = cropSize / 2f
        val cx = hint.first.coerceIn(half, w - half)
        val cy = hint.second.coerceIn(half, h - half)
        val left = (cx - half).toInt()
        val top = (cy - half).toInt()
        val crop = try {
            Bitmap.createBitmap(frame, left, top, cropSize.toInt(), cropSize.toInt())
        } catch (e: Exception) {
            return emptyList()
        }
        val scaled = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        crop.recycle()
        val local = runInference(scaled, INPUT_SIZE)
        scaled.recycle()
        if (local.isEmpty()) return emptyList()

        val k = cropSize.toFloat() / INPUT_SIZE.toFloat()
        return local.map { b ->
            val x0 = (b.cx - b.w / 2f) * k + left
            val y0 = (b.cy - b.h / 2f) * k + top
            val x1 = (b.cx + b.w / 2f) * k + left
            val y1 = (b.cy + b.h / 2f) * k + top
            Box((x0 + x1) / 2f, (y0 + y1) / 2f, max(1f, x1 - x0), max(1f, y1 - y0), b.conf)
        }
    }

    /** 对位图直接推理（局部重检用） */
    private fun runInference(bitmap: Bitmap, inputSize: Int): List<Box> {
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val input = FloatArray(3 * inputSize * inputSize)
        val n = inputSize * inputSize
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = (p shr 16 and 0xFF) / 255f
            input[n + i] = (p shr 8 and 0xFF) / 255f
            input[2 * n + i] = (p and 0xFF) / 255f
        }
        return runInferenceOnInput(input, inputSize)
    }

    /** 输入 [1,3,N,N] CHW float，执行 ONNX 推理并解析输出 */
    private fun runInferenceOnInput(input: FloatArray, inputSize: Int): List<Box> {
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val inputBuf = java.nio.FloatBuffer.wrap(input)
        return OnnxTensor.createTensor(env, inputBuf, shape).use { tensor ->
            session.run(Collections.singletonMap("images", tensor)).use { output ->
                val result = output[0].value as Array<*>
                val batch: Array<FloatArray> = result[0] as Array<FloatArray>
                val cols = batch[0].size
                val dets = ArrayList<Box>()
                for (c in 0 until cols) {
                    val cxRaw = batch[0][c]
                    val cyRaw = batch[1][c]
                    val wRaw = batch[2][c]
                    val hRaw = batch[3][c]
                    val conf = batch[4][c]
                    if (wRaw > 0f && hRaw > 0f && conf >= confThreshold) {
                        val normalized =
                            cxRaw in 0f..1f && cyRaw in 0f..1f && wRaw in 0f..1f && hRaw in 0f..1f
                        val k = if (normalized) inputSize.toFloat() else 1f
                        dets.add(Box(cxRaw * k, cyRaw * k, wRaw * k, hRaw * k, conf))
                    }
                }
                nms(dets, iouThreshold)
            }
        }
    }

    /** 标准 NMS */
    private fun nms(boxes: List<Box>, iouThr: Float): List<Box> {
        val sorted = boxes.sortedByDescending { it.conf }
        val picked = ArrayList<Box>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            picked.add(a)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val b = sorted[j]
                if (iou(a, b) > iouThr) suppressed[j] = true
            }
        }
        return picked
    }

    private fun iou(a: Box, b: Box): Float {
        val ax0 = a.cx - a.w / 2f; val ay0 = a.cy - a.h / 2f
        val ax1 = a.cx + a.w / 2f; val ay1 = a.cy + a.h / 2f
        val bx0 = b.cx - b.w / 2f; val by0 = b.cy - b.h / 2f
        val bx1 = b.cx + b.w / 2f; val by1 = b.cy + b.h / 2f
        val ix = max(0f, minOf(ax1, bx1) - maxOf(ax0, bx0))
        val iy = max(0f, minOf(ay1, by1) - maxOf(ay0, by0))
        val inter = ix * iy
        val ua = a.w * a.h + b.w * b.h - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    /** 转换为 BallTracker 兼容的 Blob 结构 */
    fun toBlobs(boxes: List<Box>): List<BallDetector.Blob> = boxes.map { b ->
        BallDetector.Blob(
            cx = b.cx,
            cy = b.cy,
            area = (b.w * b.h).toInt(),
            meanBrightness = 150f + 100f * b.conf,
            movingRatio = 1f,
            minX = (b.cx - b.w / 2f).toInt(),
            maxX = (b.cx + b.w / 2f).toInt(),
            minY = (b.cy - b.h / 2f).toInt(),
            maxY = (b.cy + b.h / 2f).toInt()
        )
    }

    fun close() {
        runCatching { session.close() }
    }
}
