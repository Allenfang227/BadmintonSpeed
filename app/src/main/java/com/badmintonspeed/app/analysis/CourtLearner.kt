package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.PointF
import com.badmintonspeed.app.data.CourtModelRepo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 场地标定学习器（用户要求："每次标完之后把那个东西纳入容器里面进行学习，用于提高识别精准度"）：
 * 每次成功标定（自动或手动）都把4角点归一化坐标存入本地容器（SharedPreferences），
 * 下次自动检测失败时，用最近一次学习到的标定先验直接生成候选角点——
 * 因为用户通常用同一手机、同一机位拍摄，标定角点在画面中的相对位置是相似的。
 * 相当于轻量级的"在线学习"：标定一次，后续自动识别成功率逐步提高。
 */
class CourtLearner(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("court_learner", Context.MODE_PRIVATE)
    private val keyCalib = "calibrations"
    private val maxKeep = 10

    data class Calibration(
        val corners: List<PointF>,   // 归一化坐标 0..1（相对帧宽高）
        val frameW: Int,
        val frameH: Int,
        val timestamp: Long
    )

    /** 保存一次成功标定（像素角点 → 归一化后入容器） */
    fun save(cornersPx: List<PointF>, frameW: Int, frameH: Int) {
        if (cornersPx.size != 4 || frameW <= 0 || frameH <= 0) return
        val arr = JSONArray()
        for (p in cornersPx) {
            val obj = JSONObject()
            obj.put("x", p.x / frameW)
            obj.put("y", p.y / frameH)
            arr.put(obj)
        }
        val entry = JSONObject()
        entry.put("corners", arr)
        entry.put("w", frameW)
        entry.put("h", frameH)
        entry.put("ts", System.currentTimeMillis())

        // 保留最近 maxKeep 次
        val list = loadEntries().toMutableList()
        list.add(entry)
        if (list.size > maxKeep) list.removeAt(0)

        val root = JSONArray()
        for (e in list) root.put(e)
        prefs.edit().putString(keyCalib, root.toString()).apply()

        // v2.29 同时持久化到公共模型库：覆盖更新/卸载重装后由 syncFromPublic 恢复，
        // 不丢、可转发分享，且同机位下一个视频自动复用。
        try {
            val dir = CourtModelRepo.courtCalibDir(appContext)
            File(dir, "latest.json").writeText(entry.toString())
            CourtModelRepo.exportToPublic(appContext, "court_calib")
        } catch (e: Exception) {
            // 公共目录写入失败不影响主流程（SharedPreferences 仍在）
        }
    }

    /** 用最近一次学习到的标定先验，生成当前帧尺寸的像素角点 */
    fun predict(frameW: Int, frameH: Int): List<PointF>? {
        // v2.29 优先读持久化文件（启动 syncFromPublic 已从公共 Download 恢复）；
        // 文件缺失再回退 SharedPreferences。
        val fileLatest = try {
            val f = File(CourtModelRepo.courtCalibDir(appContext), "latest.json")
            if (f.exists()) JSONObject(f.readText()) else null
        } catch (e: Exception) { null }
        val latest = fileLatest ?: loadEntries().lastOrNull() ?: return null
        val arr = latest.optJSONArray("corners") ?: return null
        if (arr.length() != 4) return null
        return (0 until 4).map { i ->
            val obj = arr.getJSONObject(i)
            PointF(obj.optDouble("x", 0.0).toFloat() * frameW, obj.optDouble("y", 0.0).toFloat() * frameH)
        }
    }

    /** 学习到的标定次数 */
    fun count(): Int = loadEntries().size

    /** 清除所有学习记录 */
    fun clear() {
        prefs.edit().remove(keyCalib).apply()
    }

    private fun loadEntries(): List<JSONObject> {
        val raw = prefs.getString(keyCalib, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
