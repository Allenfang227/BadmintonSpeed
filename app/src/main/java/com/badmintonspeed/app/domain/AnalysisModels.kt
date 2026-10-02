package com.badmintonspeed.app.domain

import android.graphics.PointF

/** 视频信息 */
data class VideoInfo(
    val path: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val totalFrames: Int
)

/** 标准羽毛球场尺寸（米）：双打场地 6.10m x 13.40m */
object CourtDimensions {
    const val WIDTH_M = 6.10f
    const val LENGTH_M = 13.40f
}

/** 场地标定结果：像素角点 + 像素->场地坐标的透视矩阵（3x3 行主序） */
data class CourtResult(
    val cornersPx: List<PointF>,
    val homography: FloatArray
)

/** 击球类型 */
enum class HitType(val displayName: String) {
    SMASH("杀球"),
    CLEAR("高远球"),
    DROP("吊球"),
    DRIVE("平抽球"),
    UNKNOWN("未知")
}

/** 球轨迹点 */
data class BallPoint(
    val frame: Int,
    val timeSec: Double,
    val x: Float,
    val y: Float,
    val confidence: Float,
    val courtX: Float,
    val courtY: Float,
    val speedKmh: Float? = null,
    val isSmash: Boolean = false,
    val zMeters: Float = 0f,   // 景深高度（v2.12：球离地高度，空间立体识别）
    val groundX: Float = 0f,   // 球在地面的投影（court 坐标 x，落点判定用）
    val groundY: Float = 0f    // 球在地面的投影（court 坐标 y，落点判定用）
)

/** 单次击球分析 */
data class HitAnalysis(
    val id: String,
    val frameIndex: Int,
    val timeSeconds: Double,
    val hitType: HitType,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val angleDeg: Float,
    val trajectory: List<BallPoint>
)

/** 汇总统计 */
data class AnalysisSummary(
    val totalHits: Int,
    val smashCount: Int,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val fastestHitId: String?
)

/** 一次完整分析的结果 */
/** 某一时刻的多人骨骼快照（结果页随播放进度动态叠加） */
data class PoseFrameData(
    val timeSec: Double,
    val frame: Int,
    val skeletons: List<com.badmintonspeed.app.analysis.PoseSkeleton>
)

data class AnalysisResult(
    val videoInfo: VideoInfo,
    val court: CourtResult?,
    val trajectory: List<BallPoint>,
    val hits: List<HitAnalysis>,
    val summary: AnalysisSummary,
    val analysisDurationMs: Long,
    val appVersion: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameAtMaxSpeed: Int,
    val poseFrames: List<PoseFrameData> = emptyList()
)

/** 持久化的历史记录 */
data class AnalysisRecord(
    val id: String,
    val title: String,
    val videoName: String,
    val createdAt: Long,
    val maxSpeedKmh: Float,
    val totalHits: Int,
    val smashCount: Int,
    val resultJson: String
)

/** 速度单位 */
enum class SpeedUnit(val displayName: String) {
    KMH("km/h"),
    MPH("mph"),
    MPS("m/s")
}

/** 性能模式：决定分析帧率（手机本地 ONNX 推理受 CPU 限制，5/10/15fps 采样即可完整还原轨迹） */
enum class PerformanceMode(val displayName: String, val analysisFps: Int) {
    BATTERY_SAVER("省电模式", 5),
    BALANCED("均衡模式", 10),
    TURBO("极速模式", 15)
}

/**
 * 分析阶段（对应图2/图3 分模块进度面板）。
 * 每个阶段包含细分步骤，分析时逐步骤实时上报。
 */
enum class AnalysisPhase(val title: String, val steps: List<String>) {
    COURT(
        "场地基准检测",
        listOf("Canny边缘检测", "霍夫直线变换", "RANSAC迭代拟合", "单应性矩阵计算")
    ),
    SHUTTLE(
        "羽毛球检测",
        listOf("背景差分", "SVM分类")
    ),
    PLAYER(
        "人员检测",
        listOf("HOG特征提取", "区域扫描", "SVM判定", "人员ID赋值")
    ),
    HIT(
        "击球点检测",
        listOf("击球时刻定位", "球速计算", "击球类型判定")
    )
}

/** 分析阶段实时更新（驱动进度面板） */
data class StageUpdate(
    val phase: AnalysisPhase,
    val stepIndex: Int,        // 当前执行步骤序号（0 起）
    val phasePercent: Float,   // 本模块进度 0-100
    val totalPercent: Float,   // 总体进度 0-100
    val done: Boolean = false // 模块是否完成
)

/**
 * 分析错误（带错误码，对应不同的失败原因）：
 *   E001 视频解码失败/过短
 *   E002 重复帧过多（视频几乎静止，无法测速）
 *   E101 场地检测失败（未找到足够场地线）
 *   E201 羽毛球检测帧数不足（未稳定检测到羽毛球轨迹）
 *   E202 轨迹点数不足（跟丢严重）
 */
data class AnalysisError(
    val code: String,
    val title: String,
    val detail: String,
    val threshold: String // 判定阈值/输出说明
) {
    val display: String
        get() = "[$code] $title\n$detail\n（判定阈值：$threshold）"
}

object SpeedUnitConverter {
    fun fromKmh(kmh: Float, unit: SpeedUnit): Float = when (unit) {
        SpeedUnit.KMH -> kmh
        SpeedUnit.MPH -> kmh * 0.621371f
        SpeedUnit.MPS -> kmh / 3.6f
    }
}
