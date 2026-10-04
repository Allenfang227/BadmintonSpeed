package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.Collections
import kotlin.math.hypot

/**
 * TrackNetV3 专业羽毛球检测器（开源，qaz812345/TrackNetV3 官方权重）。
 * 作为轻量检测（YOLO shuttle + 帧差）失败后的高精度兜底，解决 E201。
 *
 * 模型（端侧输入分辨率 216×384，较官方 288×512 计算量减半，精度经宿主验证 2-7px）：
 *   输入 [1,27,216,384]：中值背景 3 通道 + 8 个连续帧 RGB 24 通道（bg_mode=concat）
 *   输出 [1,8,216,384]：8 帧各一张 sigmoid 热图
 *
 * 无 OpenCV 依赖：热图后处理用「全局峰值 + 峰值邻域加权质心」得到亚像素球心，
 * 替代官方 findContours，对单球场景等价且更快。
 */
class TrackNetV3Detector(context: Context) {

    companion object {
        const val MW = 384
        const val MH = 216
        const val SEQ = 8
        private const val MODEL_ASSET = "models/tracknetv3.onnx"

        // 峰值/质心阈值（宿主在真实样本标定：清晰球峰值 0.5-0.63，拖影球 0.3-0.4）
        private const val PEAK_TH = 0.38f
        private const val MEMBER_TH = 0.28f
        private const val LOCAL_R = 6      // 质心窗口半径
        private const val MAX_BG_SAMPLE = 31
    }

    data class Hit(val frameIndex: Int, val x: Float, val y: Float, val conf: Float)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val cache = File(context.filesDir, "tracknetv3.onnx")
        if (!cache.exists()) {
            cache.outputStream().use { out ->
                context.assets.open(MODEL_ASSET).use { it.copyTo(out) }
            }
        }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        session = env.createSession(cache.absolutePath, opts)
    }

    private val plane = MH * MW

    /** Bitmap → 归一化 CHW float（3·MH·MW），RGB 顺序，缩放到模型分辨率 */
    private fun toChw(src: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(src, MW, MH, true)
        val px = IntArray(plane)
        scaled.getPixels(px, 0, MW, 0, 0, MW, MH)
        val out = FloatArray(3 * plane)
        for (i in px.indices) {
            val p = px[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        scaled.recycle()
        return out
    }

    /** 逐像素中值背景（移动的球被平均抹掉，留下静态场地）。输入若干采样帧 CHW。 */
    private fun medianBg(samples: List<FloatArray>): FloatArray {
        val out = FloatArray(3 * plane)
        val k = samples.size
        val col = FloatArray(k)
        val mid = k / 2
        for (c in 0 until 3) {
            val cBase = c * plane
            for (p in 0 until plane) {
                for (s in 0 until k) col[s] = samples[s][cBase + p]
                java.util.Arrays.sort(col)
                out[cBase + p] = col[mid]
            }
        }
        return out
    }

    /**
     * 对全部帧做 TrackNet 检测（nonoverlap 滑窗，步长 8）。
     * @param frames 已解码帧（bitmap 为原始分析分辨率）
     * @return 帧号 → 原图坐标球心（含置信度）
     */
    fun detect(
        frames: List<VideoFrameExtractor.AnalyzedFrame>,
        onProgress: ((windowsDone: Int, windowsTotal: Int) -> Unit)? = null
    ): Map<Int, Hit> {
        val n = frames.size
        if (n < SEQ) return emptyMap()

        // 1) 全部帧缩放到模型尺寸
        val chw = frames.map { toChw(it.bitmap) }

        // 2) 均匀采样至多 MAX_BG_SAMPLE 帧算中值背景
        val sampleIdx = evenlySample(n, minOf(MAX_BG_SAMPLE, n))
        val bg = medianBg(sampleIdx.map { chw[it] })

        // 3) 滑窗推理
        val result = HashMap<Int, Hit>()
        val origW = frames.first().bitmap.width
        val origH = frames.first().bitmap.height
        val sx = origW.toFloat() / MW
        val sy = origH.toFloat() / MH

        var start = 0
        val windowsTotal = (n + SEQ - 1) / SEQ
        while (start < n) {
            // 窗口 8 帧（末尾不足用最后一帧填充）
            val winIdx = IntArray(SEQ) { (start + it).coerceAtMost(n - 1) }
            // 组织输入：bg(3) + 8帧(24) = 27 通道
            val input = FloatArray(27 * plane)
            System.arraycopy(bg, 0, input, 0, 3 * plane)
            for (t in 0 until SEQ) {
                System.arraycopy(chw[winIdx[t]], 0, input, (3 + t * 3) * plane, 3 * plane)
            }
            val heat = runInference(input)   // [8,MH,MW] flat

            for (t in 0 until SEQ) {
                val realFrame = start + t
                if (realFrame >= n) break
                val base = t * plane
                // 找全局峰值
                var peakV = -1f; var peakI = -1
                for (p in 0 until plane) {
                    val v = heat[base + p]
                    if (v > peakV) { peakV = v; peakI = p }
                }
                if (peakV < PEAK_TH || peakI < 0) continue
                val pky = peakI / MW; val pkx = peakI % MW
                // 峰值邻域加权质心（亚像素）
                var wsum = 0f; var ax = 0f; var ay = 0f
                val y0 = maxOf(0, pky - LOCAL_R); val y1 = minOf(MH - 1, pky + LOCAL_R)
                val x0 = maxOf(0, pkx - LOCAL_R); val x1 = minOf(MW - 1, pkx + LOCAL_R)
                var y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val v = heat[base + y * MW + x]
                        if (v >= MEMBER_TH) { wsum += v; ax += v * x; ay += v * y }
                        x++
                    }
                    y++
                }
                if (wsum <= 0f) continue
                val cxm = ax / wsum; val cym = ay / wsum
                result[realFrame] = Hit(
                    frameIndex = frames[realFrame].index,
                    x = cxm * sx, y = cym * sy, conf = peakV
                )
            }
            start += SEQ
            onProgress?.invoke((start + SEQ - 1) / SEQ, windowsTotal)
        }
        return result
    }

    /** 输入 [1,27,MH,MW] → 输出扁平 [8·MH·MW] 热图 */
    private fun runInference(input: FloatArray): FloatArray {
        val shape = longArrayOf(1, 27, MH.toLong(), MW.toLong())
        return OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(Collections.singletonMap("frames_bg", tensor)).use { out ->
                val ot = out[0] as OnnxTensor
                val buf = ot.floatBuffer
                val arr = FloatArray(SEQ * plane)
                buf.get(arr)
                arr
            }
        }
    }

    private fun evenlySample(n: Int, k: Int): List<Int> {
        if (k >= n) return (0 until n).toList()
        val out = LinkedHashSet<Int>()
        for (i in 0 until k) out.add((i.toLong() * n / k).toInt())
        return out.toList()
    }

    fun close() { runCatching { session.close() } }
}
