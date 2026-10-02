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
    val isSmash: Boolean = false
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
    val frameAtMaxSpeed: Int
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

/** 性能模式：决定分析帧率 */
enum class PerformanceMode(val displayName: String, val analysisFps: Int) {
    BATTERY_SAVER("省电模式", 15),
    BALANCED("均衡模式", 30),
    TURBO("极速模式", 60)
}

object SpeedUnitConverter {
    fun fromKmh(kmh: Float, unit: SpeedUnit): Float = when (unit) {
        SpeedUnit.KMH -> kmh
        SpeedUnit.MPH -> kmh * 0.621371f
        SpeedUnit.MPS -> kmh / 3.6f
    }
}
