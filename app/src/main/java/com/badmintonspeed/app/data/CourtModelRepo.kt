package com.badmintonspeed.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * v2.18 模型/训练数据仓库：
 *  - 权威目录：filesDir/BadmintonSpeed/（App 内训练、检测直接读这里）
 *  - 公共副本：Download/BadmintonSpeed/（用户可见、可转发；导出用 MediaStore 免权限）
 * 目录：
 *    labels/        场地 12 交点训练 json + 标定底图（手动标定自动积累）
 *    ball_samples/  红框裁剪的羽毛球正样本（模型训练输入）
 *    ball_model/    templates.json（本地训练产物，实测检测时调用）
 */
object CourtModelRepo {
    const val ROOT_NAME = "BadmintonSpeed"

    fun rootDir(context: Context): File =
        File(context.filesDir, ROOT_NAME).apply { mkdirs() }

    fun labelsDir(context: Context): File =
        File(rootDir(context), "labels").apply { mkdirs() }

    fun samplesDir(context: Context): File =
        File(rootDir(context), "ball_samples").apply { mkdirs() }

    fun modelDir(context: Context): File =
        File(rootDir(context), "ball_model").apply { mkdirs() }

    /** v2.29 场地四角标定持久化目录（跨更新/重装/分享不丢，同机位自动复用） */
    fun courtCalibDir(context: Context): File =
        File(rootDir(context), "court_calib").apply { mkdirs() }

    /** 导出权威目录 → 公共 Download/BadmintonSpeed（MediaStore，免存储权限） */
    fun exportToPublic(context: Context, subDir: String? = null): Boolean {
        return try {
            val srcDir = if (subDir != null) File(rootDir(context), subDir) else rootDir(context)
            if (!srcDir.exists()) return false
            val rel = if (subDir != null) "$ROOT_NAME/$subDir" else ROOT_NAME
            val files = srcDir.listFiles() ?: return false
            // v2.30：先确保 .nomedia 就位，再写图片 —— 训练图（球样本/标定底图）不被图库收录
            addNoMedia(context)
            for (f in files) {
                if (!f.isFile) continue
                val data = f.readBytes()
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                        put(MediaStore.Downloads.MIME_TYPE, if (f.name.endsWith(".json")) "application/json" else "image/jpeg")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/$rel/")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), rel)
                    dir.mkdirs()
                    File(dir, f.name).writeBytes(data)
                }
            }
            true
        } catch (e: Exception) { false }
    }

    /**
     * v2.30 在公共 Download/BadmintonSpeed/ 写 .nomedia：
     * 阻止系统图库/相册扫描该目录及所有子目录里的训练图片（球样本、标定底图），
     * 文件管理器仍可正常查看这些图片。放在根目录一份即可覆盖全部子目录。
     */
    fun addNoMedia(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val rel = "Download/$ROOT_NAME/"
                // 已存在则不重复写
                val exists = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(rel, ".nomedia"), null
                )?.use { it.moveToFirst() } ?: false
                if (exists) return true
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, ".nomedia")
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, rel)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
                resolver.openOutputStream(uri)?.use { it.write(ByteArray(0)) }
                v.clear(); v.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, v, null, null)
                true
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    ROOT_NAME
                ).apply { mkdirs() }
                File(dir, ".nomedia").createNewFile()
            }
        } catch (e: Exception) { false }
    }

    /** 列出公共目录 Download/BadmintonSpeed 下文件（用于文件管理页） */
    data class PublicFile(val name: String, val uri: Uri?, val path: String)

    fun listPublic(context: Context, subDir: String? = null): List<PublicFile> {
        val out = ArrayList<PublicFile>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rel = if (subDir != null) "Download/$ROOT_NAME/$subDir/" else "Download/$ROOT_NAME/"
                val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATA)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(rel), null
                )?.use { c ->
                    val iName = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    val iData = c.getColumnIndex(MediaStore.Downloads.DATA)
                    val iId = c.getColumnIndex(MediaStore.Downloads._ID)
                    while (c.moveToNext()) {
                        out.add(PublicFile(
                            c.getString(iName) ?: "",
                            Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(iId).toString()),
                            c.getString(iData) ?: ""
                        ))
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    if (subDir != null) "$ROOT_NAME/$subDir" else ROOT_NAME)
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) out.add(PublicFile(f.name, Uri.fromFile(f), f.absolutePath))
                }
            }
        } catch (e: Exception) {
            // 查询失败：返回空
        }
        return out.sortedBy { it.name }
    }

    /** 全量打包 zip → 公共目录（一键分享用） */
    fun exportZip(context: Context, zipName: String = "model_backup.zip"): Uri? {
        return try {
            val zip = File(context.cacheDir, zipName)
            if (zip.exists()) zip.delete()
            val src = rootDir(context)
            val srcFiles = src.listFiles()?.filter { it.isFile } ?: return null
            java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                srcFiles.forEach { f ->
                    if (f.name.endsWith(".zip")) return@forEach
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    zos.write(f.readBytes())
                    zos.closeEntry()
                }
                // 子目录
                for (sub in listOf("labels", "ball_samples", "ball_model")) {
                    val d = File(src, sub)
                    if (!d.exists()) continue
                    d.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            zos.putNextEntry(java.util.zip.ZipEntry("$sub/${f.name}"))
                            zos.write(f.readBytes())
                            zos.closeEntry()
                        }
                    }
                }
            }
            exportOneFile(context, zip, "Download/$ROOT_NAME")
        } catch (e: Exception) { null }
    }

    private fun exportOneFile(context: Context, f: File, relDir: String): Uri? {        return try {
            val data = f.readBytes()
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "$relDir/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    uri
                } else null
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relDir)
                dir.mkdirs()
                val dst = File(dir, f.name)
                dst.writeBytes(data)
                Uri.fromFile(dst)
            }
        } catch (e: Exception) { null }
    }

    // ================= v2.21 持久化与共享 =================

    data class SyncResult(
        val restored: Int,   // 本次从公共目录恢复的文件数
        val templates: Int,  // 恢复的模型文件数（templates.json）
        val samples: Int,    // 恢复的球样本数
        val labels: Int      // 恢复的场地标注数
    )

    /**
     * App 启动时调用：主动扫描公共 Download/BadmintonSpeed，
     * 把模型/样本/标注恢复进 filesDir。
     * 卸载重装后 filesDir 被清空，本方法保证原有训练成果不丢。
     * 策略：本地缺失 → 恢复；本地存在但公共版本更新（DATE_MODIFIED）→ 覆盖。
     */
    fun syncFromPublic(context: Context): SyncResult {
        var restored = 0; var nTemplates = 0; var nSamples = 0; var nLabels = 0
        try {
            for (sub in listOf("labels", "ball_samples", "ball_model", "court_calib")) {
                val files = listPublic(context, sub)
                val dstDir = File(rootDir(context), sub).apply { mkdirs() }
                for (pf in files) {
                    val uri = pf.uri ?: continue
                    val dst = File(dstDir, pf.name)
                    var need = !dst.exists()
                    if (!need) {
                        try {
                            context.contentResolver.query(
                                uri, arrayOf(MediaStore.Downloads.DATE_MODIFIED), null, null, null
                            )?.use { c ->
                                if (c.moveToFirst()) {
                                    val pubModSec = c.getLong(0)
                                    if (pubModSec > dst.lastModified() / 1000L) need = true
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    if (need) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        } ?: continue
                        restored++
                        when (sub) {
                            "labels" -> nLabels++
                            "ball_samples" -> nSamples++
                            "ball_model" -> if (pf.name == "templates.json") nTemplates++
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return SyncResult(restored, nTemplates, nSamples, nLabels)
    }

    /**
     * 导入别人分享的模型包 zip（model_backup.zip）：
     *  - 样本/标注按文件名叠加（重名跳过，不删任何本地数据）
     *  - templates.json 做模板级合并去重（真正"叠加训练成果"）
     *  - 导入后自动回写公共 Download 目录
     * @return 导入文件数；-1 表示包解析失败
     */
    fun importZip(context: Context, uri: Uri): Int {
        var imported = 0
        try {
            java.util.zip.ZipInputStream(
                context.contentResolver.openInputStream(uri)
            ).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val allowed = name.startsWith("labels/") ||
                        name.startsWith("ball_samples/") ||
                        name.startsWith("ball_model/")
                    // zip-slip 防护 + 只接受三个已知子目录
                    if (!allowed || name.contains("..")) {
                        zis.closeEntry(); entry = zis.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        zis.closeEntry(); entry = zis.nextEntry; continue
                    }
                    val dst = File(rootDir(context), name)
                    dst.parentFile?.mkdirs()
                    when {
                        name == "ball_model/templates.json" && dst.exists() -> {
                            mergeTemplates(dst, zis.readBytes())
                            imported++
                        }
                        name.startsWith("ball_model/") -> {
                            dst.outputStream().use { zis.copyTo(it) }; imported++
                        }
                        dst.exists() -> { /* 样本/标注重名：保留本地，跳过 */ }
                        else -> {
                            dst.outputStream().use { zis.copyTo(it) }; imported++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            // 导入成果回写公共目录（保证卸载不丢 + 可继续分享）
            exportToPublic(context, "labels")
            exportToPublic(context, "ball_samples")
            exportToPublic(context, "ball_model")
        } catch (e: Exception) { return -1 }
        return imported
    }

    /** 合并两个 templates.json：模板数组与标签数组分别去重叠加 */
    private fun mergeTemplates(dst: File, incomingBytes: ByteArray) {
        try {
            val cur = org.json.JSONObject(dst.readText())
            val inc = org.json.JSONObject(String(incomingBytes))
            val seenT = LinkedHashSet<String>()
            val mergedT = org.json.JSONArray()
            listOf(cur, inc).forEach { j ->
                val a = j.getJSONArray("templates")
                for (i in 0 until a.length()) {
                    val s = a.getString(i)
                    if (seenT.add(s)) mergedT.put(s)
                }
            }
            val seenL = LinkedHashSet<String>()
            val mergedL = org.json.JSONArray()
            listOf(cur, inc).forEach { j ->
                val a = j.getJSONArray("labels")
                for (i in 0 until a.length()) {
                    val s = a.getString(i)
                    if (seenL.add(s)) mergedL.put(s)
                }
            }
            val out = org.json.JSONObject()
            out.put("templates", mergedT)
            out.put("labels", mergedL)
            out.put("size", BallLearner.TEMPLATE_SIZE)
            dst.writeText(out.toString())
        } catch (e: Exception) {
            // 合并失败：退化为直接覆盖
            dst.writeBytes(incomingBytes)
        }
    }
}
