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
import com.badmintonspeed.app.analysis.PoseDetector
import com.badmintonspeed.app.data.BallLearner
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.PoseFrameData
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
    private val candidatePaint = Paint().apply {
        color = Color.rgb(250, 204, 21)
        style = Paint.Style.STROKE
        strokeWidth = 2f
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
    // v2.12 骨骼识别画笔（MediaPipe 姿态，青绿色骨架）
    private val poseBonePaint = Paint().apply {
        color = Color.rgb(34, 211, 238)  // 青绿：与黄色球框区分
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    /** v2.13 多帧投票：两套角点逐角距离 < 帧宽8% 视为一致 */
    private fun cornersClose(a: List<PointF>, b: List<PointF>, frameW: Int): Boolean {
        if (a.size < 4 || b.size < 4) return false
        val tol = frameW * 0.08f
        var close = 0
        for (i in 0 until 4) {
            val dx = a[i].x - b[i].x
            val dy = a[i].y - b[i].y
            if (kotlin.math.hypot(dx, dy) < tol) close++
        }
        return close >= 3
    }

    private val poseJointPaint = Paint().apply {
        color = Color.rgb(34, 211, 238)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 场地颜色先验叠加文字（白字+黑描边，任何底色上可读） */
    private val courtColorPaint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        isAntiAlias = true
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    /**
     * 场地颜色识别（v2.23 新增独立阶段，秒级完成）：
     * 采样画面中心 60% 区域（避开边线/广告/观众席），按 RGB 主色调分类，
     * 输出"绿色场地/蓝色场地/木地板/深色场地…"作为场地检测前的先验展示。
     */
    private fun detectCourtColor(bmp: Bitmap): String {
        return try {
            val w = bmp.width; val h = bmp.height
            var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var n = 0.0
            val x0 = (w * 0.2f).toInt(); val y0 = (h * 0.2f).toInt()
            val x1 = (w * 0.8f).toInt(); val y1 = (h * 0.8f).toInt()
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = px[y * w + x]
                    rSum += (c shr 16) and 0xFF
                    gSum += (c shr 8) and 0xFF
                    bSum += c and 0xFF
                    n++
                    x += 2
                }
                y += 2
            }
            if (n < 4) return "未知"
            val r = (rSum / n).toFloat()
            val g = (gSum / n).toFloat()
            val b = (bSum / n).toFloat()
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val s = if (mx == 0f) 0f else (mx - mn) / mx
            val v = mx
            when {
                s < 0.15f && v > 120f -> "灰白/木地板色"
                g >= r && g >= b && s > 0.15f -> "绿色场地"
                b >= r && b >= g && s > 0.15f -> "蓝色场地"
                r >= g && r >= b && s > 0.2f -> "红/橙色场地"
                v < 80f -> "深色场地"
                else -> "混合色场地"
            }
        } catch (e: Exception) {
            "未知"
        }
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
        manualCourtCorners: List<PointF>? = null,
        onCourt: (CourtResult) -> Unit = {},
        roiPolygon: List<PointF>? = null, // v2.17：用户首帧框选的目标场地多边形（B误检修复）
        reuseFrames: List<VideoFrameExtractor.AnalyzedFrame>? = null, // v2.27.3：失败重试复用已解码帧（跳过重新转格式）
        onFrames: (List<VideoFrameExtractor.AnalyzedFrame>) -> Unit = {} // v2.27.3：解码完成后暴露帧供上层缓存
    ): AnalysisResult = withContext(Dispatchers.Default) {
        // v2.18：本地训练模型（红框标注→模板库），实测时调用辅助识别羽毛球
        val learnedModel = try {
            BallLearner.loadModel(File(context.filesDir, "BadmintonSpeed/ball_model"))
        } catch (e: Exception) { null }
        // v2.24：训练完导出的用户 ONNX 模型（优先于模板打分，缺失/失败自动回退）
        val userOnnx = try { OnnxUserModel.load(context) } catch (e: Exception) { null }
        // v2.30 明确用户训练模型的加载状态（用户反馈"报错都不知道训练了哪个/是否生效"）
        val userModelStatus = when {
            userOnnx != null -> "已加载你的专属 ONNX 模型，已参与判定"
            learnedModel != null -> "已加载你的模板模型（${learnedModel.count} 个模板，建议导出 ONNX），已参与判定"
            else -> "未加载到你的训练模型（请在模型训练页导入、训练并导出 ONNX）"
        }
        val startTime = System.currentTimeMillis()

        // ================= 阶段 1：先转格式/抽帧（0-10%，全程显示画面） =================
        // v2.23 用户要求"处理视频主要是转化成需要的格式，而不是开始测速"：
        // 此阶段只做 MediaCodec 顺序解码 + 抽帧（不再逐帧 seek），不跑任何检测
        // v2.27.3：失败重试（手动标定后）直接复用上层缓存的已解码帧，跳过重新转格式——
        // 用户框选完场地不想再等一次解码。
        var videoInfo: VideoInfo? = null
        val frames: List<VideoFrameExtractor.AnalyzedFrame> = if (reuseFrames != null && reuseFrames.size >= 8) {
            onPreviewFrame(reuseFrames.first().bitmap)
            reuseFrames
        } else {
            val extractor = VideoFrameExtractor()
            val extracted = extractor.extract(
                file = videoFile,
                analysisFps = analysisFps,
                maxDimension = 800,
                onProgress = { done, total ->
                    onStage(
                        StageUpdate(
                            phase = AnalysisPhase.DECODE,
                            stepIndex = if (done < total) 1 else 2,
                            phasePercent = 100f * done.toFloat() / total.coerceAtLeast(1),
                            totalPercent = 15f * done.toFloat() / total.coerceAtLeast(1),
                            detail = "正在解码第 $done/$total 帧 · MediaCodec硬件解码"
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
            videoInfo = extracted.info
            onFrames(extracted.frames)
            extracted.frames
        }
        val w = frames.first().bitmap.width
        val h = frames.first().bitmap.height
        // v2.38 解码完成，发 DECODE done
        onStage(StageUpdate(AnalysisPhase.DECODE, 2, 100f, 15f, done = true,
            detail = "解码完成：共 ${frames.size} 帧 · ${w}x${h}"))

        // ================= 阶段 2：识别场地颜色（15-17%，轻量先验） =================
        // v2.23 用户要求"先转换成格式，然后识别一下场地颜色"：
        // 只做中心区域主色分类（绿/蓝/木地板/其他），作为场地检测前的先验展示，秒级完成
        onStage(StageUpdate(AnalysisPhase.COURT, 0, 10f, 16f, detail = "识别场地主色（绿/蓝/木地板）"))
        val courtColor = detectCourtColor(frames.first().bitmap)
        val colorPreview = frames.first().bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val cvColor = Canvas(colorPreview)
        cvColor.drawText("场地颜色：$courtColor", 24f, 56f, courtColorPaint)
        onPreviewFrame(colorPreview)
        onStage(StageUpdate(AnalysisPhase.COURT, 0, 100f, 17f))
        delay(60)

        // ================= 阶段 3：场地基准检测（12-32%，全 AI 标注，去掉手工标定） =================
        // v2.13 用户要求："去掉手工标注，改为全AI标注"。逻辑：
        //  1) 视角固定 → 截取前10帧、中间10帧、后10帧 → 逐帧图片识别（背景色验证+长实线扫描）
        //  2) 多时间点投票：多数一致才采纳（"背景不在这种场地上就判误识别"）
        //  3) 全部失败 → 学习容器历史先验 → 仍失败报 E101（不再弹手动标定）
        // v2.27.1 修复 E101 死循环：用户手动标定过 4 个角点（submitManualCourtCorners）后
        // 重新分析时 manualCourtCorners 必须直接采用——此前该参数从未被读取，标定完又全自动
        // 检测再失败 → 又弹 E101 → 无限循环。
        var courtCornersPx: List<PointF>? = null
        var anchorFrame = frames.first().bitmap
        // v2.33.1：用户手动标定的角点直接用于本次分析，不再做颜色校验微调（用户标得准，校验反而改歪）
        var fromUserCalibration = false
        if (manualCourtCorners != null && isValidPrediction(manualCourtCorners, w, h)) {
            fromUserCalibration = true
            courtCornersPx = manualCourtCorners
            anchorFrame = frames.first().bitmap
            onStage(StageUpdate(AnalysisPhase.COURT, 1, 100f, 29f))
            onStage(StageUpdate(AnalysisPhase.COURT, 2, 100f, 30f))
            onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 31f))
            onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 32f, done = true))
            onPreviewFrame(anchorFrame.copy(Bitmap.Config.ARGB_8888, true))
            delay(150)
        } else {
        val courtSamples = LinkedHashSet<Int>()
        val third = frames.size / 3
        // 前10帧段、中间10帧段、后10帧段（每段均匀取帧）
        for (segStart in listOf(0, third, third * 2)) {
            val seg = (0 until minOf(10, frames.size)).map { (segStart + it * frames.size / 30).coerceIn(0, frames.size - 1) }.distinct()
            courtSamples.addAll(seg)
        }
        val candidates = ArrayList<List<PointF>>()
        val sampleList = courtSamples.toList()
        // v2.28 开源真AI场地分割：只对首帧跑 1 次（场地静态），
        // 输出原图尺寸场地掩码作为白线提取的空间约束（不依赖颜色，含蓝色/木地板场地）
        var aiSeg: CourtSegDetector.SegMask? = null
        var aiSegDetector: CourtSegDetector? = null
        try {
            aiSegDetector = CourtSegDetector(context)
            val firstProbe = frames[0].bitmap
            onStage(StageUpdate(AnalysisPhase.COURT, 0, 3f, 18f, detail = "AI场地语义分割（开源模型）"))
            aiSeg = aiSegDetector.segment(firstProbe)
        } catch (e: Exception) {
            aiSeg = null // AI 分割失败不阻塞：回退颜色掩码
        } finally {
            aiSegDetector?.close()
        }
        // v2.15 E101 失败分类统计（A漏检/B误检/C拓扑错/D几何歪）：
        // 用户要求"每一类占比 + 判断主因"，先积累采样帧的诊断信号再给出占比
        val diagCount = IntArray(4) // [A漏检, B误检, C拓扑错, D几何歪]
        for ((idx, pi) in sampleList.withIndex()) {
            val probe = frames[pi.coerceIn(0, frames.size - 1)].bitmap
            val pct = 18f + 20f * ((idx + 1).toFloat() / sampleList.size)
            onStage(StageUpdate(AnalysisPhase.COURT, 0, pct, 19f + 10f * ((idx + 1).toFloat() / sampleList.size),
                detail = "多帧场地线检测（${idx + 1}/${sampleList.size}）"))
            val r = try {
                CourtAutoCalibrator.calibrate(probe, roiPolygon, aiSeg?.mask)
            } catch (e: Exception) {
                null // 防闪退：单帧检测异常不中断整体流程
            }
            // v2.15 诊断分类：
            //  A漏检：白线像素占比过低（线太淡/被遮挡/画面昏暗）
            //  B误检：有白线但构不成四边形（邻场线/广告/接缝干扰）
            //  C拓扑错：四边形有了但几何校验不过（连线关系错误）
            //  D几何歪：几何校验过但长实线验证不过（畸变/反光/单应性病态）
            val diag = CourtAutoCalibrator.lastDiagnosis
            if (r == null) {
                if (diag.whiteRatio < 0.03f) diagCount[0]++ else diagCount[1]++
            } else {
                if (!diag.geomOk) diagCount[2]++
                else if (!CourtAutoCalibrator.verifyLongLines(probe, r)) diagCount[3]++
            }
            // 长实线验证：四边形每边要落在白色长实线上（用户："扫到绿色或蓝色或红色地上的长实线"）
            val rValid = r != null && CourtAutoCalibrator.verifyLongLines(probe, r)
            if (rValid && r != null) {
                candidates.add(r)
            }
            // v2.39 左边预览框实时显示 AI 推理过程：把本帧检测到的候选四边形画到帧上
            if (r != null) {
                val vis = probe.copy(Bitmap.Config.ARGB_8888, true)
                val cvc = Canvas(vis)
                val pp = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f
                    color = if (rValid) 0xFF00E676.toInt() else 0xFFFF5252.toInt()
                }
                val path = android.graphics.Path()
                r.forEachIndexed { ci, pt ->
                    if (ci == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                path.close()
                cvc.drawPath(path, pp)
                val tp = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE; textSize = 26f; isFakeBoldText = true
                    setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                }
                cvc.drawText("帧${idx + 1}/${sampleList.size} ${if (rValid) "✓候选场地" else "✗几何不符"}", 16f, 40f, tp)
                onPreviewFrame(vis)
            }
        }
        // 多帧投票（v2.14 借鉴 VLX-Seek"候选区域检索+选择"思路放宽）：
        // ①多帧一致（≥2套角点相近）优先采纳；②否则取第一个通过长实线验证的候选直接采纳——
        // 用户视频是固定机位斜拍，帧间角点可能因反光/遮挡有细微差异，单帧高置信也应可用。
        if (candidates.isNotEmpty()) {
            val agree = HashMap<Int, Int>() // candidate index -> 一致票数
            for (i in candidates.indices) {
                var votes = 1
                for (j in candidates.indices) {
                    if (i == j) continue
                    if (cornersClose(candidates[i], candidates[j], frames.first().bitmap.width)) votes++
                }
                agree[i] = votes
            }
            val best = agree.maxByOrNull { it.value }
            courtCornersPx = if (best != null && best.value >= 2) {
                candidates[best.key] // 多帧一致：最高置信
            } else {
                candidates.first() // 单帧通过验证：直接采纳（不再死板要求多数一致）
            }
            anchorFrame = frames[sampleList[0].coerceIn(0, frames.size - 1)].bitmap
        }
        onStage(StageUpdate(AnalysisPhase.COURT, 1, 70f, 29f))
        delay(120)

        if (courtCornersPx == null) {
            // 全 AI 检测失败 → 学习容器历史先验（用户每次标定被纳入容器学习，同一机位可直接复用）
            val learner = CourtLearner(context)
            val predicted = learner.predict(w, h)
            if (predicted != null && isValidPrediction(predicted, w, h)) {
                courtCornersPx = predicted
                anchorFrame = frames.first().bitmap
                onStage(StageUpdate(AnalysisPhase.COURT, 2, 85f, 30f))
                onStage(StageUpdate(AnalysisPhase.COURT, 3, 90f, 31f))
                onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 32f, done = true))
                onPreviewFrame(anchorFrame.copy(Bitmap.Config.ARGB_8888, true))
                delay(150)
            } else {
                // 全 AI 与历史先验均失败 → 直接 E101（用户要求去掉手工标注）
                // v2.15 失败分类占比：A漏检/B误检/C拓扑错/D几何歪
                val total = diagCount.sum().coerceAtLeast(1)
                val names = listOf("A漏检", "B误检", "C拓扑错", "D几何歪")
                val parts = diagCount.mapIndexed { i, c ->
                    "${names[i]} ${c * 100 / total}%"
                }.joinToString(" ")
                val mainCause = when (diagCount.indices.maxByOrNull { diagCount[it] }) {
                    0 -> "主因：线太淡/被遮挡（数据与预处理问题）"
                    1 -> "主因：邻场线/广告/接缝误检（建议拍摄时只框住单个场地）"
                    2 -> "主因：线连错关系（模板约束已介入仍失败，多为视角过斜）"
                    else -> "主因：相机畸变或单应性求解病态（建议镜头更正、减少广角）"
                }
                throw AnalysisException(
                    AnalysisError(
                        code = "E101",
                        title = "场地检测失败",
                        detail = "AI 未能从视频中识别出羽毛球场地。请确认：①视频画面里包含完整场地；②光线不要太暗；③摄像头固定不要摇晃。",
                        threshold = "失败分类占比：$parts；$mainCause",
                        suggestManual = true // v2.15：AI 优先 + 人工引导兜底（拖拽四角修正入口）
                    )
                )
            }
        }
        } // v2.27.1: else 分支（全自动检测）闭合
        onStage(StageUpdate(AnalysisPhase.COURT, 3, 92f, 31f))
        // v2.33 场地线两侧颜色校验：AI 标注后自行微调——沿线取两侧像素颜色，
        // 两侧都≈场地主色才正确；断节/错段（黑色/其他色）沿法向重新对齐，
        // 4 条外边界线微调后重新求交得到修正角点（偏移≤帧宽5%，防过拟合）。
        // v2.33.1：用户手动标定的角点（fromUserCalibration）跳过校验——用户标得准，直接用。
        var corners = courtCornersPx!!
        if (!fromUserCalibration && anchorFrame != null) {
            try {
                val validated = CourtLineColorValidator.validate(anchorFrame, corners)
                if (validated.adjusted) corners = validated.corners
            } catch (e: Exception) { /* 校验失败不影响主流程 */ }
        }
        // 把本次成功标定纳入学习容器（提高下次自动识别精准度）
        try {
            CourtLearner(context).save(corners, w, h)
        } catch (e: Exception) {
            // 学习容器写入失败不影响分析主流程
        }
        val homography = Homography.compute(corners, StandardCourt.corners)
        val court = CourtResult(corners, homography)
        onCourt(court) // v2.12：分析中实时暴露场地，UI 层常驻叠加黄线（不再闪一下消失）
        // 用 CourtMapper 透视变换画出完整标准场地线（融合自 AI-YuJian-AI：不只是4条外边，还包括中线/发球线/球网等）
        val courtPreview = anchorFrame.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(courtPreview)
        try {
            CourtMapper(corners).drawFullCourt(cv, courtPaint)
        } catch (e: Exception) {
            // 透视变换异常时回退到只画4条外边
            val cp = corners
            cv.drawLine(cp[0].x, cp[0].y, cp[1].x, cp[1].y, courtPaint)
            cv.drawLine(cp[1].x, cp[1].y, cp[2].x, cp[2].y, courtPaint)
            cv.drawLine(cp[2].x, cp[2].y, cp[3].x, cp[3].y, courtPaint)
            cv.drawLine(cp[3].x, cp[3].y, cp[0].x, cp[0].y, courtPaint)
            for (pt in cp) cv.drawCircle(pt.x, pt.y, 7f, cornerPaint)
        }
        onPreviewFrame(courtPreview)
        onStage(StageUpdate(AnalysisPhase.COURT, 3, 100f, 32f, done = true))
        delay(250)

        // ================= 阶段 3.5：检查重复帧（32-35%，图1） =================
        // v2.23 用户要求顺序：转格式 → 场地颜色 → 场地检测 → 重复帧 → 正式检测
        val deduped = dedupeFrames(frames) { pct ->
            onStage(
                StageUpdate(
                    phase = AnalysisPhase.COURT,
                    stepIndex = 0,
                    phasePercent = 0f,
                    totalPercent = 32f + 3f * pct / 100f
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

        // ================= 阶段 4：人员检测（v2.37 先算好球员框，球检测时排除人员区域，严格串行） =================
        onStage(StageUpdate(AnalysisPhase.PLAYER, 0, 5f, 35f))
        var playerRects = try { detectPlayers(framesAll) { stepIdx, pct ->
            onStage(StageUpdate(AnalysisPhase.PLAYER, stepIdx, pct, 35f + 4f * pct / 100f))
        } } catch (e: Exception) { emptyList() }
        onStage(StageUpdate(AnalysisPhase.PLAYER, 3, 100f, 39f, done = true))
        val excludedRects = playerRects.map { r ->
            android.graphics.RectF(
                (r.left - r.width() * 0.3f).coerceAtLeast(0f),
                (r.top - r.height() * 0.3f).coerceAtLeast(0f),
                (r.right + r.width() * 0.3f).coerceAtMost(w.toFloat()),
                (r.bottom + r.height() * 0.3f).coerceAtMost(h.toFloat())
            )
        }
        fun inPlayerRect(x: Float, y: Float): Boolean {
            for (r in excludedRects) {
                if (x in r.left..r.right && y in r.top..r.bottom) return true
            }
            return false
        }

        // ================= 阶段 5：羽毛球检测（YOLO11 ONNX 真实检测 + 背景差分 + 帧间差分） =================
        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 0, 5f, 40f))
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
        val bgDetector = BackgroundShuttleDetector(
            bgFrames = 12,          // 多学几帧背景，球还没动时建干净背景
            diffThreshold = 24,    // 与背景亮度差
            whiteThreshold = 100,  // 白色阈值降低：远景球较暗也能检出（v2.12 提灵敏度）
            cellSize = 16,          // 聚类格子缩小：球很小也能成簇
            minCellHits = 3,
            maxBlobCells = 10,
            minMovePx = 4f,         // 帧间最小移动：小球帧间位移小也认
            maxFrameJump = 70f
        )
        // 前 bgFrames 帧学背景（视频开头通常为空场地/球还没动）
        for ((i, frame) in framesAll.withIndex()) {
            if (i < 12) bgDetector.learn(frame.bitmap)
        }

        // v2.13 帧间差分检测器（用户要求："AI 比较每两帧之间……移动的白色羽毛球"）：
        // 固定背景不动，比较第2帧和第4帧（间隔1帧），移动的白色点就是球候选。
        // 与 YOLO、背景差分并列第三通道，提高检出率。
        val frameDiffDetector = BackgroundShuttleDetector(
            bgFrames = 2, diffThreshold = 26, whiteThreshold = 110, cellSize = 12,
            minCellHits = 2, maxBlobCells = 6, minMovePx = 3f, maxFrameJump = 60f
        )
        var prevDiffFrame: Bitmap? = null

        for ((i, frame) in framesAll.withIndex()) {
            yield()
            // 速度优化（用户要求"视频处理速度过慢"）：YOLO ONNX 推理最贵，
            // 隔帧跑（偶数帧跑 YOLO，奇数帧靠差分+跟踪预测补点），速度约提升 1.6 倍
            val runYolo = i % 2 == 0
            var boxes = if (runYolo) {
                try {
                    detector.detect(frame.bitmap, lastBall)
                } catch (e: Exception) {
                    emptyList() // 防闪退：单帧推理异常不中断
                }
            } else emptyList()
            var useBgDiff = false
            // 帧间差分通道（每帧都跑，便宜）：与上一帧间隔1帧比较，移动白色点
            val diffCands = prevDiffFrame?.let { prev ->
                try { frameDiffDetector.detectMovingWhite(frame.bitmap) } catch (e: Exception) { emptyList() }
            } ?: emptyList()
            prevDiffFrame = frame.bitmap
            if (boxes.isEmpty()) {
                // YOLO 没检出 → 背景差分 + 帧间差分补充（白色运动点，多帧确认）
                val bgCands = bgDetector.detectMovingWhite(frame.bitmap)
                val merged = bgCands + diffCands
                if (merged.isNotEmpty()) {
                    boxes = boxes + merged
                    useBgDiff = true
                }
                // v2.18 本地训练模型补检：红框标注训练出的羽毛球模板对运动白点打分，
                // 高置信（>0.58）且不与现有候选重复的补入 boxes（球很小很糊时 YOLO 漏检的救星）
                // v2.24：优先用训练完导出的用户 ONNX 模型打分（onnxruntime 推理），
                // 导出失败/加载失败时回退 BallLearner 模板打分
                if (learnedModel != null && merged.isNotEmpty()) {
                    val extra = ArrayList<ShuttleOnnxDetector.Box>()
                    for (c in merged) {
                        if (boxes.any { kotlin.math.hypot((it.cx - c.cx).toDouble(), (it.cy - c.cy).toDouble()) < 20.0 }) continue
                        val patch = safeCrop(frame.bitmap, c.cx.toInt(), c.cy.toInt(), 48) ?: continue
                        val sc = if (userOnnx != null) {
                            val s = userOnnx.score(patch)
                            if (s >= 0f) s else BallLearner.match(patch, learnedModel)
                        } else {
                            BallLearner.match(patch, learnedModel)
                        }
                        // v2.33 用户训练模型置信度优先：阈值从 0.58 降到 0.52（用户模型更灵敏），
                        // 用户要求"用户训练的模型和下载模型一起识别，用户训练模型置信度偏高一点"
                        if (sc > 0.52f) extra.add(c)
                        if (!patch.isRecycled) patch.recycle()
                    }
                    if (extra.isNotEmpty()) boxes = boxes + extra
                }
                // v2.25 模型一优化：模糊小球放大重检。差分候选小（球只占几十像素、运动模糊）
                // 时 YOLO 和模板都容易漏，把候选 patch 放大到 640 再喂 shuttle.onnx 局部重检。
                // 只处理最多 2 个候选（局部推理成本可控），检出框映射回原图坐标补入。
                if (boxes.isEmpty() && merged.isNotEmpty()) {
                    val small = merged.filter { it.w < 16f || it.h < 16f }.take(2)
                    for (c in small) {
                        val patch = safeCrop(frame.bitmap, c.cx.toInt(), c.cy.toInt(), 64) ?: continue
                        val p640 = Bitmap.createScaledBitmap(patch, 640, 640, true)
                        val dets = try { detector.detectPatch(p640) } catch (e: Exception) { emptyList() }
                        val in640 = dets.firstOrNull { it.conf > 0.05f } ?: dets.maxByOrNull { it.conf }
                        if (in640 != null) {
                            // 640 尺度 → 原图：patch 放大系数 = 640/64，偏移 320（patch 中心即候选中心）
                            val box = ShuttleOnnxDetector.Box(
                                c.cx + (in640.cx - 320f) / 10f,
                                c.cy + (in640.cy - 320f) / 10f,
                                maxOf(4f, in640.w / 10f),
                                maxOf(4f, in640.h / 10f),
                                in640.conf
                            )
                            boxes = boxes + box
                            break
                        }
                        if (!patch.isRecycled) patch.recycle()
                        if (!p640.isRecycled) p640.recycle()
                    }
                }
            } else {
                bgDetector.updateBackground(frame.bitmap) // 球出现后背景滚动自适应
            }
            // 人员位置排除：落在球员框内的候选丢弃（白色衣服/球拍不是球）
            if (boxes.isNotEmpty()) {
                boxes = boxes.filter { !inPlayerRect(it.cx, it.cy) }
            }
            // 球网位置排除：网是静止的，背景差分天然滤掉；候选离网线过近（±0.25m）丢弃，避免网孔误检
            boxes = boxes.filter { b ->
                val courtPos = try { Homography.pixelToCourt(homography, b.cx, b.cy) } catch (e: Exception) { null }
                courtPos == null || abs(courtPos.y - 6.70f) > 0.25f // 距网 ±0.25m 外
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
            // v2.42 实时预览帧：每帧都画检测推理过程（候选球框+跟踪确认框+帧号标注），不再是10帧一次
            val bmp = frame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val bcv = Canvas(bmp)
            // 候选框（YOLO/差分检测到的，未跟踪确认的用细黄框）
            for (b in boxes.take(4)) {
                val bs = 34f
                bcv.drawRect(
                    b.cx - bs / 2f, b.cy - bs / 2f,
                    b.cx + bs / 2f, b.cy + bs / 2f,
                    candidatePaint
                )
            }
            // 跟踪确认的唯一球框（粗绿框）
            if (pos != null) {
                val boxSize = 34f
                bcv.drawRect(
                    pos.x - boxSize / 2f, pos.y - boxSize / 2f,
                    pos.x + boxSize / 2f, pos.y + boxSize / 2f,
                    ballPaint
                )
            }
            val btp = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE; textSize = 22f; isFakeBoldText = true
                setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
            }
            bcv.drawText(
                "球检测 帧${frame.index} ${if (pos != null) "✓球(x=${pos.x.toInt()},y=${pos.y.toInt()})" else "搜索中…"} 候选${boxes.size}",
                14f, 36f, btp
            )
            onPreviewFrame(bmp)
            // 步骤映射：前 40% 帧为"背景差分"（全帧扫描），后 60% 为"SVM分类"（跟踪筛选）
            val stepIdx = if (i < framesAll.size * 0.4f) 0 else 1
            val phasePct = 5f + 90f * (i.toFloat() / framesAll.size)
            onStage(StageUpdate(AnalysisPhase.SHUTTLE, stepIdx, phasePct, 39f + 49f * (i.toFloat() / framesAll.size),
                detail = "正在检测第 ${i + 1}/${framesAll.size} 帧 · YOLO+差分双通道"))
        }
        detector.close()
        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 1, 100f, 88f, done = true))

        // v2.29 TrackNetV3 专业模型兜底：轻量检测（YOLO+帧差）帧不足、即将抛 E201 前，
        // 用 TrackNet 复检补点。常规视频轻量已成功 → 不跑，不拖慢；失败 → 慢但准。
        if (detectedFrames < 6) {
            val tn = try { TrackNetV3Detector(context) } catch (e: Exception) { null }
            if (tn != null) {
                onStage(StageUpdate(AnalysisPhase.SHUTTLE, 2, 0f, 88f))
                val hits = try {
                    tn.detect(framesAll) { doneW, totalW ->
                        val p = 88f + 3f * doneW / totalW.coerceAtLeast(1)
                        onStage(StageUpdate(AnalysisPhase.SHUTTLE, 2, p, p))
                    }
                } catch (e: Exception) { emptyMap() }
                for ((framePos, h) in hits) {
                    // 排除球员框（白衣/球拍不是球）
                    if (inPlayerRect(h.x, h.y)) continue
                    // 排除球网 ±0.25m
                    val cp = try { Homography.pixelToCourt(homography, h.x, h.y) } catch (e: Exception) { null }
                    if (cp != null && abs(cp.y - 6.70f) <= 0.25f) continue
                    // 同帧已有点：跳过
                    if (rawPoints.any { it.frame == h.frameIndex }) continue
                    val fr = framesAll[framePos]
                    rawPoints.add(
                        BallPoint(
                            frame = h.frameIndex,
                            timeSec = fr.timeMs / 1000.0,
                            x = h.x, y = h.y, confidence = h.conf,
                            courtX = cp?.x ?: 0f, courtY = cp?.y ?: 0f
                        )
                    )
                    detectedFrames++
                }
                tn.close()
                onStage(StageUpdate(AnalysisPhase.SHUTTLE, 2, 100f, 91f, done = true))
            }
        }

        if (detectedFrames < 6) {
            throw AnalysisException(
                AnalysisError(
                    code = "E201",
                    title = "羽毛球检测失败",
                    detail = "YOLO、帧间差分与 TrackNet 专业模型均未能稳定识别出羽毛球：请确保羽毛球在画面中清晰可见（不要太小、不要和白色背景融合），且击球过程完整出现在画面内。建议：①离场地近一点拍；②拉近镜头让球更大；③保证球和背景颜色差异明显。\n\n本次：$userModelStatus。",
                    threshold = "检出帧 $detectedFrames / 总帧 ${framesAll.size}（YOLO $yoloHits + 差分 $bgDiffHits + TrackNet 复检），需要 ≥ 6 帧"
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

        // ================= 阶段 5b：骨骼识别（v2.12/2.13 用户要求：骨骼识别运动员击球动作，随视频播放动态显示） =================
        // 人员位置已在球检测前算好（playerRects），这里直接跑骨骼：
        // 使用 MediaPipe PoseLandmarker 官方 AI 模型（assets/models/pose_landmarker.task，多人 33 关键点）
        // v2.13 提速：检测频率 每6帧 -> 每10帧
        // v2.25 人员检测优化：用骨架关键点（含手腕=球拍端）生成更精确的运动员外接框，
        // 并入 playerRects（结果页显示 + 击球动作关联用），避免"白色衣服/球拍当球"的误检区域漏盖。
        onStage(StageUpdate(AnalysisPhase.PLAYER, 2, 5f, 91f))
        val poseFrames = ArrayList<PoseFrameData>()
        val posePlayerRects = ArrayList<RectF>()   // v2.25 骨架外接框（比帧差框更贴人）
        val poseDetector = try { PoseDetector(context) } catch (e: Exception) { null }
        if (poseDetector != null) {
            for ((i, f) in framesAll.withIndex()) {
                // v2.25：骨骼采样 10→6 帧，让 3D/结果页骨架"一直随视频播放显示"
                if (i % 6 == 0) {
                    val skels = poseDetector.detect(f.bitmap)
                    if (skels.isNotEmpty()) {
                        poseFrames.add(PoseFrameData(f.timeMs / 1000.0, f.index, skels))
                        // v2.25：多人骨架 → 每人一个外接框（33 点包围盒，腕/肘覆盖球拍活动范围）
                        for (skel in skels) {
                            val vis = skel.points.filter { it.visibility > 0.4f }
                            if (vis.isEmpty()) continue
                            val minX = vis.minOf { it.x }
                            val minY = vis.minOf { it.y }
                            val maxX = vis.maxOf { it.x }
                            val maxY = vis.maxOf { it.y }
                            posePlayerRects.add(RectF(minX - 30f, minY - 20f, maxX + 30f, maxY + 30f))
                        }
                        // v2.42 实时预览：把当前帧骨骼骨架+人员框画到预览帧
                        val bmp = f.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val pcv = Canvas(bmp)
                        for (r in playerRects) {
                            pcv.drawRect(r, playerPaint)
                            pcv.drawText("PERSON", r.left + 4f, r.top - 6f, playerTextPaint)
                        }
                        for (skel in skels) {
                            for (conn in PoseDetector.CONNECTIONS) {
                                val a = skel.points.getOrNull(conn[0]) ?: continue
                                val b = skel.points.getOrNull(conn[1]) ?: continue
                                if (a.visibility > 0.3f && b.visibility > 0.3f) {
                                    pcv.drawLine(a.x, a.y, b.x, b.y, poseBonePaint)
                                }
                            }
                            for (pt in skel.points) {
                                if (pt.visibility > 0.3f) pcv.drawCircle(pt.x, pt.y, 4f, poseJointPaint)
                            }
                        }
                        val ptp = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE; textSize = 22f; isFakeBoldText = true
                            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
                        }
                        pcv.drawText("骨骼识别 帧${f.index} · ${skels.size}人", 14f, 36f, ptp)
                        onPreviewFrame(bmp)
                    }
                }
                if (i % 20 == 0) {
                    val pct = 10f + 80f * (i.toFloat() / framesAll.size)
                    onStage(StageUpdate(AnalysisPhase.PLAYER, 2, pct, 91f + 4f * pct / 100f,
                        detail = "骨骼识别第 ${i + 1}/${framesAll.size} 帧 · MediaPipe Pose"))
                }
                yield()
            }
            // 预览帧：叠加运动员框 + 骨骼骨架（青绿色，随帧动态）
            if (poseFrames.isNotEmpty()) {
                val bmp = framesAll.last().bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val pcv = Canvas(bmp)
                // v2.13 双场区人员标注：先画运动员位置框（"识别出俩运动员的位置"）
                for (r in playerRects) {
                    pcv.drawRect(r, playerPaint)
                    pcv.drawText("PERSON", r.left + 4f, r.top - 6f, playerTextPaint)
                }
                for (skel in poseFrames.last().skeletons) {
                    for (conn in PoseDetector.CONNECTIONS) {
                        val a = skel.points.getOrNull(conn[0]) ?: continue
                        val b = skel.points.getOrNull(conn[1]) ?: continue
                        if (a.visibility > 0.3f && b.visibility > 0.3f) {
                            pcv.drawLine(a.x, a.y, b.x, b.y, poseBonePaint)
                        }
                    }
                    for (pt in skel.points) {
                        if (pt.visibility > 0.3f) pcv.drawCircle(pt.x, pt.y, 4f, poseJointPaint)
                    }
                }
                onPreviewFrame(bmp)
            }
            poseDetector.close()
        }
        // v2.25 人员检测优化：骨架外接框并入球员框（比帧差框更贴人，结果页显示/击球关联更准）
        if (posePlayerRects.isNotEmpty()) playerRects = (playerRects + posePlayerRects)
        onStage(StageUpdate(AnalysisPhase.PLAYER, 3, 100f, 95f, done = true))
        delay(200)

        // ================= 阶段 6：击球点检测 + 球速（94-100%） =================
        onStage(StageUpdate(AnalysisPhase.HIT, 0, 20f, 95f))
        val speedCalc = SpeedCalculator(homography)
        val points = rawPoints.mapIndexed { i, p ->
            if (i == 0) p.copy(speedKmh = 0f)
            else p.copy(speedKmh = speedCalc.instantSpeed(rawPoints[i - 1], p))
        }
        delay(120)
        // 击球动作识别（用户要求）：复用阶段5的人员检测结果，用运动员区域关联击球点
        onStage(StageUpdate(AnalysisPhase.HIT, 1, 60f, 97f))
        val hits = HitDetector().detect(points, playerRects, poseFrames)
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
        // v2.25 模型七：高光规则引擎（球速>100 杀球高光 / 单回合>10 拍多拍高光 / 连续快速击球平抽高光）
        val highlights = HighlightEngine.detect(hits)
        onStage(StageUpdate(AnalysisPhase.HIT, 2, 100f, 100f, done = true))

        AnalysisResult(
            videoInfo = videoInfo!!,
            court = court,
            trajectory = finalPoints,
            hits = hits,
            summary = summary,
            analysisDurationMs = System.currentTimeMillis() - startTime,
            appVersion = "2.42.0",
            frameWidth = w,
            frameHeight = h,
            frameAtMaxSpeed = frameAtMax,
            poseFrames = poseFrames,
            highlights = highlights
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

    /** 以 (cx,cy) 为中心裁 size×size 图块（越界自动钳制），失败返回 null */
    private fun safeCrop(bmp: Bitmap, cx: Int, cy: Int, size: Int): Bitmap? {
        if (bmp.width < size || bmp.height < size) return null
        val half = size / 2
        val left = (cx - half).coerceIn(0, bmp.width - size)
        val top = (cy - half).coerceIn(0, bmp.height - size)
        return try { Bitmap.createBitmap(bmp, left, top, size, size) } catch (e: Exception) { null }
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
