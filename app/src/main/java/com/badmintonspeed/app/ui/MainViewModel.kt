package com.badmintonspeed.app.ui

import android.app.Application
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
    fun startAnalysis(manualCourtCorners: List<PointF>? = null) {
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
                    onCourt = { c -> _courtResult.value = c }
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
    fun submitManualCourtCorners(corners: List<PointF>) {
        _calibrationFrame.value = null
        _screen.value = Screen.Analyzing
        startAnalysis(manualCourtCorners = corners)
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
