package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.Collections

/**
 * v2.28 开源真AI场地分割检测器（YOLO11n-seg ONNX）。
 * 模型：100-heon/badminton_double_analysis best_court.pt（Roboflow badminton_court 数据集）
 *      -> best_court.onnx（opset12，输入 [1,3,640,640]，
 *         输出 output0=[1,37,8400]（4bbox+1cls+32mask系数，单类）、output1=[1,32,160,160] 原型掩码）
 * 用途：作为"真 AI 场地识别"通道，输出整幅场地掩码（不依赖颜色），
 *      替代/回退 v2.27 的颜色域掩码做白线提取的空间约束。
 * 场地静态，只对校准帧（首帧/失败重试帧）跑 1 次，CPU 约 200~400ms 可接受。
 */
class CourtSegDetector(context: Context) {

    companion object {
        const val INPUT_SIZE = 640
        private const val MODEL_ASSET = "models/court_seg.onnx"
        private const val CONF_MIN = 0.18f
        private const val MASK_THR = 0.40f
        /** 掩码有效覆盖比下限（≥15% 才认为 AI 真识别到了场地） */
        const val MIN_COVERAGE = 0.15f
    }

    /** 分割结果：原图尺寸布尔掩码 + 置信度 + 覆盖比 */
    data class SegMask(
        val mask: BooleanArray,
        val w: Int,
        val h: Int,
        val conf: Float,
        val coverage: Float
    )

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = loadSession(context)

    private fun loadSession(context: Context): OrtSession {
        val cacheFile = File(context.filesDir, "court_seg.onnx")
        if (!cacheFile.exists()) {
            cacheFile.outputStream().use { out ->
                context.assets.open(MODEL_ASSET).use { ins -> ins.copyTo(out) }
            }
        }
        return env.createSession(cacheFile.absolutePath, OrtSession.SessionOptions())
    }

    /** 对帧做场地分割，返回原图尺寸掩码；无检出/低置信返回 null */
    fun segment(frame: Bitmap): SegMask? {
        val srcW = frame.width
        val srcH = frame.height
        val scale = minOf(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val resizedW = (srcW * scale).toInt().coerceAtLeast(1)
        val resizedH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - resizedW) / 2f
        val padY = (INPUT_SIZE - resizedH) / 2f

        val resized = Bitmap.createScaledBitmap(frame, resizedW, resizedH, true)
        val pixels = IntArray(resizedW * resizedH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)

        val input = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val padValue = 114f / 255f
        java.util.Arrays.fill(input, padValue)
        val n = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = (p shr 16 and 0xFF) / 255f
            input[n + i] = (p shr 8 and 0xFF) / 255f
            input[2 * n + i] = (p and 0xFF) / 255f
            val row = i / resizedW
            val col = i % resizedW
            val y = row + padY.toInt()
            val x = col + padX.toInt()
            if (y in 0 until INPUT_SIZE && x in 0 until INPUT_SIZE) {
                val idx = y * INPUT_SIZE + x
                // 覆盖填值（上面已整体赋值 RGB，此处仅重定位）
                input[idx] = (p shr 16 and 0xFF) / 255f
                input[n + idx] = (p shr 8 and 0xFF) / 255f
                input[2 * n + idx] = (p and 0xFF) / 255f
            }
        }
        resized.recycle()

        val mask = try { runInference(input, srcW, srcH, padX, padY, scale) } catch (e: Exception) { null }
        return mask
    }

    /** 执行 ONNX 推理并组装原图掩码 */
    private fun runInference(
        input: FloatArray, srcW: Int, srcH: Int, padX: Float, padY: Float, scale: Float
    ): SegMask? {
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputBuf = java.nio.FloatBuffer.wrap(input)
        return OnnxTensor.createTensor(env, inputBuf, shape).use { tensor ->
            session.run(Collections.singletonMap("images", tensor)).use { output ->
                // output0 [1,37,8400] -> rows=37, cols=8400
                val det = output[0].value as Array<*>
                val rows: Array<FloatArray> = det[0] as Array<FloatArray>
                val cols = rows[0].size
                // 单类：conf = row[4]
                var best = -1
                var bestConf = CONF_MIN
                for (c in 0 until cols) {
                    val conf = rows[4][c]
                    if (conf > bestConf) { bestConf = conf; best = c }
                }
                if (best < 0) return null

                // mask 系数 32 维
                val coeff = FloatArray(32)
                for (k in 0 until 32) coeff[k] = rows[5 + k][best]
                // output1 [1,32,160,160] -> 递归扁平化（ORT Java 多层数组结构不固定，扁平后按布局读）
                val proto = output[1].value as Array<*>
                val protoFlat = ArrayList<Float>(32 * 160 * 160)
                fun flatten(a: Any?) {
                    when (a) {
                        is FloatArray -> a.forEach { protoFlat.add(it) }
                        is Float -> protoFlat.add(a)
                        is Array<*> -> a.forEach { flatten(it) }
                    }
                }
                flatten(proto[0])
                if (protoFlat.size < 32 * 160 * 160) return null
                val m160 = 160
                // 160x160 sigmoid 掩码 -> 640 尺度最近邻放大
                val maskPix = IntArray(m160 * m160)
                for (y in 0 until m160) {
                    var by = y * m160
                    for (x in 0 until m160) {
                        var s = 0f
                        for (k in 0 until 32) {
                            s += protoFlat[k * m160 * m160 + y * m160 + x] * coeff[k]
                        }
                        val v = 1f / (1f + Math.exp(-s.toDouble()).toFloat())
                        maskPix[by + x] = if (v > MASK_THR) 0xFFFFFFFF.toInt() else 0
                    }
                }
                val maskBmp = Bitmap.createBitmap(maskPix, m160, m160, Bitmap.Config.ARGB_8888)
                val full = Bitmap.createScaledBitmap(maskBmp, INPUT_SIZE, INPUT_SIZE, true)
                maskBmp.recycle()

                // 640 全图掩码（含 letterbox 填充区置 0）
                val fullPix = IntArray(INPUT_SIZE * INPUT_SIZE)
                full.getPixels(fullPix, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
                full.recycle()
                val x0 = padX.toInt().coerceIn(0, INPUT_SIZE)
                val y0 = padY.toInt().coerceIn(0, INPUT_SIZE)
                val x1 = (padX + resizedWOf(scale, srcW)).toInt().coerceIn(0, INPUT_SIZE)
                val y1 = (padY + resizedHOf(scale, srcH)).toInt().coerceIn(0, INPUT_SIZE)
                // 裁剪 letterbox 内区域 -> 原图尺寸
                val cropW = x1 - x0
                val cropH = y1 - y0
                if (cropW <= 0 || cropH <= 0) return null
                val cropPix = IntArray(cropW * cropH)
                for (yy in 0 until cropH) {
                    val srcBase = (y0 + yy) * INPUT_SIZE + x0
                    System.arraycopy(fullPix, srcBase, cropPix, yy * cropW, cropW)
                }
                val cropBmp = Bitmap.createBitmap(cropPix, cropW, cropH, Bitmap.Config.ARGB_8888)
                val srcBmp = Bitmap.createScaledBitmap(cropBmp, srcW, srcH, true)
                cropBmp.recycle()
                val srcPix = IntArray(srcW * srcH)
                srcBmp.getPixels(srcPix, 0, srcW, 0, 0, srcW, srcH)
                srcBmp.recycle()

                val mask = BooleanArray(srcW * srcH)
                var hits = 0
                for (i in srcPix.indices) {
                    val on = (srcPix[i] and 0xFF) > 128
                    mask[i] = on
                    if (on) hits++
                }
                val coverage = hits.toFloat() / mask.size.toFloat()
                if (coverage < MIN_COVERAGE) return null
                return SegMask(mask, srcW, srcH, bestConf, coverage)
            }
        }
    }

    private fun resizedWOf(scale: Float, srcW: Int) = (srcW * scale).toInt().coerceAtLeast(1)
    private fun resizedHOf(scale: Float, srcH: Int) = (srcH * scale).toInt().coerceAtLeast(1)

    fun close() {
        runCatching { session.close() }
    }
}
