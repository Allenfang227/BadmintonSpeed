package com.badmintonspeed.app.data

import android.graphics.PointF
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.HitAnalysis
import org.json.JSONArray
import org.json.JSONObject

/** AnalysisResult <-> JSON（用于历史记录持久化与详情展示） */
object ResultJson {

    fun encode(result: AnalysisResult): String {
        val hits = JSONArray()
        for (h in result.hits) {
            hits.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("frame", h.frameIndex)
                    put("time", h.timeSeconds)
                    put("type", h.hitType.name)
                    put("maxSpeed", h.maxSpeedKmh)
                    put("avgSpeed", h.avgSpeedKmh)
                    put("angle", h.angleDeg)
                }
            )
        }
        val summary = result.summary
        return JSONObject().apply {
            put("maxSpeed", summary.maxSpeedKmh)
            put("avgSpeed", summary.avgSpeedKmh)
            put("totalHits", summary.totalHits)
            put("smashCount", summary.smashCount)
            put("trajectoryPoints", result.trajectory.size)
            put("videoDurationMs", result.videoInfo.durationMs)
            put("fps", result.videoInfo.fps)
            put("analysisDurationMs", result.analysisDurationMs)
            put("hits", hits)
        }.toString()
    }

    data class Detail(
        val maxSpeedKmh: Float,
        val avgSpeedKmh: Float,
        val totalHits: Int,
        val smashCount: Int,
        val trajectoryPoints: Int,
        val hits: List<Pair<String, Float>>  // 类型名 -> 速度
    )

    fun decode(json: String): Detail? = runCatching {
        val o = JSONObject(json)
        val arr = o.getJSONArray("hits")
        val hits = (0 until arr.length()).map { i ->
            val h = arr.getJSONObject(i)
            h.getString("type") to h.getDouble("maxSpeed").toFloat()
        }
        Detail(
            maxSpeedKmh = o.getDouble("maxSpeed").toFloat(),
            avgSpeedKmh = o.getDouble("avgSpeed").toFloat(),
            totalHits = o.getInt("totalHits"),
            smashCount = o.getInt("smashCount"),
            trajectoryPoints = o.getInt("trajectoryPoints"),
            hits = hits
        )
    }.getOrNull()
}
