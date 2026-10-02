package com.badmintonspeed.app.analysis

import com.badmintonspeed.app.domain.BallPoint
import kotlin.math.hypot

/**
 * 球速计算器：
 * 1. 相邻帧球心像素坐标 -> 透视变换为场地坐标（米）
 * 2. 位移 / 帧间隔时间 = 速度
 * 3. 移动平均平滑，减少检测抖动
 */
class SpeedCalculator(
    private val homography: FloatArray,
    private val smoothingWindow: Int = 5,
    private val minSpeedKmh: Float = 6f
) {

    private val speedHistory = ArrayDeque<Float>()

    /** 计算两点之间的瞬时速度（km/h），并做移动平均平滑 */
    fun instantSpeed(prev: BallPoint, cur: BallPoint): Float {
        val prevCourt = Homography.pixelToCourt(homography, prev.x, prev.y)
        val curCourt = Homography.pixelToCourt(homography, cur.x, cur.y)
        val dist = hypot((curCourt.x - prevCourt.x).toDouble(), (curCourt.y - prevCourt.y).toDouble()).toFloat()
        val dt = (cur.timeSec - prev.timeSec).toFloat()
        if (dt <= 0.001f) return 0f
        val mps = dist / dt
        val rawKmh = mps * 3.6f
        return smooth(rawKmh)
    }

    private fun smooth(speed: Float): Float {
        speedHistory.addLast(speed)
        if (speedHistory.size > smoothingWindow) speedHistory.removeFirst()
        val avg = speedHistory.average().toFloat()
        return if (avg < minSpeedKmh) 0f else avg
    }

    fun reset() {
        speedHistory.clear()
    }
}
