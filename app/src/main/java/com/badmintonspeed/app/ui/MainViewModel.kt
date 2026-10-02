package com.badmintonspeed.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badmintonspeed.app.analysis.VideoAnalyzer
import com.badmintonspeed.app.analysis.VideoFrameExtractor
import com.badmintonspeed.app.data.HistoryRepository
import com.badmintonspeed.app.data.ResultJson
import com.badmintonspeed.app.data.SettingsRepository
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.PerformanceMode
import com.badmintonspeed.app.domain.SpeedUnit
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
    data class RecordDetail(val record: AnalysisRecord) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val historyRepo = HistoryRepository(context)
    val settings = SettingsRepository(context)

    // 页面
    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen

    // 视频与标定
    private val _videoFile = MutableStateFlow<File?>(null)
    val videoFile: StateFlow<File?> = _videoFile
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap
    private val _corners = MutableStateFlow<List<PointF>>(emptyList())
    val corners: StateFlow<List<PointF>> = _corners

    // 分析进度
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    private val _stage = MutableStateFlow("")
    val stage: StateFlow<String> = _stage

    // 结果与错误
    private val _result = MutableStateFlow<AnalysisResult?>(null)
    val result: StateFlow<AnalysisResult?> = _result
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // 历史
    private val _records = MutableStateFlow<List<AnalysisRecord>>(emptyList())
    val records: StateFlow<List<AnalysisRecord>> = _records

    private var analyzeJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    fun goTo(screen: Screen) {
        _screen.value = screen
    }

    /** 用户选择视频后：复制到私有目录并生成标定预览帧 */
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
                    val preview = VideoFrameExtractor().getFrame(target, 0L, 720)
                    _videoFile.value = target
                    _previewBitmap.value = preview
                    _corners.value = emptyList()
                    true
                }.getOrElse { e ->
                    _error.value = "视频导入失败：${e.message}"
                    false
                }
            }
            if (ok) _screen.value = Screen.Calibrate
        }
    }

    fun addCorner(p: PointF) {
        val cur = _corners.value
        if (cur.size >= 4) return
        _corners.value = cur + p
    }

    fun undoCorner() {
        val cur = _corners.value
        if (cur.isNotEmpty()) _corners.value = cur.dropLast(1)
    }

    fun clearCorners() {
        _corners.value = emptyList()
    }

    /** 启动分析（MVP 流水线） */
    fun startAnalysis() {
        val file = _videoFile.value ?: run {
            _error.value = "请先选择视频"; return
        }
        val cr = _corners.value
        if (cr.size != 4) {
            _error.value = "请先在画面上点击标定场地四角（左上→右上→右下→左下）"
            return
        }
        cancelFlag.set(false)
        _progress.value = 0f
        _stage.value = "准备中"
        _screen.value = Screen.Analyzing

        analyzeJob = viewModelScope.launch {
            try {
                val fps = settings.performanceMode.analysisFps
                val threshold = settings.brightThreshold
                val result = VideoAnalyzer().analyze(file, cr, fps, threshold) { p, s ->
                    _progress.value = p
                    _stage.value = s
                }
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
            } catch (e: Exception) {
                if (!cancelFlag.get()) {
                    _error.value = e.message ?: "分析失败，请重试"
                    _screen.value = Screen.Home
                }
            }
        }
    }

    fun cancelAnalysis() {
        cancelFlag.set(true)
        analyzeJob?.cancel()
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
