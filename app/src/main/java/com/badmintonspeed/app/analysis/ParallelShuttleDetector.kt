package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

/**
 * 多线程并行羽毛球检测器（发挥麒麟9000S 八核优势）。
 *
 * 原理：ONNX Session 非线程安全，因此为每个工作线程创建独立的 ShuttleOnnxDetector。
 * 把视频帧切成 batch，提交到固定线程池并行推理，最后按帧序合并结果。
 *
 * 线程池大小 = min(可用核数, 4)，避免小核争抢大核资源导致整体变慢。
 * 同时提供传统 CV（亮度+运动）兜底，当 YOLO 全帧漏检时回退到 BallDetector。
 */
class ParallelShuttleDetector(
    private val context: Context,
    private val numThreads: Int = min(Runtime.getRuntime().availableProcessors(), 4)
) {
    private val executor = Executors.newFixedThreadPool(numThreads)

    /** 每个线程的检测器（ThreadLocal 保证线程安全） */
    private val threadDetector = ThreadLocal<ShuttleOnnxDetector>()

    private fun getDetector(): ShuttleOnnxDetector {
        var d = threadDetector.get()
        if (d == null) {
            d = ShuttleOnnxDetector(context)
            threadDetector.set(d)
        }
        return d
    }

    /** 亮度兜底检测器（YOLO 漏检时使用） */
    private val cvDetector = BallDetector(brightThreshold = 160)

    data class FrameResult(
        val frameIndex: Int,
        val boxes: List<ShuttleOnnxDetector.Box>,
        val usedCvFallback: Boolean
    )

    /**
     * 并行检测一批帧。
     * @param frames 帧列表（按顺序）
     * @param hints  每帧的上一球位置提示（可空）
     * @return 与 frames 等长的检测结果列表
     */
    fun detectBatch(
        frames: List<Bitmap>,
        hints: List<Pair<Float, Float>?>
    ): List<FrameResult> {
        if (frames.isEmpty()) return emptyList()

        val tasks = frames.mapIndexed { idx, frame ->
            Callable {
                val detector = getDetector()
                val hint = hints.getOrNull(idx)
                val yoloBoxes = try {
                    detector.detect(frame, hint)
                } catch (e: Exception) {
                    emptyList()
                }
                if (yoloBoxes.isNotEmpty()) {
                    FrameResult(idx, yoloBoxes, usedCvFallback = false)
                } else {
                    // YOLO 漏检 → 传统 CV 兜底（亮度+连通域）
                    val cvBlobs = cvDetector.detect(frame, null).blobs
                    val cvBoxes = cvBlobs.take(3).map { b ->
                        ShuttleOnnxDetector.Box(
                            cx = b.cx, cy = b.cy,
                            w = (b.maxX - b.minX + 1).toFloat(),
                            h = (b.maxY - b.minY + 1).toFloat(),
                            conf = 0.3f // CV 结果给中等置信度
                        )
                    }
                    FrameResult(idx, cvBoxes, usedCvFallback = cvBoxes.isNotEmpty())
                }
            }
        }

        val futures: List<Future<FrameResult>> = executor.invokeAll(tasks)
        return futures.map { it.get() }
    }

    fun close() {
        // 关闭所有线程的 detector
        executor.shutdownNow()
    }
}
