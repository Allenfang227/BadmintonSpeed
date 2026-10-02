package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.AnalysisSummary
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.CourtDimensions
import com.badmintonspeed.app.domain.CourtResult
import com.badmintonspeed.app.domain.HitType
import com.badmintonspeed.app.domain.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * 视频分析器（MVP 简化 Pipeline，文档 3.9 / 9.3）：
 *   阶段 1 提取帧      （进度 0% -> 20%）
 *   阶段 2 计算透视矩阵（20% -> 25%）
 *   阶段 3 球检测+跟踪  （25% -> 85%）
 *   阶段 4 速度+击球    （85% -> 100%）
 */
object StandardCourt {
    val corners = listOf(
        PointF(0f, 0f),
        PointF(CourtDimensions.WIDTH_M, 0f),
        PointF(CourtDimensions.WIDTH_M, CourtDimensions.LENGTH_M),
        PointF(0f, CourtDimensions.LENGTH_M)
    )
}

class VideoAnalyzer {

    data class AnalysisException(override val message: String) : Exception(message)

    /**
     * @param context 用于加载 assets 中的 ONNX 模型
     * @param videoFile 视频文件
     * @param courtCornersPx 用户标定的场地四角（像素，顺序：左上->右上->右下->左下）
     * @param analysisFps 目标分析帧率
     * @param onProgress (percent 0-100, stageName)
     */
    suspend fun analyze(
        context: Context,
        videoFile: File,
        courtCornersPx: List<PointF>,
        analysisFps: Int,
        onProgress: (Float, String) -> Unit
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        require(courtCornersPx.size == 4) { "场地标定需要 4 个角点" }

        // ---- 阶段 1：提取帧 ----
        onProgress(2f, "提取视频帧")
        val extractor = VideoFrameExtractor()
        val extracted = extractor.extract(
            file = videoFile,
            analysisFps = analysisFps,
            onProgress = { done, total ->
                onProgress(2f + 18f * (done.toFloat() / total.coerceAtLeast(1)), "提取视频帧")
            }
        )
        if (extracted.frames.size < 24) {
            throw AnalysisException("视频过短或无法解码，请换一段 2 秒以上的视频")
        }
        val frames = extracted.frames

        // ---- 阶段 2：透视矩阵 ----
        onProgress(22f, "计算场地透视")
        val homography = Homography.compute(courtCornersPx, StandardCourt.corners)
        val court = CourtResult(courtCornersPx, homography)

        // ---- 阶段 3：球检测（真实 AI：YOLO11 ONNX）+ 跟踪 ----
        onProgress(26f, "检测羽毛球轨迹")
        val detector = ShuttleOnnxDetector(context)
        val tracker = BallTracker()
        val rawPoints = ArrayList<BallPoint>()
        val w = frames.first().bitmap.width
        val h = frames.first().bitmap.height

        for ((i, frame) in frames.withIndex()) {
            yield()
            val boxes = detector.detect(frame.bitmap)
            val blobs = detector.toBlobs(boxes)
            val timeSec = frame.timeMs / 1000.0
            val pos = tracker.update(blobs, frame.index, timeSec, w, h)
            if (pos != null) {
                val courtPos = Homography.pixelToCourt(homography, pos.first, pos.second)
                rawPoints.add(
                    BallPoint(
                        frame = frame.index,
                        timeSec = timeSec,
                        x = pos.first,
                        y = pos.second,
                        confidence = boxes.firstOrNull()?.conf ?: 0.5f,
                        courtX = courtPos.x,
                        courtY = courtPos.y
                    )
                )
            }
            if (i % 4 == 0) {
                onProgress(26f + 56f * (i.toFloat() / frames.size), "检测羽毛球轨迹")
            }
        }
        detector.close()
        onProgress(82f, "检测羽毛球轨迹")

        if (rawPoints.size < 12) {
            throw AnalysisException("未能稳定检测到羽毛球轨迹：请确保羽毛球在画面中清晰可见、场地标定准确，或换一段光线更好的视频")
        }

        // ---- 阶段 4：速度 + 击球 ----
        onProgress(84f, "计算球速与击球")
        val speedCalc = SpeedCalculator(homography)
        val points = rawPoints.mapIndexed { i, p ->
            if (i == 0) p.copy(speedKmh = 0f)
            else p.copy(speedKmh = speedCalc.instantSpeed(rawPoints[i - 1], p))
        }

        // 标注杀球飞行段
        val hits = HitDetector().detect(points)
        val smashFrames = hits.filter { it.hitType == HitType.SMASH }
            .flatMap { it.trajectory.map { tp -> tp.frame } }
            .toSet()
        val finalPoints = points.map { if (it.frame in smashFrames) it.copy(isSmash = true) else it }

        val speeds = finalPoints.mapNotNull { it.speedKmh }.filter { it > 0f }
        val maxSpeed = speeds.maxOrNull() ?: 0f
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average().toFloat() else 0f
        val fastestHit = hits.maxByOrNull { it.maxSpeedKmh }
        val summary = AnalysisSummary(
            totalHits = hits.size,
            smashCount = hits.count { it.hitType == HitType.SMASH },
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            fastestHitId = fastestHit?.id
        )

        // 最高速所在帧
        val maxSpeedPoint = finalPoints.maxByOrNull { it.speedKmh ?: 0f }
        val frameAtMax = maxSpeedPoint?.frame ?: 0

        onProgress(100f, "完成")

        AnalysisResult(
            videoInfo = extracted.info,
            court = court,
            trajectory = finalPoints,
            hits = hits,
            summary = summary,
            analysisDurationMs = System.currentTimeMillis() - startTime,
            appVersion = "2.0.0",
            frameWidth = w,
            frameHeight = h,
            frameAtMaxSpeed = frameAtMax
        )
    }
}
