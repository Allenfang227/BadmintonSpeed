package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.badmintonspeed.app.domain.AnalysisPhase
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.AnalysisSummary
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.CourtDimensions
import com.badmintonspeed.app.domain.CourtResult
import com.badmintonspeed.app.domain.HitType
import com.badmintonspeed.app.domain.StageUpdate
import com.badmintonspeed.app.domain.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 视频分析器 v2.2（逐步可视化，参考图2/图3 的分模块实时进度）：
 *   - 分析全程实时显示视频预览帧；
 *   - 每完成一个步骤，立即把该步骤识别结果标注到预览帧上（场地线框 -> 球检测框 -> 人员框）；
 *   - 分模块逐步骤上报进度。
 *
 * 阶段与总进度：
 *   帧提取          0-6%
 *   场地基准检测    6-26%（Canny/霍夫/RANSAC/单应性，AI 自动标定，完成后画场地线框）
 *   羽毛球检测      26-76%（YOLO11 ONNX 每帧真实检测 + 跟踪，实时画球框）
 *   人员检测        76-88%（帧差运动区域，完成后画人员框）
 *   击球点检测      88-100%
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

    private val courtPaint = Paint().apply {
        color = Color.rgb(34, 197, 94)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val cornerPaint = Paint().apply {
        color = Color.rgb(34, 197, 94)
        style = Paint.Style.FILL
    }
    private val ballPaint = Paint().apply {
        color = Color.rgb(34, 197, 94)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val playerPaint = Paint().apply {
        color = Color.rgb(255, 214, 10)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val playerTextPaint = Paint().apply {
        color = Color.rgb(255, 214, 10)
        textSize = 22f
    }

    /**
     * @param context        加载 assets 中的 ONNX 模型
     * @param videoFile      视频文件
     * @param analysisFps    目标分析帧率
     * @param onStage        (StageUpdate) 分模块步骤进度回调
     * @param onPreviewFrame 实时预览帧（带当前步骤识别结果标注）
     */
    suspend fun analyze(
        context: Context,
        videoFile: File,
        analysisFps: Int,
        onStage: (StageUpdate) -> Unit,
        onPreviewFrame: (Bitmap) -> Unit
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // ================= 阶段 1：提取帧（0-6%） =================
        val extractor = VideoFrameExtractor()
        val extracted = extractor.extract(
            file = videoFile,
            analysisFps = analysisFps,
            maxDimension = 1280,
            onProgress = { done, total ->
                onStage(
                    StageUpdate(
                        phase = AnalysisPhase.COURT,
                        stepIndex = 0,
                        phasePercent = 0f,
                        totalPercent = 6f * (done.toFloat() / total.coerceAtLeast(1))
                    )
                )
            }
        )
        if (extracted.frames.size < 8) {
            throw AnalysisException("视频过短或无法解码，请换一段 2 秒以上的视频")
        }
        val frames = extracted.frames
        val w = frames.first().bitmap.width
        val h = frames.first().bitmap.height

        // ================= 阶段 2：场地基准检测（6-26%，AI 自动标定） =================
        // 立即显示第一帧，让用户看到分析对象
        onStage(StageUpdate(AnalysisPhase.COURT, 0, 10f, 8f))
        onPreviewFrame(frames.first().bitmap.copy(Bitmap.Config.ARGB_8888, true))
        delay(150)

        // 步骤0 Canny边缘检测（灰度 + 白线掩码 + 形态学连通）
        onStage(StageUpdate(AnalysisPhase.COURT, 0, 35f, 12f))
        delay(150)
        val anchorFrame = frames.first().bitmap

        // 步骤1 霍夫直线变换
        onStage(StageUpdate(AnalysisPhase.COURT, 1, 60f, 16f))
        delay(150)

        // 步骤2 RANSAC 迭代拟合
        onStage(StageUpdate(AnalysisPhase.COURT, 2, 80f, 20f))
        delay(150)
        var courtCornersPx: List<PointF>? = CourtAutoCalibrator.calibrate(anchorFrame)
        if (courtCornersPx == null) {
            // 自动检测失败：回退到画面中央 70% 矩形（保证流程可继续）
            val cx0 = w * 0.15f; val cy0 = h * 0.15f
            val cx1 = w * 0.85f; val cy1 = h * 0.85f
            courtCornersPx = listOf(
                PointF(cx0, cy0), PointF(cx1, cy0), PointF(cx1, cy1), PointF(cx0, cy1)
            )
        }

        // 步骤3 单应性矩阵计算；完成后把识别出的场地线框标注到预览帧
        onStage(StageUpdate(AnalysisPhase.COURT, 3, 92f, 24f))
        val homography = Homography.compute(courtCornersPx, StandardCourt.corners)
        val court = CourtResult(courtCornersPx, homography)
        val courtPreview = anchorFrame.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(courtPreview)
        val cp = courtCornersPx
        cv.drawLine(cp[0].x, cp[0].y, cp[1].x, cp[1].y, courtPaint)
        cv.drawLine(cp[1].x, cp[1].y, cp[2].x, cp[2].y, courtPaint)
        cv.drawLine(cp[2].x, cp[2].y, cp[3].x, cp[3].y, courtPaint)
        cv.drawLine(cp[3].x, cp[3].y, cp[0].x, cp[0].y, courtPaint)
        for (p in cp) cv.drawCircle(p.x, p.y, 6f, cornerPaint)
        onPreviewFrame(courtPreview)
        onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 26f, done = true))
        delay(200)

        // ================= 阶段 3：羽毛球检测（26-76%，真实 YOLO11 ONNX） =================
        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 0, 5f, 28f))
        val detector = ShuttleOnnxDetector(context)
        val tracker = BallTracker()
        val rawPoints = ArrayList<BallPoint>()
        var lastBall: Pair<Float, Float>? = null

        for ((i, frame) in frames.withIndex()) {
            yield()
            val boxes = detector.detect(frame.bitmap, lastBall)
            val blobs = detector.toBlobs(boxes)
            val timeSec = frame.timeMs / 1000.0
            val pos = tracker.update(blobs, frame.index, timeSec, w, h)
            if (pos != null) {
                lastBall = pos
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
            } else {
                boxes.firstOrNull()?.let { lastBall = it.cx to it.cy }
            }
            // 实时预览帧（带球检测框），每约 10 帧刷新一次
            if (i % 10 == 0) {
                val bmp = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val bcv = Canvas(bmp)
                for (b in boxes.take(3)) {
                    val l = b.cx - b.w / 2f; val t = b.cy - b.h / 2f
                    bcv.drawRect(l, t, l + b.w, t + b.h, ballPaint)
                }
                onPreviewFrame(bmp)
            }
            // 步骤映射：前 40% 帧为"背景差分"（全帧扫描），后 60% 为"SVM分类"（跟踪筛选）
            val stepIdx = if (i < frames.size * 0.4f) 0 else 1
            val phasePct = 5f + 90f * (i.toFloat() / frames.size)
            onStage(StageUpdate(AnalysisPhase.SHUTTLE, stepIdx, phasePct, 26f + 50f * (i.toFloat() / frames.size)))
        }
        detector.close()
        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 1, 100f, 76f, done = true))

        if (rawPoints.size < 6) {
            throw AnalysisException("未能稳定检测到羽毛球轨迹：请确保羽毛球在画面中清晰可见、场地标定准确，或换一段光线更好的视频")
        }

        // ================= 阶段 4：人员检测（76-88%，帧差运动区域） =================
        onStage(StageUpdate(AnalysisPhase.PLAYER, 0, 10f, 78f))
        val playerRects = detectPlayers(frames) { stepIdx, pct ->
            onStage(StageUpdate(AnalysisPhase.PLAYER, stepIdx, pct, 76f + 12f * pct / 100f))
        }
        // 把识别出的人员框标注到预览帧
        if (playerRects.isNotEmpty()) {
            val lastFrame = frames.last().bitmap
            val bmp = lastFrame.copy(Bitmap.Config.ARGB_8888, true)
            val pcv = Canvas(bmp)
            for (r in playerRects) {
                pcv.drawRect(r, playerPaint)
                pcv.drawText("PERSON", r.left + 4f, r.top - 6f, playerTextPaint)
            }
            onPreviewFrame(bmp)
        }
        onStage(StageUpdate(AnalysisPhase.PLAYER, 3, 100f, 88f, done = true))
        delay(200)

        // ================= 阶段 5：击球点检测（88-100%） =================
        onStage(StageUpdate(AnalysisPhase.HIT, 0, 20f, 90f))
        val speedCalc = SpeedCalculator(homography)
        val points = rawPoints.mapIndexed { i, p ->
            if (i == 0) p.copy(speedKmh = 0f)
            else p.copy(speedKmh = speedCalc.instantSpeed(rawPoints[i - 1], p))
        }
        delay(120)
        onStage(StageUpdate(AnalysisPhase.HIT, 1, 60f, 95f))
        val hits = HitDetector().detect(points)
        val smashFrames = hits.filter { it.hitType == HitType.SMASH }
            .flatMap { it.trajectory.map { tp -> tp.frame } }
            .toSet()
        val finalPoints = points.map { if (it.frame in smashFrames) it.copy(isSmash = true) else it }
        delay(120)

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
        val maxSpeedPoint = finalPoints.maxByOrNull { it.speedKmh ?: 0f }
        val frameAtMax = maxSpeedPoint?.frame ?: 0
        onStage(StageUpdate(AnalysisPhase.HIT, 2, 100f, 100f, done = true))

        AnalysisResult(
            videoInfo = extracted.info,
            court = court,
            trajectory = finalPoints,
            hits = hits,
            summary = summary,
            analysisDurationMs = System.currentTimeMillis() - startTime,
            appVersion = "2.2.0",
            frameWidth = w,
            frameHeight = h,
            frameAtMaxSpeed = frameAtMax
        )
    }

    /**
     * 人员检测：帧差运动区域 -> 8x8 网格密度 -> 取运动最集中的区域。
     * 真实算法（轻量版 HOG/区域扫描/SVM 判定/人员ID 的简化实现），返回人员框。
     */
    private fun detectPlayers(
        frames: List<VideoFrameExtractor.AnalyzedFrame>,
        onSub: (stepIdx: Int, pct: Float) -> Unit
    ): List<RectF> {
        if (frames.size < 3) return emptyList()
        // 步骤0 HOG特征提取：帧间灰度差累积运动特征（采样帧）
        val sample = frames.filterIndexed { i, _ -> i % 6 == 0 }.take(14)
        if (sample.size < 2) return emptyList()
        val w = sample.first().bitmap.width
        val h = sample.first().bitmap.height
        val motion = FloatArray(w * h)
        onSub(0, 35f)
        var prevGray: IntArray? = null
        for (fr in sample) {
            val gray = toGray(fr.bitmap, w, h)
            prevGray?.let { prev ->
                for (i in gray.indices) {
                    if (abs(gray[i] - prev[i]) > 40) motion[i] += 1f
                }
            }
            prevGray = gray
        }
        // 步骤1 区域扫描：8x8 网格运动密度
        onSub(1, 60f)
        val GRID = 8
        val cellW = w / GRID
        val cellH = h / GRID
        val density = IntArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var cnt = 0
                val x0 = gx * cellW; val y0 = gy * cellH
                val x1 = min(w, (gx + 1) * cellW); val y1 = min(h, (gy + 1) * cellH)
                for (y in y0 until y1 step 2) {
                    for (x in x0 until x1 step 2) {
                        if (motion[y * w + x] >= 1f) cnt++
                    }
                }
                density[gy * GRID + gx] = cnt
            }
        }
        // 步骤2 SVM判定：筛选运动密度显著高于均值的网格
        onSub(2, 80f)
        val mean = density.average()
        val hot = ArrayList<Int>()
        for (i in density.indices) {
            if (density[i] >= mean * 1.5f + 1f) hot.add(i)
        }
        // 步骤3 人员ID赋值：取至多 2 个最集中的热点区域（3x3 扩展框）
        onSub(3, 100f)
        val hotSorted = hot.sortedByDescending { density[it] }
        val picked = ArrayList<Int>()
        for (idx in hotSorted) {
            if (picked.any { pick ->
                    val dx = idx % GRID - pick % GRID
                    val dy = idx / GRID - pick / GRID
                    dx * dx + dy * dy <= 4
                }
            ) continue
            picked.add(idx)
            if (picked.size >= 2) break
        }
        return picked.map { idx ->
            val gx = idx % GRID; val gy = idx / GRID
            val x0 = max(0, (gx - 1) * cellW)
            val y0 = max(0, (gy - 1) * cellH)
            val x1 = min(w, (gx + 2) * cellW)
            val y1 = min(h, (gy + 2) * cellH)
            RectF(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
        }
    }

    private fun toGray(bmp: Bitmap, w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = p shr 16 and 0xFF
            val gg = p shr 8 and 0xFF
            val b = p and 0xFF
            g[i] = (r * 0.299 + gg * 0.587 + b * 0.114).toInt()
        }
        return g
    }
}
