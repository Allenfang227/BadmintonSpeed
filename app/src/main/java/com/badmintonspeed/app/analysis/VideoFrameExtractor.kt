package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.badmintonspeed.app.domain.VideoInfo
import java.io.File
import kotlin.math.min

/** 从视频中提取分析帧 */
class VideoFrameExtractor {

    data class AnalyzedFrame(
        val index: Int,
        val timeMs: Long,
        val bitmap: Bitmap
    )

    data class ExtractedVideo(
        val info: VideoInfo,
        val frames: List<AnalyzedFrame>
    )

    /**
     * 读取视频信息并按目标帧率采样帧。
     * @param file 视频文件
     * @param analysisFps 目标分析帧率
     * @param maxAnalysisSeconds 最多分析的视频时长（秒）
     * @param maxDimension 帧长边最大值（降采样，提升处理速度）
     * @param onProgress (processed, total) 帧提取进度
     */
    fun extract(
        file: File,
        analysisFps: Int,
        maxAnalysisSeconds: Int = 120,
        maxDimension: Int = 960,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ExtractedVideo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            var fps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull() ?: 0f
            if (fps <= 0f || fps > 120f) fps = 30f

            val analyzedDurationMs = min(durationMs, maxAnalysisSeconds * 1000L)
            val totalFrames = if (durationMs > 0) (durationMs * fps / 1000).toInt() else 0

            // 采样间隔（毫秒）
            val stepMs = if (analysisFps > 0) (1000.0 / analysisFps) else 33.0
            val sampleCount = (analyzedDurationMs / stepMs).toInt() + 1
            val frames = ArrayList<AnalyzedFrame>(min(sampleCount, 1500))

            var tMs = 0L
            var idx = 0
            while (tMs <= analyzedDurationMs && frames.size < 1500) {
                val raw = retriever.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw != null) {
                    val bmp = downscale(raw, maxDimension)
                    if (raw !== bmp) raw.recycle()
                    frames.add(AnalyzedFrame(idx, tMs, bmp))
                }
                onProgress(frames.size, sampleCount)
                tMs += stepMs.toLong()
                idx++
            }

            // 帧率修正：以实际采样帧数计算
            val actualFps = if (frames.size > 1) {
                frames.size.toFloat() / ((frames.last().timeMs - frames.first().timeMs).coerceAtLeast(1) / 1000f)
            } else {
                fps
            }

            val info = VideoInfo(
                path = file.absolutePath,
                durationMs = durationMs,
                width = width,
                height = height,
                fps = actualFps,
                totalFrames = totalFrames
            )
            return ExtractedVideo(info, frames)
        } finally {
            retriever.release()
        }
    }

    /** 获取单帧（用于标定预览） */
    fun getFrame(file: File, timeMs: Long = 0L, maxDimension: Int = 960): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            val bmp = downscale(raw, maxDimension)
            if (raw !== bmp) raw.recycle()
            return bmp
        } finally {
            retriever.release()
        }
    }

    private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxSide
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
