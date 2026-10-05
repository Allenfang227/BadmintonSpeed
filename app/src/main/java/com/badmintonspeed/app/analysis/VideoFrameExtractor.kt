package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.badmintonspeed.app.domain.VideoInfo
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * v2.23：顺序解码抽帧（MediaCodec + Surface），替代 getFrameAtTime 逐帧 seek。
 *
 * 旧实现每个采样点都 seek 解码一次：4K 视频一次 seek ~100-300ms，
 * 1500 帧下来要几分钟。顺序解码只解码一遍，速度提升 5~10 倍，
 * 且进度随解码线性推进（不会再"一会儿3%一会儿30%"）。
 */
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
     * 读取视频信息并按目标帧率采样帧（顺序解码）。
     */
    fun extract(
        file: File,
        analysisFps: Int,
        maxAnalysisSeconds: Int = 120,
        maxDimension: Int = 960,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onPreview: (Bitmap) -> Unit = {}
    ): ExtractedVideo {
        val retriever = MediaMetadataRetriever()
        val meta = try {
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
            Triple(durationMs, width to height, fps)
        } finally {
            retriever.release()
        }
        val durationMs = meta.first
        val (srcW, srcH) = meta.second
        var fps = meta.third

        val analyzedDurationMs = min(durationMs, maxAnalysisSeconds * 1000L)
        val totalFrames = if (durationMs > 0) (durationMs * fps / 1000).toInt() else 0
        val stepUs = if (analysisFps > 0) (1_000_000.0 / analysisFps).toLong() else 33_333L
        val sampleCount = (analyzedDurationMs * 1000 / stepUs).toInt() + 1

        // 输出尺寸（保持宽高比，长边 ≤ maxDimension）
        var outW = srcW; var outH = srcH
        if (maxOf(srcW, srcH) > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(srcW, srcH)
            outW = (srcW * scale).toInt().coerceAtLeast(1)
            outH = (srcH * scale).toInt().coerceAtLeast(1)
        }

        val frames = ArrayList<AnalyzedFrame>(min(sampleCount, 1500))
        if (outW > 0 && outH > 0) {
            try {
                val got = decodeSequentially(
                    file, outW, outH, stepUs, analyzedDurationMs,
                    maxFrames = 1500,
                    onProgress = onProgress,
                    sampleCountTotal = sampleCount,
                    onFrame = { idx, timeMs, bmp ->
                        frames.add(AnalyzedFrame(idx, timeMs, bmp))
                        if (frames.size % 12 == 0) onPreview(bmp)
                    }
                )
                // v2.25 E001 加固：主通道一帧都没采到（静默失败，如 ImageReader 不匹配）→ 强制回退
                if (got == 0) {
                    frames.clear()
                    fallbackSeekExtract(file, analysisFps, maxAnalysisSeconds, maxDimension, sampleCount, onProgress, onPreview, frames)
                }
            } catch (e: Exception) {
                // MediaCodec 通道失败（个别设备编码格式特殊）→ 回退 getFrameAtTime
                frames.clear()
                fallbackSeekExtract(file, analysisFps, maxAnalysisSeconds, maxDimension, sampleCount, onProgress, onPreview, frames)
            }
        } else {
            fallbackSeekExtract(file, analysisFps, maxAnalysisSeconds, maxDimension, sampleCount, onProgress, onPreview, frames)
        }

        if (frames.isNotEmpty() && frames.size % 12 != 0) onPreview(frames.first().bitmap)

        val actualFps = if (frames.size > 1) {
            frames.size.toFloat() / ((frames.last().timeMs - frames.first().timeMs).coerceAtLeast(1) / 1000f)
        } else {
            fps
        }
        val info = VideoInfo(
            path = file.absolutePath,
            durationMs = durationMs,
            width = srcW,
            height = srcH,
            fps = actualFps,
            totalFrames = totalFrames
        )
        return ExtractedVideo(info, frames)
    }

    /** MediaCodec 顺序解码主通道：解码一遍，按时间戳采样。返回采到的帧数（0=静默失败）。 */
    private fun decodeSequentially(
        file: File,
        outW: Int,
        outH: Int,
        stepUs: Long,
        analyzedDurationMs: Long,
        maxFrames: Int,
        onProgress: (Int, Int) -> Unit,
        sampleCountTotal: Int,
        onFrame: (Int, Long, Bitmap) -> Unit
    ): Int {
        var frameCount = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { trackIdx = i; format = f; break }
            }
            if (trackIdx < 0 || format == null) throw IllegalStateException("no video track")
            extractor.selectTrack(trackIdx)

            // v2.25 E001 修复：ImageReader 必须匹配解码器实际输出尺寸（decW×decH），
            // 否则 acquireLatestImage 拿不到帧（"不管传什么视频都提取 0 帧"）。
            // 解码输出 buffer 尺寸 = 视频存储尺寸（旋转不改变 buffer 尺寸，由 KEY_ROTATION 标记），
            // 拿到帧后再按 KEY_ROTATION 旋转并缩放到 outW/outH。
            val decW = format.getInteger(MediaFormat.KEY_WIDTH)
            val decH = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = format.getInteger(MediaFormat.KEY_ROTATION, 0)
            val imageReader = ImageReader.newInstance(decW, decH, PixelFormat.RGBA_8888, 2)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, imageReader.surface, null, 0)
                codec.start()

                var nextSampleUs = 0L
                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                var lastPtsUs = -1L

                while (!outputDone) {
                    // 喂输入
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)
                            val sampleSize = extractor.sampleSize
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                if (buf != null) {
                                    buf.clear()
                                    val read = extractor.readSampleData(buf, 0)
                                    if (read > 0) {
                                        codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                        extractor.advance()
                                    } else {
                                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    }
                                } else {
                                    extractor.advance()
                                }
                            }
                        }
                    }
                    // 取输出
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { }
                        outIdx >= 0 -> {
                            val ptsUs = info.presentationTimeUs.coerceAtLeast(lastPtsUs)
                            val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            // 达到采样点且还没采够
                            if (frameCount < maxFrames &&
                                ptsUs >= nextSampleUs &&
                                (ptsUs / 1000) <= analyzedDurationMs
                            ) {                                val img = imageReader.acquireLatestImage()
                                if (img != null) {
                                    val bmp = imageToBitmap(img)
                                    if (bmp != null) {
                                        // v2.25 E001 修复：竖拍视频（ROTATION 90/270）解码 buffer 是横置的，
                                        // 旋转回显示方向后再缩放到输出尺寸
                                        val rotated = if (rotation == 90 || rotation == 270) {
                                            val m = android.graphics.Matrix()
                                            m.postRotate(rotation.toFloat())
                                            try { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }
                                            catch (e: Exception) { bmp }
                                        } else bmp
                                        val finalBmp = if (rotated.width != outW || rotated.height != outH) {
                                            Bitmap.createScaledBitmap(rotated, outW, outH, true)
                                        } else rotated
                                        if (finalBmp !== rotated && rotated !== bmp) rotated.recycle()
                                        if (rotated !== bmp && finalBmp !== bmp) bmp.recycle()
                                        onFrame(frameCount, ptsUs / 1000, finalBmp)
                                        frameCount++
                                        onProgress(frameCount, sampleCountTotal)
                                    }
                                    img.close()
                                }
                                // 下一采样点
                                nextSampleUs = ptsUs + stepUs
                            } else {
                                // v2.32 非采样帧也按已解码时间滚动进度：MediaCodec 必须逐帧解码，
                                // 若只在采样点上报，长视频阶段1进度条会长时间"卡住不动"，用户误以为死机。
                                val est = ((ptsUs / 1000) * sampleCountTotal / analyzedDurationMs.coerceAtLeast(1))
                                    .toInt().coerceIn(frameCount, sampleCountTotal)
                                if (est > frameCount) onProgress(est, sampleCountTotal)
                            }
                            lastPtsUs = ptsUs
                            codec.releaseOutputBuffer(outIdx, false)
                            if (isEos || (ptsUs / 1000) > analyzedDurationMs) {
                                outputDone = true
                                // 释放 ImageReader 剩余帧
                                try { imageReader.acquireLatestImage()?.close() } catch (_: Exception) { }
                            }
                        }
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) { }
                try { codec.release() } catch (_: Exception) { }
                imageReader.close()
            }
        } finally {
            extractor.release()
        }
        return frameCount
    }

    /** Image(RGBA_8888) → Bitmap：单平面直接拷贝 */
    private fun imageToBitmap(img: Image): Bitmap? {
        return try {
            val plane = img.planes[0]
            val buf: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = img.width
            val h = img.height
            val pixels = IntArray(w * h)
            val tmp = ByteArray(4)
            var row = 0
            while (row < h) {
                var col = 0
                while (col < w) {
                    buf.position(row * rowStride + col * pixelStride)
                    buf.get(tmp)
                    pixels[row * w + col] =
                        ((tmp[0].toInt() and 0xFF) shl 16) or
                        ((tmp[1].toInt() and 0xFF) shl 8) or
                        (tmp[2].toInt() and 0xFF) or
                        (0xFF shl 24)
                    col++
                }
                row++
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) { null }
    }

    /** 兜底通道：旧式 getFrameAtTime 逐 seek */
    private fun fallbackSeekExtract(
        file: File,
        analysisFps: Int,
        maxAnalysisSeconds: Int,
        maxDimension: Int,
        sampleCount: Int,
        onProgress: (Int, Int) -> Unit,
        onPreview: (Bitmap) -> Unit,
        frames: MutableList<AnalyzedFrame>
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val analyzedMs = min(durationMs, maxAnalysisSeconds * 1000L)
            val stepMs = if (analysisFps > 0) (1000.0 / analysisFps) else 33.0
            var tMs = 0L
            var idx = 0
            while (tMs <= analyzedMs && frames.size < 1500) {
                val raw = retriever.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (raw != null) {
                    val bmp = downscale(raw, maxDimension)
                    if (raw !== bmp) raw.recycle()
                    frames.add(AnalyzedFrame(idx, tMs, bmp))
                    if (frames.size % 12 == 0) onPreview(bmp)
                }
                onProgress(frames.size, sampleCount)
                tMs += stepMs.toLong()
                idx++
            }
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
