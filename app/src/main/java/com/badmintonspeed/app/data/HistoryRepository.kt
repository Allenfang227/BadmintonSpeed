package com.badmintonspeed.app.data

import android.content.Context
import com.badmintonspeed.app.domain.AnalysisRecord
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 历史记录仓库：以 JSON 文件形式保存在应用私有目录 history/ 下。
 * （MVP 简化：文档推荐 Room，后续迭代可无缝迁移为数据库实现）
 */
class HistoryRepository(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "history").apply { mkdirs() }

    fun save(record: AnalysisRecord) {
        val file = File(dir, "${record.id}.json")
        val json = JSONObject().apply {
            put("id", record.id)
            put("title", record.title)
            put("videoName", record.videoName)
            put("createdAt", record.createdAt)
            put("maxSpeedKmh", record.maxSpeedKmh)
            put("totalHits", record.totalHits)
            put("smashCount", record.smashCount)
            put("resultJson", record.resultJson)
        }
        file.writeText(json.toString())
    }

    fun list(): List<AnalysisRecord> {
        return dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    AnalysisRecord(
                        id = json.getString("id"),
                        title = json.getString("title"),
                        videoName = json.getString("videoName"),
                        createdAt = json.getLong("createdAt"),
                        maxSpeedKmh = json.getDouble("maxSpeedKmh").toFloat(),
                        totalHits = json.getInt("totalHits"),
                        smashCount = json.getInt("smashCount"),
                        resultJson = json.getString("resultJson")
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

        fun defaultTitle(createdAt: Long, videoName: String): String {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val name = videoName.substringBeforeLast(".").take(12)
            return "${fmt.format(Date(createdAt))} $name"
        }
    }
}
