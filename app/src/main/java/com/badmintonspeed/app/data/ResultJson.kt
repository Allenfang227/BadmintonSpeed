package com.badmintonspeed.app.data

import android.graphics.PointF
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.AnalysisSummary
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.CourtResult
import com.badmintonspeed.app.domain.Highlight
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.domain.HitType
import com.badmintonspeed.app.domain.PoseFrameData
import com.badmintonspeed.app.domain.VideoInfo
import com.badmintonspeed.app.analysis.PoseKeyPoint
import com.badmintonspeed.app.analysis.PoseSkeleton
import org.json.JSONArray
import org.json.JSONObject

/** AnalysisResult <-> JSON（用于历史记录持久化与详情/回放） */
object ResultJson {

    // ================= 旧版摘要（向后兼容旧历史记录） =================

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

    // ================= v2.43 完整结果（历史回放：视频+轨迹+球速+3D+IN/OUT+骨骼） =================

    /** 编码完整 AnalysisResult。videoPathOverride：持久化时视频的实际保存路径（filesDir/videos/xx） */
    fun encodeFull(result: AnalysisResult, videoPathOverride: String? = null): String {
        val o = JSONObject()
        o.put("format", "full_v1")
        o.put("appVersion", result.appVersion)
        o.put("analysisDurationMs", result.analysisDurationMs)
        o.put("frameWidth", result.frameWidth)
        o.put("frameHeight", result.frameHeight)
        o.put("frameAtMaxSpeed", result.frameAtMaxSpeed)

        // videoInfo
        val vi = JSONObject().apply {
            put("path", videoPathOverride ?: result.videoInfo.path)
            put("durationMs", result.videoInfo.durationMs)
            put("width", result.videoInfo.width)
            put("height", result.videoInfo.height)
            put("fps", result.videoInfo.fps.toDouble())
            put("totalFrames", result.videoInfo.totalFrames)
        }
        o.put("videoInfo", vi)

        // court
        if (result.court != null) {
            val co = JSONObject()
            val corners = JSONArray()
            for (c in result.court.cornersPx) {
                corners.put(JSONArray().put(c.x.toDouble()).put(c.y.toDouble()))
            }
            co.put("corners", corners)
            val hg = JSONArray()
            for (v in result.court.homography) hg.put(v.toDouble())
            co.put("homography", hg)
            o.put("court", co)
        }

        // 全局轨迹点（全字段）
        val traj = JSONArray()
        for (p in result.trajectory) traj.put(ballPointJson(p))
        o.put("trajectory", traj)

        // hits（轨迹点用 frame 索引引用全局 trajectory，避免重复存储）
        val hits = JSONArray()
        for (h in result.hits) {
            val hj = JSONObject().apply {
                put("id", h.id)
                put("frameIndex", h.frameIndex)
                put("timeSeconds", h.timeSeconds)
                put("hitType", h.hitType.name)
                put("maxSpeedKmh", h.maxSpeedKmh.toDouble())
                put("avgSpeedKmh", h.avgSpeedKmh.toDouble())
                put("angleDeg", h.angleDeg.toDouble())
                put("netCrossed", h.netCrossed)
                put("landSide", h.landSide)
                put("landZone", h.landZone)
                put("peakHeightM", h.peakHeightM.toDouble())
                val ref = JSONArray()
                for (tp in h.trajectory) ref.put(tp.frame)
                put("trajFrames", ref)
            }
            hits.put(hj)
        }
        o.put("hits", hits)

        // summary
        o.put(
            "summary", JSONObject().apply {
                put("totalHits", result.summary.totalHits)
                put("smashCount", result.summary.smashCount)
                put("maxSpeedKmh", result.summary.maxSpeedKmh.toDouble())
                put("avgSpeedKmh", result.summary.avgSpeedKmh.toDouble())
                put("fastestHitId", result.summary.fastestHitId)
            }
        )

        // highlights
        val hl = JSONArray()
        for (h in result.highlights) {
            hl.put(
                JSONObject().apply {
                    put("type", h.type)
                    put("startSec", h.startSec)
                    put("endSec", h.endSec)
                    put("desc", h.desc)
                }
            )
        }
        o.put("highlights", hl)

        // poseFrames（骨骼，回放时随播放显示）
        val pf = JSONArray()
        for (frame in result.poseFrames) {
            val pj = JSONObject().put("timeSec", frame.timeSec).put("frame", frame.frame)
            val skels = JSONArray()
            for (sk in frame.skeletons) {
                val pts = JSONArray()
                for (pt in sk.points) {
                    pts.put(
                        JSONArray().put(pt.x.toDouble()).put(pt.y.toDouble()).put(pt.z.toDouble()).put(pt.visibility.toDouble())
                    )
                }
                skels.put(pts)
            }
            pj.put("skeletons", skels)
            pf.put(pj)
        }
        o.put("poseFrames", pf)
        return o.toString()
    }

    private fun ballPointJson(p: BallPoint): JSONObject = JSONObject().apply {
        put("frame", p.frame)
        put("timeSec", p.timeSec)
        put("x", p.x.toDouble())
        put("y", p.y.toDouble())
        put("confidence", p.confidence.toDouble())
        put("courtX", p.courtX.toDouble())
        put("courtY", p.courtY.toDouble())
        put("speedKmh", p.speedKmh?.toDouble())
        put("isSmash", p.isSmash)
        put("zMeters", p.zMeters.toDouble())
        put("groundX", p.groundX.toDouble())
        put("groundY", p.groundY.toDouble())
    }

    /** 解码完整结果。videoPathOverride：回放时视频实际路径（历史记录 filesDir/videos/videoName） */
    fun decodeFull(json: String, videoPathOverride: String? = null): AnalysisResult? = runCatching {
        val o = JSONObject(json)
        if (o.optString("format") != "full_v1") return@runCatching null

        val vij = o.getJSONObject("videoInfo")
        val videoInfo = VideoInfo(
            path = videoPathOverride ?: vij.getString("path"),
            durationMs = vij.getLong("durationMs"),
            width = vij.getInt("width"),
            height = vij.getInt("height"),
            fps = vij.getDouble("fps").toFloat(),
            totalFrames = vij.getInt("totalFrames")
        )

        val court: CourtResult? = if (o.has("court")) {
            val co = o.getJSONObject("court")
            val cArr = co.getJSONArray("corners")
            val corners = (0 until cArr.length()).map { i ->
                val pair = cArr.getJSONArray(i)
                PointF(pair.getDouble(0).toFloat(), pair.getDouble(1).toFloat())
            }
            val hArr = co.getJSONArray("homography")
            val hg = FloatArray(hArr.length()) { hArr.getDouble(it).toFloat() }
            CourtResult(corners, hg)
        } else null

        // 全局轨迹
        val tArr = o.getJSONArray("trajectory")
        val trajectory = (0 until tArr.length()).map { parseBallPoint(tArr.getJSONObject(it)) }
        val byFrame = trajectory.associateBy { it.frame }

        // hits
        val hArr = o.getJSONArray("hits")
        val hits = (0 until hArr.length()).map { i ->
            val hj = hArr.getJSONObject(i)
            val ref = hj.getJSONArray("trajFrames")
            val ht = (0 until ref.length()).mapNotNull { byFrame[ref.getInt(it)] }
            HitAnalysis(
                id = hj.getString("id"),
                frameIndex = hj.getInt("frameIndex"),
                timeSeconds = hj.getDouble("timeSeconds"),
                hitType = runCatching { HitType.valueOf(hj.getString("hitType")) }.getOrDefault(HitType.UNKNOWN),
                maxSpeedKmh = hj.getDouble("maxSpeedKmh").toFloat(),
                avgSpeedKmh = hj.getDouble("avgSpeedKmh").toFloat(),
                angleDeg = hj.getDouble("angleDeg").toFloat(),
                trajectory = ht,
                netCrossed = hj.optBoolean("netCrossed", false),
                landSide = hj.optString("landSide", "A"),
                landZone = hj.optString("landZone", ""),
                peakHeightM = hj.optDouble("peakHeightM", 0.0).toFloat()
            )
        }

        val sj = o.getJSONObject("summary")
        val summary = AnalysisSummary(
            totalHits = sj.getInt("totalHits"),
            smashCount = sj.getInt("smashCount"),
            maxSpeedKmh = sj.getDouble("maxSpeedKmh").toFloat(),
            avgSpeedKmh = sj.getDouble("avgSpeedKmh").toFloat(),
            fastestHitId = if (sj.isNull("fastestHitId")) null else sj.optString("fastestHitId")
        )

        val hlArr = o.getJSONArray("highlights")
        val highlights = (0 until hlArr.length()).map { i ->
            val h = hlArr.getJSONObject(i)
            Highlight(
                type = h.getString("type"),
                startSec = h.getDouble("startSec"),
                endSec = h.getDouble("endSec"),
                desc = h.getString("desc")
            )
        }

        val pfArr = o.getJSONArray("poseFrames")
        val poseFrames = (0 until pfArr.length()).map { i ->
            val pj = pfArr.getJSONObject(i)
            val skArr = pj.getJSONArray("skeletons")
            val skels = (0 until skArr.length()).map { si ->
                val ptsArr = skArr.getJSONArray(si)
                val pts = (0 until ptsArr.length()).map { pi ->
                    val a = ptsArr.getJSONArray(pi)
                    PoseKeyPoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                }
                PoseSkeleton(pts)
            }
            PoseFrameData(pj.getDouble("timeSec"), pj.getInt("frame"), skels)
        }

        AnalysisResult(
            videoInfo = videoInfo,
            court = court,
            trajectory = trajectory,
            hits = hits,
            summary = summary,
            analysisDurationMs = o.getLong("analysisDurationMs"),
            appVersion = o.getString("appVersion"),
            frameWidth = o.getInt("frameWidth"),
            frameHeight = o.getInt("frameHeight"),
            frameAtMaxSpeed = o.getInt("frameAtMaxSpeed"),
            poseFrames = poseFrames,
            highlights = highlights
        )
    }.getOrNull()

    private fun parseBallPoint(o: JSONObject): BallPoint = BallPoint(
        frame = o.getInt("frame"),
        timeSec = o.getDouble("timeSec"),
        x = o.getDouble("x").toFloat(),
        y = o.getDouble("y").toFloat(),
        confidence = o.getDouble("confidence").toFloat(),
        courtX = o.getDouble("courtX").toFloat(),
        courtY = o.getDouble("courtY").toFloat(),
        speedKmh = if (o.isNull("speedKmh")) null else o.getDouble("speedKmh").toFloat(),
        isSmash = o.optBoolean("isSmash", false),
        zMeters = o.optDouble("zMeters", 0.0).toFloat(),
        groundX = o.optDouble("groundX", 0.0).toFloat(),
        groundY = o.optDouble("groundY", 0.0).toFloat()
    )
}
