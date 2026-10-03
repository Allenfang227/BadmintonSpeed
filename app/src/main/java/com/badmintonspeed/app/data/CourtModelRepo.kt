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

    /** 导出权威目录 → 公共 Download/BadmintonSpeed（MediaStore，免存储权限） */
    fun exportToPublic(context: Context, subDir: String? = null): Boolean {
        return try {
            val srcDir = if (subDir != null) File(rootDir(context), subDir) else rootDir(context)
            if (!srcDir.exists()) return false
            val rel = if (subDir != null) "$ROOT_NAME/$subDir" else ROOT_NAME
            val files = srcDir.listFiles() ?: return false
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

    private fun exportOneFile(context: Context, f: File, relDir: String): Uri? {
        return try {
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
}
