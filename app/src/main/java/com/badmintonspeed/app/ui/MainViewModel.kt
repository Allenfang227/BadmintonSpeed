package com.badmintonspeed.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badmintonspeed.app.analysis.VideoAnalyzer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 页面 */
sealed interface Screen {
    object Home : Screen
    object Calibrate : Screen
    object RoiSelect : Screen
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

    // 结果与错误（错误带错误码，对应不同失败原因）
    private val _result = MutableStateFlow<AnalysisResult?>(null)
    val result: StateFlow<AnalysisResult?> = _result
    private val _error = MutableStateFlow<AnalysisError?>(null)
    val error: StateFlow<AnalysisError?> = _error

    // 手动标定：ABC自动检测全失败后，携带第一帧供用户点4个角点（融合自 AI-YuJian-AI）
    private val _calibrationFrame = MutableStateFlow<Bitmap?>(null)
    val calibrationFrame: StateFlow<Bitmap?> = _calibrationFrame
    private var pendingManualCorners: List<PointF>? = null

    // 历史
    private val _records = MutableStateFlow<List<AnalysisRecord>>(emptyList())
    val records: StateFlow<List<AnalysisRecord>> = _records

    private var analyzeJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    fun goTo(screen: Screen) {
        _screen.value = screen
    }

    /** 用户选择视频后：复制到私有目录，直接进入分析（场地由 AI 自动标定） */
    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "video_${System.currentTimeMillis()}.mp4"
                    val safeName = name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
                    val videosDir = File(context.filesDir, "videos").apply { mkdirs() }
                    val target = File(videosDir, safeName)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("无法读取所选视频")
                    input.use { ins ->
                        target.outputStream().use { out -> ins.copyTo(out) }
                    }
                    _videoFile.value = target
                    true
                }.getOrElse { e ->
                    _error.value = AnalysisError(
                        code = "E000",
                        title = "视频导入失败",
                        detail = e.message ?: "无法读取所选视频",
                        threshold = "需要可读的视频文件（mp4/3gp 等）"
                    )
                    false
                }
            }
            if (ok) startAnalysis()
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
        // v2.17：ROI 优先用本次传入；未传入时读上次同机位保存的（固定机位只框一次）
        val roi = roiPolygon ?: loadRoi()

        analyzeJob = viewModelScope.launch {
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
                    roiPolygon = roi
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
            }
        }
    }

    fun cancelAnalysis() {
        cancelFlag.set(true)
        analyzeJob?.cancel()
        _previewFrame.value = null
        _screen.value = Screen.Home
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
     *  每次人工标注自动进入本地训练集（labels/ 目录），供 train_keypoints.py 增量训练。 */
    fun submitManualCourtCorners(corners: List<PointF>) {
        exportCourtLabel(corners)
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
            val dir = File(context.filesDir, "labels").apply { mkdirs() }
            // 保存标定底图
            var imgName = "frame_${System.currentTimeMillis()}.jpg"
            if (bmp != null) {
                val f = File(dir, imgName)
                f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            }
            val json = """{"image":"$imgName","W":$W,"H":$H,"points":[$pts]}"""
            File(dir, "court_${System.currentTimeMillis()}.json").writeText(json)
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
