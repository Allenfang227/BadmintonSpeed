package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 真实 AI 羽毛球检测器（YOLO11n-Shuttle ONNX）：
 * 模型：yolo11n_shuttle.pt -> shuttle.onnx（opset17，输入 [1,3,640,640]，输出 [1,5,8400]）
 * 单类别 "Shuttlecock"，输出格式 cx,cy,w,h,conf（coco 格式）。
 * 检测流程：letterbox 缩放 -> 归一化 -> 推理 -> 阈值过滤 -> NMS -> 映射回原图像素坐标。
 * 结果以 BallDetector.Blob 结构输出，兼容 BallTracker 跟踪器。
 */
class ShuttleOnnxDetector(
    context: Context,
    private val confThreshold: Float = 0.15f,
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
        return env.createSession(cacheFile.absolutePath, OrtSession.SessionOptions())
    }

    /**
     * 在帧上检测羽毛球。
     * @param hint 上一帧球心提示（可选）：全帧未检出时，在提示位置周围放大区域重检，显著提升小目标召回。
     * @return 映射回原始帧像素坐标的检测框列表
     */
    fun detect(frame: Bitmap, hint: Pair<Float, Float>? = null): List<Box> {
        val boxes = detectFull(frame)
        if (boxes.isNotEmpty() || hint == null) return boxes

        // ---- 局部放大重检：上一球位置附近裁剪并放大到 640 ----
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
        val local = runInference(scaled)
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

    /** 全帧 letterbox 检测 */
    private fun detectFull(frame: Bitmap): List<Box> {
        val srcW = frame.width
        val srcH = frame.height

        // ---- letterbox 到 640x640 ----
        val scale = minOf(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val resizedW = (srcW * scale).toInt().coerceAtLeast(1)
        val resizedH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - resizedW) / 2f
        val padY = (INPUT_SIZE - resizedH) / 2f

        val resized = Bitmap.createScaledBitmap(frame, resizedW, resizedH, true)
        val pixels = IntArray(resizedW * resizedH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)

        // CHW float [1,3,640,640]，归一化到 [0,1]，letterbox 填充用与训练一致的 114/255 灰
        val input = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val padValue = 114f / 255f
        java.util.Arrays.fill(input, padValue)
        val n = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val row = i / resizedW
            val col = i % resizedW
            val y = row + padY.toInt()
            val x = col + padX.toInt()
            if (y in 0 until INPUT_SIZE && x in 0 until INPUT_SIZE) {
                val idx = y * INPUT_SIZE + x
                input[idx] = r
                input[n + idx] = g
                input[2 * n + idx] = b
            }
        }
        resized.recycle()

        val boxes = runInferenceOnInput(input)

        // ---- 映射回原图坐标 ----
        return boxes.map { b ->
            val x0 = b.cx - b.w / 2f
            val y0 = b.cy - b.h / 2f
            val x1 = b.cx + b.w / 2f
            val y1 = b.cy + b.h / 2f
            // 去掉 letterbox 填充
            val ox0 = ((x0 - padX) / scale).coerceIn(0f, srcW.toFloat())
            val oy0 = ((y0 - padY) / scale).coerceIn(0f, srcH.toFloat())
            val ox1 = ((x1 - padX) / scale).coerceIn(0f, srcW.toFloat())
            val oy1 = ((y1 - padY) / scale).coerceIn(0f, srcH.toFloat())
            val cw = max(1f, ox1 - ox0)
            val ch = max(1f, oy1 - oy0)
            Box(ox0 + cw / 2f, oy0 + ch / 2f, cw, ch, b.conf)
        }
    }

    /** 对 640x640 的位图直接推理（局部重检用），返回 640 尺度坐标 */
    private fun runInference(bitmap: Bitmap): List<Box> {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val input = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val n = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = (p shr 16 and 0xFF) / 255f
            input[n + i] = (p shr 8 and 0xFF) / 255f
            input[2 * n + i] = (p and 0xFF) / 255f
        }
        return runInferenceOnInput(input)
    }

    /** 输入 [1,3,640,640] CHW float，执行 ONNX 推理并解析输出 */
    private fun runInferenceOnInput(input: FloatArray): List<Box> {
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputBuf = java.nio.FloatBuffer.wrap(input)
        return OnnxTensor.createTensor(env, inputBuf, shape).use { tensor ->
            session.run(Collections.singletonMap("images", tensor)).use { output ->
                val result = output[0].value as Array<*>
                // [1,5,8400] -> 取第 0 个 batch
                val batch: Array<FloatArray> = result[0] as Array<FloatArray>
                val cols = batch[0].size
                // YOLO11 coco 格式: [cx, cy, w, h, conf] 每列一个候选
                // 防御：部分导出模型输出 0-1 归一化坐标，自动判别后放大到 640 尺度
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
                        val k = if (normalized) INPUT_SIZE.toFloat() else 1f
                        dets.add(Box(cxRaw * k, cyRaw * k, wRaw * k, hRaw * k, conf))
                    }
                }
                nms(dets, iouThreshold)
            }
        }
    }

    /** 标准 NMS（类别内，IoU 抑制） */
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

    /**
     * 将检测框转换为 BallTracker 兼容的 Blob 结构
     * （meanBrightness 以置信度折算，保证 AI 高置信目标优先）。
     */
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
