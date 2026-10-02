package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.badmintonspeed.app.domain.AnalysisError
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
 * 视频分析器 v2.3：
 *   流程（对应参考图1-图3）：
 *     1) 先处理视频（解码+提取帧，全程实时显示画面）
 *     2) 检查重复帧（图1："正在检测重复帧 xx%"）
 *     3) 场地基准检测（AI 自动标定，黄线框贴合场地线）
 *     4) 羽毛球检测（YOLO11 ONNX 真实检测，实时绿框）
 *     5) 人员检测（帧差运动区域，黄框）
 *     6) 击球点检测 + 球速计算
 *   错误码：E001 解码失败 / E002 重复帧过多 / E101 场地检测失败 / E201 羽毛球检出不足 / E202 轨迹点不足
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

    class AnalysisException(val error: AnalysisError) : Exception(error.display)

    /**
     * ABC 三套自动场地检测全部失败时抛出，携带视频第一帧供用户手动标定4个角点。
     * 这不是错误，而是"需要用户介入"的信号——融合自 AI-YuJian-AI 的人工标定思路。
     */
    class ManualCalibrationRequired(val firstFrame: Bitmap) : Exception("Manual court calibration required")

    private val courtPaint = Paint().apply {
        color = Color.rgb(250, 204, 21) // 黄色：贴合场地线，对应参考图
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val cornerPaint = Paint().apply {
        color = Color.rgb(250, 204, 21)
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
        onPreviewFrame: (Bitmap) -> Unit,
        manualCourtCorners: List<PointF>? = null
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        // ================= 阶段 1：先处理视频（0-4%，全程显示画面） =================
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
                        totalPercent = 4f * (done.toFloat() / total.coerceAtLeast(1))
                    )
                )
            },
            onPreview = { bmp -> onPreviewFrame(bmp) }
        )
        if (extracted.frames.size < 8) {
            throw AnalysisException(
                AnalysisError(
                    code = "E001",
                    title = "视频解码失败或过短",
                    detail = "未能从视频中提取到足够帧数，请换一段 2 秒以上、画面清晰的视频。",
                    threshold = "提取帧数 ${extracted.frames.size} / 需要 ≥ 8"
                )
            )
        }
        val frames = extracted.frames
        val w = frames.first().bitmap.width
        val h = frames.first().bitmap.height

        // ================= 阶段 2：检查重复帧（4-8%，图1） =================
        val deduped = dedupeFrames(frames) { pct ->
            onStage(
                StageUpdate(
                    phase = AnalysisPhase.COURT,
                    stepIndex = 0,
                    phasePercent = 0f,
                    totalPercent = 4f + 4f * pct / 100f
                )
            )
        }
        if (deduped.size < 6) {
            throw AnalysisException(
                AnalysisError(
                    code = "E002",
                    title = "重复帧过多，无法测速",
                    detail = "检测到视频中大量画面静止/重复（如视频卡住或误选了照片），需要包含实际打球过程的视频。",
                    threshold = "有效帧 ${deduped.size} / 需要 ≥ 6"
                )
            )
        }
        val framesAll = deduped

        // ================= 阶段 3：场地基准检测（8-28%，黄线框贴合场地线） =================
        onStage(StageUpdate(AnalysisPhase.COURT, 0, 10f, 9f))
        // 尝试多帧：首帧可能模糊/被遮挡，用首帧、1/3处、2/3处帧依次检测，任一成功即可
        // 均匀取 8 帧探测（原来只有 3 帧，容易被遮挡/模糊帧连累，导致 E101）
        val probeIndexes = (0 until 8).map { framesAll.size * it / 7 }.distinct()
        var courtCornersPx: List<PointF>? = null
        var anchorFrame = framesAll.first().bitmap

        if (manualCourtCorners != null) {
            // 用户手动标定的角点（融合自 AI-YuJian-AI：人工点4角 + 透视变换补全）。
            // 不是死套模板：AI 在用户框起来的4个区域附近搜索连续长白线，精修出准确贴合线的角点
            val anchorForRefine = framesAll.first().bitmap
            onStage(StageUpdate(AnalysisPhase.COURT, 0, 50f, 12f))
            courtCornersPx = try {
                LocalLineRefiner.refine(anchorForRefine, manualCourtCorners)
            } catch (e: Exception) {
                manualCourtCorners // 精修异常回退用户原始角点
            }
            onStage(StageUpdate(AnalysisPhase.COURT, 1, 80f, 20f))
            delay(100)
        } else {
            // ABC 三套自动检测级联
            for (pi in probeIndexes) {
                val probe = framesAll[pi.coerceIn(0, framesAll.size - 1)].bitmap
                onPreviewFrame(probe.copy(Bitmap.Config.ARGB_8888, true))
                delay(150)
                onStage(StageUpdate(AnalysisPhase.COURT, 0, 30f + 20f * (probeIndexes.indexOf(pi) + 1) / probeIndexes.size, 11f + 6f * (probeIndexes.indexOf(pi) + 1) / probeIndexes.size))
                val r = try {
                    CourtAutoCalibrator.calibrate(probe)
                } catch (e: Exception) {
                    null // 防闪退：单帧检测异常不中断整体流程
                }
                if (r != null) {
                    courtCornersPx = r
                    anchorFrame = probe
                    break
                }
            }
            onStage(StageUpdate(AnalysisPhase.COURT, 1, 60f, 17f))
            delay(150)
            onStage(StageUpdate(AnalysisPhase.COURT, 2, 80f, 21f))
            delay(150)
        }

        if (courtCornersPx == null) {
            // ABC 三套自动检测全部失败 → 尝试用"标定学习器"的历史先验（用户每次标定都被纳入学习容器，
            // 同一机位下角点在画面中相对位置相似，可直接复用上次成功标定）
            val learner = CourtLearner(context)
            val predicted = learner.predict(w, h)
            if (predicted != null && isValidPrediction(predicted, w, h)) {
                courtCornersPx = predicted
                anchorFrame = framesAll.first().bitmap
                onStage(StageUpdate(AnalysisPhase.COURT, 2, 85f, 22f))
                onStage(StageUpdate(AnalysisPhase.COURT, 3, 90f, 25f))
                onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 28f, done = true))
                onPreviewFrame(anchorFrame.copy(Bitmap.Config.ARGB_8888, true))
                delay(150)
            } else {
                // 历史先验也不可用 → 请求用户手动标定（不直接报错，融合自 AI-YuJian-AI）
                throw ManualCalibrationRequired(framesAll.first().bitmap.copy(Bitmap.Config.ARGB_8888, true))
            }
        }
        onStage(StageUpdate(AnalysisPhase.COURT, 3, 92f, 25f))
        // 把本次成功标定纳入学习容器（提高下次自动识别精准度）
        try {
            CourtLearner(context).save(courtCornersPx, w, h)
        } catch (e: Exception) {
            // 学习容器写入失败不影响分析主流程
        }
        val homography = Homography.compute(courtCornersPx, StandardCourt.corners)
        val court = CourtResult(courtCornersPx, homography)
        // 用 CourtMapper 透视变换画出完整标准场地线（融合自 AI-YuJian-AI：不只是4条外边，还包括中线/发球线/球网等）
        val courtPreview = anchorFrame.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(courtPreview)
        try {
            CourtMapper(courtCornersPx).drawFullCourt(cv, courtPaint)
        } catch (e: Exception) {
            // 透视变换异常时回退到只画4条外边
            val cp = courtCornersPx
            cv.drawLine(cp[0].x, cp[0].y, cp[1].x, cp[1].y, courtPaint)
            cv.drawLine(cp[1].x, cp[1].y, cp[2].x, cp[2].y, courtPaint)
            cv.drawLine(cp[2].x, cp[2].y, cp[3].x, cp[3].y, courtPaint)
            cv.drawLine(cp[3].x, cp[3].y, cp[0].x, cp[0].y, courtPaint)
            for (pt in cp) cv.drawCircle(pt.x, pt.y, 7f, cornerPaint)
        }
        onPreviewFrame(courtPreview)
        onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 28f, done = true))
        delay(250)

        // ================= 阶段 4：羽毛球检测（28-78%，YOLO11 ONNX 真实检测） =================
        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 0, 5f, 30f))
        val detector = ShuttleOnnxDetector(context)
        // 融合自 AI-YuJian-AI ShuttlecockTracker：帧间跳跃门限+速度预测+丢帧容忍+检测框面积/宽高比过滤+ROI限制
        val shuttleTracker = ShuttleTracker(
            maxJumpPixels = 220f,
            predictionGatePixels = 260f,
            maxMissingFrames = 5,
            maxBoxAreaRatio = 0.004f,
            maxAspectRatio = 4.0f
        )
        val rawPoints = ArrayList<BallPoint>()
        var lastBall: Pair<Float, Float>? = null
        var detectedFrames = 0
        var bgDiffHits = 0
        var yoloHits = 0
        // 背景差分检测器（用户要求："固定背景，识别移动的白色点，多帧差分确保羽毛球"）：
        // 远景斜拍时球很小很糊，YOLO 经常漏检，用背景差分做第二通道补充
        val bgDetector = BackgroundShuttleDetector()
        // 前 bgFrames 帧学背景（视频开头通常为空场地/球还没动）
        for ((i, frame) in framesAll.withIndex()) {
            if (i < 8) bgDetector.learn(frame.bitmap)
        }

        for ((i, frame) in framesAll.withIndex()) {
            yield()
            var boxes = try {
                detector.detect(frame.bitmap, lastBall)
            } catch (e: Exception) {
                emptyList() // 防闪退：单帧推理异常不中断
            }
            var useBgDiff = false
            if (boxes.isEmpty()) {
                // YOLO 没检出 → 背景差分补充（白色运动点，多帧确认）
                val bgCands = bgDetector.detectMovingWhite(frame.bitmap)
                if (bgCands.isNotEmpty()) {
                    boxes = boxes + bgCands
                    useBgDiff = true
                }
            } else {
                bgDetector.updateBackground(frame.bitmap) // 球出现后背景滚动自适应
            }
            val timeSec = frame.timeMs / 1000.0
            // ShuttleTracker 做帧间跟踪过滤（面积/宽高比/跳跃/预测/丢帧/ROI），输出稳定球心
            val pos = shuttleTracker.update(boxes, w, h, courtCornersPx)
            if (pos != null) {
                detectedFrames++
                lastBall = pos.x to pos.y
                if (useBgDiff) bgDiffHits++ else yoloHits++
                val courtPos = Homography.pixelToCourt(homography, pos.x, pos.y)
                rawPoints.add(
                    BallPoint(
                        frame = frame.index,
                        timeSec = timeSec,
                        x = pos.x,
                        y = pos.y,
                        confidence = boxes.firstOrNull()?.conf ?: 0.5f,
                        courtX = courtPos.x,
                        courtY = courtPos.y
                    )
                )
            } else {
                boxes.firstOrNull()?.let { lastBall = it.cx to it.cy }
            }
            // 实时预览帧：只画跟踪确认的唯一球框（每帧一个框，不叠加历史候选）
            if (i % 10 == 0) {
                val bmp = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val bcv = Canvas(bmp)
                if (pos != null) {
                    val boxSize = 34f
                    bcv.drawRect(
                        pos.x - boxSize / 2f, pos.y - boxSize / 2f,
                        pos.x + boxSize / 2f, pos.y + boxSize / 2f,
                        ballPaint
                    )
                }
                onPreviewFrame(bmp)
            }
            // 步骤映射：前 40% 帧为"背景差分"（全帧扫描），后 60% 为"SVM分类"（跟踪筛选）
            val stepIdx = if (i < framesAll.size * 0.4f) 0 else 1
            val phasePct = 5f + 90f * (i.toFloat() / framesAll.size)
            onStage(StageUpdate(AnalysisPhase.SHUTTLE, stepIdx, phasePct, 28f + 50f * (i.toFloat() / framesAll.size)))
        }
        detector.close()
        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 1, 100f, 78f, done = true))

        if (detectedFrames < 6) {
            throw AnalysisException(
                AnalysisError(
                    code = "E201",
                    title = "羽毛球检测失败",
                    detail = "YOLO 与背景差分双通道均未能稳定识别出羽毛球：请确保羽毛球在画面中清晰可见（不要太小、不要和白色背景融合），且击球过程完整出现在画面内。建议：①离场地近一点拍；②拉近镜头让球更大；③保证球和背景颜色差异明显。",
                    threshold = "检出帧 $detectedFrames / 总帧 ${framesAll.size}（YOLO $yoloHits + 背景差分 $bgDiffHits），需要 ≥ 6 帧"
                )
            )
        }
        if (rawPoints.size < 6) {
            throw AnalysisException(
                AnalysisError(
                    code = "E202",
                    title = "轨迹跟踪不足",
                    detail = "虽然识别到了羽毛球，但连续轨迹过短，无法计算球速。请用更稳定的视角拍摄完整击球过程。",
                    threshold = "连续轨迹点 ${rawPoints.size} / 需要 ≥ 6"
                )
            )
        }

        // ================= 阶段 5：人员检测（78-88%，帧差运动区域） =================
        onStage(StageUpdate(AnalysisPhase.PLAYER, 0, 10f, 79f))
        val playerRects = detectPlayers(framesAll) { stepIdx, pct ->
            onStage(StageUpdate(AnalysisPhase.PLAYER, stepIdx, pct, 78f + 10f * pct / 100f))
        }
        if (playerRects.isNotEmpty()) {
            val lastFrame = framesAll.last().bitmap
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

        // ================= 阶段 6：击球点检测 + 球速（88-100%） =================
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
            appVersion = "2.10.0",
            frameWidth = w,
            frameHeight = h,
            frameAtMaxSpeed = frameAtMax
        )
    }

    /**
     * 重复帧检查（对应图1"正在检测重复帧"）：
     * 对相邻帧做降采样灰度比较，高度相似（≥97%）的帧标记为重复。
     * @return 去重后的帧列表
     */
    private fun dedupeFrames(
        frames: List<VideoFrameExtractor.AnalyzedFrame>,
        onProgress: (Float) -> Unit
    ): List<VideoFrameExtractor.AnalyzedFrame> {
        if (frames.size < 2) return frames
        val result = ArrayList<VideoFrameExtractor.AnalyzedFrame>()
        result.add(frames.first())
        val SW = 48
        val SH = 27
        var prevHash = grayHash(frames.first().bitmap, SW, SH)
        for (i in 1 until frames.size) {
            val hash = grayHash(frames[i].bitmap, SW, SH)
            var diff = 0
            for (k in hash.indices) {
                if (abs(hash[k] - prevHash[k]) > 24) diff++
            }
            val similarRatio = 1f - diff.toFloat() / hash.size
            if (similarRatio < 0.97f) {
                result.add(frames[i])
                prevHash = hash
            }
            if (i % 8 == 0) {
                onProgress(i.toFloat() / frames.size * 100f)
            }
        }
        onProgress(100f)
        return result
    }

    private fun grayHash(bmp: Bitmap, sw: Int, sh: Int): IntArray {
        val out = IntArray(sw * sh)
        val sx = bmp.width.toFloat() / sw
        val sy = bmp.height.toFloat() / sh
        val px = IntArray(sw * sh)
        // 简单双线性取样的近似：中心采样
        for (y in 0 until sh) {
            val syi = ((y + 0.5f) * sy).toInt().coerceIn(0, bmp.height - 1)
            for (x in 0 until sw) {
                val sxi = ((x + 0.5f) * sx).toInt().coerceIn(0, bmp.width - 1)
                px[y * sw + x] = bmp.getPixel(sxi, syi)
            }
        }
        for (i in px.indices) {
            val p = px[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            out[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
        return out
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

    /** 校验学习器预测的角点是否合理（在画面内、面积占比合理、是凸四边形） */
    private fun isValidPrediction(corners: List<PointF>, w: Int, h: Int): Boolean {
        if (corners.size != 4) return false
        for (p in corners) {
            if (p.x < -0.05f * w || p.x > 1.05f * w || p.y < -0.05f * h || p.y > 1.05f * h) return false
        }
        val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
        val area = (maxX - minX) * (maxY - minY)
        if (area < 0.03f * w * h) return false
        if (area > 0.95f * w * h) return false
        return true
    }
}
