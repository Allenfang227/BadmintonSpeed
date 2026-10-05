package com.badmintonspeed.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badmintonspeed.app.analysis.CourtLearner
import com.badmintonspeed.app.analysis.VideoAnalyzer
import com.badmintonspeed.app.analysis.VideoFrameExtractor
import com.badmintonspeed.app.data.CourtModelRepo
import com.badmintonspeed.app.data.HistoryRepository
import com.badmintonspeed.app.data.ResultJson
import com.badmintonspeed.app.data.SettingsRepository
import com.badmintonspeed.app.analysis.Homography
import com.badmintonspeed.app.analysis.StandardCourt
import com.badmintonspeed.app.domain.AnalysisError
import com.badmintonspeed.app.domain.CourtResult
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.PerformanceMode
import com.badmintonspeed.app.domain.SpeedUnit
import com.badmintonspeed.app.domain.StageUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 页面 */
sealed interface Screen {
    object Home : Screen
    object CalibrateMode : Screen  // v2.36：场地标定模式选择（手工/AI）
    object Calibrate : Screen
    object RoiSelect : Screen
    object TrainMode : Screen
    object ModelFiles : Screen
    object Analyzing : Screen
    object Result : Screen
    object History : Screen
    object Settings : Screen
    object Tutorial : Screen
    object Mine : Screen
    object About : Screen
    data class RecordDetail(val record: AnalysisRecord) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val historyRepo = HistoryRepository(context)
    val settings = SettingsRepository(context)

    // 页面
    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen

    // 视频
    private val _videoFile = MutableStateFlow<File?>(null)
    val videoFile: StateFlow<File?> = _videoFile

    // 分析进度（分模块步骤）
    private val _stage = MutableStateFlow<StageUpdate?>(null)
    val stage: StateFlow<StageUpdate?> = _stage
    private val _analysisStartMs = MutableStateFlow(0L)
    val analysisStartMs: StateFlow<Long> = _analysisStartMs

    // 分析中的实时预览帧（带检测框）
    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame
    // v2.12：场地标定结果常驻（分析中实时更新，UI 层黄线一直叠加不闪）
    private val _courtResult = MutableStateFlow<CourtResult?>(null)
    val courtResult: StateFlow<CourtResult?> = _courtResult

    // v2.37 超线程实时悬浮窗：可开关 + 每秒刷新 CPU/线程/NPU/内存
    private val _showPerfOverlay = MutableStateFlow(false)
    val showPerfOverlay: StateFlow<Boolean> = _showPerfOverlay
    private val _perfInfo = MutableStateFlow("")
    val perfInfo: StateFlow<String> = _perfInfo
    private var perfJob: kotlinx.coroutines.Job? = null
    fun togglePerfOverlay() {
        _showPerfOverlay.value = !_showPerfOverlay.value
        if (_showPerfOverlay.value) startPerfMonitor() else stopPerfMonitor()
    }
    private fun startPerfMonitor() {
        stopPerfMonitor()
        perfJob = viewModelScope.launch {
            while (true) {
                val rt = Runtime.getRuntime()
                val cores = rt.availableProcessors()
                val threads = Thread.activeCount()
                val usedMem = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                val maxMem = rt.maxMemory() / 1024 / 1024
                val npu = try {
                    Class.forName("com.badmintonspeed.app.analysis.OrtSessions")
                        .getDeclaredField("NpuSupport").apply { isAccessible = true }
                        .let { f ->
                            val obj = f.get(null)
                            obj?.javaClass?.getDeclaredField("working")?.apply { isAccessible = true }?.getBoolean(obj) == true
                        }
                } catch (_: Exception) { false }
                val intra = try {
                    Class.forName("com.badmintonspeed.app.analysis.OrtSessions")
                        .getDeclaredMethod("getIntraThreads").invoke(null) as Int
                } catch (_: Exception) { cores.coerceAtMost(8) }
                _perfInfo.value = "CPU ${cores}核(SMT) · 推理${intra}线程 · 活跃${threads}线程\nNPU:${if (npu) "NNAPI加速" else "CPU"} · 内存${usedMem}/${maxMem}MB"
                delay(1000)
            }
        }
    }
    private fun stopPerfMonitor() { perfJob?.cancel(); perfJob = null }

    // 结果与错误（错误带错误码，对应不同失败原因）
    private val _result = MutableStateFlow<AnalysisResult?>(null)
    val result: StateFlow<AnalysisResult?> = _result
    private val _error = MutableStateFlow<AnalysisError?>(null)
    val error: StateFlow<AnalysisError?> = _error

    // 手动标定：ABC自动检测全失败后，携带第一帧供用户点4个角点（融合自 AI-YuJian-AI）
    private val _calibrationFrame = MutableStateFlow<Bitmap?>(null)
    val calibrationFrame: StateFlow<Bitmap?> = _calibrationFrame
    private var pendingManualCorners: List<PointF>? = null

    // v2.27.3：失败重试帧缓存（同一视频已解码帧 + 路径指纹，重标定后跳过重新转格式）
    private var _lastFrames: List<VideoFrameExtractor.AnalyzedFrame>? = null
    private var _lastFramesVideo: String? = null

    // 历史
    private val _records = MutableStateFlow<List<AnalysisRecord>>(emptyList())
    val records: StateFlow<List<AnalysisRecord>> = _records

    private var analyzeJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    // v2.30：分析是否进行中（误返回/切后台后可"继续当前分析"，任务不丢）
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing

    fun goTo(screen: Screen) {
        _screen.value = screen
    }

    /**
     * 用户选择视频后 v2.30：复制到私有目录 → 先取第一帧做"前置场地标定"。
     *  - 同机位已有持久化标定（court_calib）→ 直接复用，不打扰用户；
     *  - 没有 → 先进 4 角标定页，标定结果存入训练集后立即开始处理视频。
     * 不再"先转完整视频、识别失败后才要求标定、又重转一遍"，省去重复等待。
     */
    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "video_${System.currentTimeMillis()}.mp4"
                    val safeName = name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                    val videosDir = File(context.filesDir, "videos").apply { mkdirs() }
                    val t = File(videosDir, safeName)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("无法读取所选视频")
                    input.use { ins -> t.outputStream().use { out -> ins.copyTo(out) } }
                    t
                }.getOrElse { e ->
                    _error.value = AnalysisError(
                        code = "E000",
                        title = "视频导入失败",
                        detail = e.message ?: "无法读取所选视频",
                        threshold = "需要可读的视频文件（mp4/3gp 等）"
                    )
                    null
                }
            }
            if (target == null) { _screen.value = Screen.Home; return@launch }
            _videoFile.value = target

            // 提取第一帧作为标定底图（分析分辨率 maxDimension=960，与后续解码口径一致）
            val firstFrame = withContext(Dispatchers.IO) {
                runCatching { VideoFrameExtractor().getFrame(target, 0L, 960) }.getOrNull()
            }
            if (firstFrame == null) {
                // 第一帧都提不出：视频可能损坏/过短，直接进分析由 E001 判定
                startAnalysis()
                return@launch
            }
            // v2.36：进入场地标定模式选择页（手工标注 / AI自动标注），不自动进标定
            _calibrationFrame.value = firstFrame
            _screen.value = Screen.CalibrateMode
        }
    }

    /** v2.36：用户选择场地标定模式 */
    fun onSelectCalibrateMode(mode: String) {
        when (mode) {
            "manual" -> _screen.value = Screen.Calibrate  // 手工标注：进四角标定页，标完直接套模板
            "ai" -> startAnalysis()  // AI自动标注：直接开始分析，AI检测场地+颜色校验，无需人工
        }
    }

    /** 启动分析（AI 自动标定场地 + YOLO11 真实检测，分模块实时进度）；manualCourtCorners 非空时跳过自动检测直接用手动角点 */
    fun startAnalysis(manualCourtCorners: List<PointF>? = null, roiPolygon: List<PointF>? = null) {
        val file = _videoFile.value ?: run {
            _error.value = AnalysisError("E000", "提示", "请先选择视频", "需要选择视频后开始测速")
            return
        }
        cancelFlag.set(false)
        _stage.value = null
        _previewFrame.value = null
        _courtResult.value = null
        _analysisStartMs.value = System.currentTimeMillis()
        _screen.value = Screen.Analyzing
        // v2.30：已用 4 角明确标定场地时不再叠加旧 ROI（二者作用重复、易冲突）；
        // 仅 AI 自动检测（无手动角点）时才读上次同机位保存的 ROI。
        val roi = roiPolygon ?: if (manualCourtCorners == null) loadRoi() else null
        // v2.27.3：失败重试（手动标定后）复用同一视频已解码帧，跳过重新转格式
        val reuse = if (manualCourtCorners != null && _lastFramesVideo == file.absolutePath) _lastFrames else null

        analyzeJob = viewModelScope.launch {
            _isAnalyzing.value = true
            try {
                val fps = settings.performanceMode.analysisFps
                val result = VideoAnalyzer().analyze(
                    context = context,
                    videoFile = file,
                    analysisFps = fps,
                    onStage = { s -> _stage.value = s },
                    onPreviewFrame = { bmp ->
                        // 不主动 recycle：Compose 可能仍引用旧帧，交给 GC 回收
                        _previewFrame.value = bmp
                    },
                    manualCourtCorners = manualCourtCorners,
                    onCourt = { c -> _courtResult.value = c },
                    roiPolygon = roi,
                    reuseFrames = reuse,
                    onFrames = { fr ->
                        // 内存保护：≤900 帧且估算 ≤600MB 才缓存（够重试用，防 OOM）
                        if (fr.size in 8..900) {
                            val est = fr.sumOf { f -> f.bitmap.width.toLong() * f.bitmap.height * 4L }
                            if (est <= 600L * 1024 * 1024) {
                                _lastFrames = fr
                                _lastFramesVideo = file.absolutePath
                            }
                        }
                    }
                )
                _result.value = result

                // 自动保存历史
                val record = AnalysisRecord(
                    id = HistoryRepository.newId(),
                    title = HistoryRepository.defaultTitle(System.currentTimeMillis(), file.name),
                    videoName = file.name,
                    createdAt = System.currentTimeMillis(),
                    maxSpeedKmh = result.summary.maxSpeedKmh,
                    totalHits = result.summary.totalHits,
                    smashCount = result.summary.smashCount,
                    resultJson = ResultJson.encode(result)
                )
                historyRepo.save(record)
                loadHistory()
                _screen.value = Screen.Result
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: VideoAnalyzer.AnalysisException) {
                if (!cancelFlag.get()) {
                    // v2.15：E101 建议手动标定兜底时，保留当前预览帧作为标定底图
                    if (e.error.suggestManual && _previewFrame.value != null) {
                        _calibrationFrame.value = _previewFrame.value
                    }
                    _error.value = e.error
                    _screen.value = Screen.Home
                }
            } catch (e: Exception) {
                if (!cancelFlag.get()) {
                    _error.value = AnalysisError(
                        code = "E999",
                        title = "分析失败",
                        detail = e.message ?: "未知错误，请重试",
                        threshold = "请重试或更换视频"
                    )
                    _screen.value = Screen.Home
                }
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun cancelAnalysis() {
        cancelFlag.set(true)
        analyzeJob?.cancel()
        _previewFrame.value = null
        _screen.value = Screen.Home
    }

    /** v2.30 后台运行：不取消分析，仅回到首页（分析继续在跑），首页可"继续当前分析" */
    fun minimizeAnalysis() {
        if (_isAnalyzing.value) _screen.value = Screen.Home
    }

    /** v2.30 回到分析页继续（分析任务仍在运行，进度不丢） */
    fun resumeAnalysis() {
        if (_isAnalyzing.value) _screen.value = Screen.Analyzing
    }

    /** 用户手动标定完4个角点后，用这些角点继续分析（跳过自动场地检测） */
    /** v2.15：E101 弹窗"手动标定"按钮 → 进入四角拖拽标定（AI 优先 + 人工兜底） */
    fun retryWithManualCalibration() {
        _error.value = null
        _screen.value = Screen.Calibrate
    }

    /** v2.17：E101 弹窗"ROI 框选"按钮 → 进入多边形框选（B误检修复：把邻场线/广告拦在本场外） */
    fun retryWithRoiSelect() {
        _error.value = null
        if (_calibrationFrame.value == null) _calibrationFrame.value = _previewFrame.value
        _screen.value = Screen.RoiSelect
    }

    /** ROI 多边形完成后：持久化 + 直接带 ROI 重新分析 */
    fun submitRoi(roi: List<PointF>) {
        saveRoi(roi)
        _screen.value = Screen.Analyzing
        startAnalysis(roiPolygon = roi)
    }

    private fun loadRoi(): List<PointF>? {
        return try {
            val raw = context.getSharedPreferences("roi", Context.MODE_PRIVATE).getString("poly", null) ?: return null
            val arr = raw.split(";").filter { it.isNotBlank() }.map {
                val xy = it.split(",")
                PointF(xy[0].toFloat(), xy[1].toFloat())
            }
            if (arr.size >= 3) arr else null
        } catch (e: Exception) { null }
    }

    private fun saveRoi(roi: List<PointF>) {
        try {
            val raw = roi.joinToString(";") { "${it.x},${it.y}" }
            context.getSharedPreferences("roi", Context.MODE_PRIVATE).edit().putString("poly", raw).apply()
        } catch (e: Exception) {
            // 持久化失败不影响本次分析
        }
    }

    /** v2.16：手动标定确认后，把 4 角映射成 12 个场地交点，导出成训练 json。
     *  每次人工标注自动进入本地训练集（labels/ 目录），供 train_keypoints.py 增量训练。
     *  v2.32：补上 CourtLearner.save —— 把 4 角持久化到公共 court_calib/latest.json，
     *  下次同机位上传视频自动复用，不再每次重标（此前漏存，导致"标了跟没标一样"）。 */
    fun submitManualCourtCorners(corners: List<PointF>) {
        exportCourtLabel(corners)
        val bmp = _calibrationFrame.value
        if (bmp != null) {
            runCatching { CourtLearner(context).save(corners, bmp.width, bmp.height) }
        }
        _calibrationFrame.value = null
        _screen.value = Screen.Analyzing
        startAnalysis(manualCourtCorners = corners)
    }

    private fun exportCourtLabel(corners: List<PointF>) {
        if (corners.size != 4) return
        try {
            val h = Homography.compute(corners, StandardCourt.corners) ?: return
            val bmp = _calibrationFrame.value
            val W = (bmp?.width ?: 1920).toFloat()
            val H = (bmp?.height ?: 1080).toFloat()
            // BWF 12 交点（米制，与 tools/train_keypoints.py 的 TEMPLATE 一致）
            val template = listOf(
                "tl_out" to Pair(0.00f, 0.00f), "tr_out" to Pair(6.10f, 0.00f),
                "br_out" to Pair(6.10f, 13.40f), "bl_out" to Pair(0.00f, 13.40f),
                "net_l" to Pair(0.00f, 6.70f), "net_r" to Pair(6.10f, 6.70f),
                "servl_tl" to Pair(0.46f, 4.72f), "servl_tr" to Pair(5.64f, 4.72f),
                "servl_bl" to Pair(0.46f, 12.64f), "servl_br" to Pair(5.64f, 12.64f),
                "mid_t" to Pair(3.05f, 4.72f), "mid_b" to Pair(3.05f, 12.64f)
            )
            val pts = StringBuilder()
            var first = true
            for ((name, m) in template) {
                val p = Homography.courtToImage(h, m.first, m.second)
                if (p.x < -0.05f * W || p.x > 1.05f * W || p.y < -0.05f * H || p.y > 1.05f * H) continue
                if (!first) pts.append(",")
                pts.append("""{"name":"$name","x":${p.x.toInt()},"y":${p.y.toInt()}}""")
                first = false
            }
            val dir = CourtModelRepo.labelsDir(context)
            // 保存标定底图
            var imgName = "frame_${System.currentTimeMillis()}.jpg"
            if (bmp != null) {
                val f = File(dir, imgName)
                f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            }
            val json = """{"image":"$imgName","W":$W,"H":$H,"points":[$pts]}"""
            File(dir, "court_${System.currentTimeMillis()}.json").writeText(json)
            // 同步到公共目录（用户可在文件管理器找到/转发）
            CourtModelRepo.exportToPublic(context, "labels")
        } catch (e: Exception) {
            // 导出失败不影响测速主流程
        }
    }

    fun cancelCalibration() {
        _calibrationFrame.value = null
        _screen.value = Screen.Home
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _records.value = historyRepo.list()
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.delete(id)
            _records.value = historyRepo.list()
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        settings.speedUnit = unit
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        settings.performanceMode = mode
    }

    fun setBrightThreshold(v: Int) {
        settings.brightThreshold = v
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()
}
