package com.badmintonspeed.app.ui.result

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Paint
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.components.CourtOverlay
import com.badmintonspeed.app.ui.components.PoseOverlay
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.TrailYellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 结果页（图6-9）：
 * 视频区 + 黄色流光球路轨迹 + 右上角 3D 可拖拽实时模拟回放
 * + SHOT SPEED / LIVE SPEED / IN-OUT 判定 + 底部控制栏（TRAJ FX / PREV / NEXT / DOWNLOAD）。
 */
@Composable
fun ResultScreen(vm: MainViewModel) {
    val result = vm.result.collectAsState().value ?: return
    val context = LocalContext.current

    var progressMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var showTrail by remember { mutableStateOf(true) }
    // 当前选中球（用户要求：每个球的速度都会变，右上角按球切换）
    val hits = result.hits
    val defaultHitIdx = (hits.indices.maxByOrNull { hits[it].maxSpeedKmh } ?: 0).coerceAtLeast(0)
    var currentHitIndex by remember { mutableStateOf(if (hits.isEmpty()) -1 else defaultHitIdx) }
    val mediaPlayer = remember { MediaPlayer() }

    val frameTimeMs = (1000f / result.videoInfo.fps.coerceAtLeast(1f)).toLong()

    // 绑定视频源
    DisposableEffect(Unit) {
        runCatching {
            mediaPlayer.setDataSource(result.videoInfo.path)
            mediaPlayer.isLooping = true
            mediaPlayer.prepare()
            mediaPlayer.start()
        }
        onDispose {
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
        }
    }

    // 播放轮询（更新进度）
    LaunchedEffect(playing) {
        while (isActive) {
            if (playing) {
                runCatching {
                    val p = mediaPlayer.currentPosition.toLong()
                    progressMs = p
                }
            }
            delay(60)
        }
    }

    fun seekTo(posMs: Long) {
        val target = posMs.coerceIn(0L, result.videoInfo.durationMs)
        runCatching {
            mediaPlayer.seekTo(target.toInt())
            progressMs = target
        }
    }

    fun stepFrame(delta: Long) {
        val p = progressMs + delta
        seekTo(p)
    }

    fun togglePlay() {
        if (playing) {
            runCatching { mediaPlayer.pause() }
        } else {
            runCatching { mediaPlayer.start() }
        }
        playing = !playing
    }

    // ---- 实时数据 v2：按当前选中球计算（SHOT = 该球最大速度，LIVE = 播放进度实时速度，IN/OUT = 该球落点） ----
    val shotSpeed: Float
    val liveSpeed: Float
    val isIn: Boolean
    val currentHit: HitAnalysis?
    if (hits.isNotEmpty() && currentHitIndex in hits.indices) {
        val hit = hits[currentHitIndex]
        currentHit = hit
        shotSpeed = hit.maxSpeedKmh
        // LIVE：当前播放进度在球轨迹中的实时速度（未到击球时刻前显示该球最大速度，过落点后显示落点速度）
        val tNow = progressMs / 1000.0
        val hitPoints = hit.trajectory
        val liveP = hitPoints.lastOrNull { it.timeSec <= tNow + 0.03 }
        liveSpeed = liveP?.speedKmh ?: hit.avgSpeedKmh
        val land = hitPoints.lastOrNull()
        isIn = land?.let { it.courtX in 0f..6.10f && it.courtY in 0f..13.40f } ?: true
    } else {
        currentHit = null
        val maxP = result.trajectory.maxByOrNull { it.speedKmh ?: 0f }
        shotSpeed = maxP?.speedKmh ?: 0f
        liveSpeed = result.trajectory.lastOrNull()?.speedKmh ?: 0f
        isIn = true
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF08100C))) {
        // ---- 中央视频区（带流光轨迹） ----
        Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
            // 视频画面
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                runCatching {
                                    mediaPlayer.setSurface(android.view.Surface(surface))
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // v2.12：场地黄线常驻叠加（一直悬在视频上，不闪）
            CourtOverlay(
                court = result.court,
                frameW = result.frameWidth,
                frameH = result.frameHeight,
                modifier = Modifier.fillMaxSize()
            )

            // v2.12：骨骼识别动态叠加（随视频播放一直显示运动员骨架）
            PoseOverlay(
                poseFrames = result.poseFrames,
                progressMs = progressMs,
                frameW = result.frameWidth,
                frameH = result.frameHeight,
                modifier = Modifier.fillMaxSize()
            )

            // 黄色流光轨迹叠加
            if (showTrail) {
                TrajectoryOverlay(result, progressMs, Modifier.fillMaxSize())
            }

            // 左上返回箭头（图6-9）
            Surface(
                shape = CircleShape,
                color = Color(0xAA0A1410),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(44.dp)
                    .clickable { vm.goTo(com.badmintonspeed.app.ui.Screen.Home) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("←", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 右上角：3D 标签 + SHOT SPEED / LIVE SPEED（图6-9）
            Column(
                Modifier.align(Alignment.TopEnd).padding(16.dp),
                horizontalAlignment = Alignment.End
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xAA0A1410)) {
                    Text("3D", color = Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xAA0A1410)) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpeedTag("SHOT SPEED", shotSpeed)
                        Spacer(Modifier.width(14.dp))
                        SpeedTag("LIVE SPEED", liveSpeed)
                        Spacer(Modifier.width(10.dp))
                        // IN/OUT 落点判定（用户要求：选了设置选项后显示 in 和 out）
                        Text(
                            text = if (isIn) "IN" else "OUT",
                            color = if (isIn) Color(0xFF4ADE80) else Color(0xFFEF4444),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                        // v2.13 双场区 + 过网标记（"视频截分成对面和这边两个场区"）
                        if (currentHit != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${if (currentHit.netCrossed) "过网 " else ""}${currentHit.landSide}区落点",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // 右上角 3D 可拖拽实时模拟回放（图6-9 右上角）
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xCC0A1410),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 78.dp, end = 16.dp)
                    .size(200.dp)
            ) {
                Court3DView(
                    result = result,
                    currentHit = if (currentHitIndex in hits.indices) hits[currentHitIndex] else null,
                    hitIndex = currentHitIndex.coerceAtLeast(0),
                    hitCount = hits.size,
                    progressMs = progressMs,
                    poseFrames = result.poseFrames,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            }
        }

        // ---- 底部控制栏（图6-9） ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xEE08100C))
                .padding(horizontal = 24.dp, vertical = 10.dp)
        ) {
            // 进度条
            Slider(
                value = progressMs.toFloat(),
                onValueChange = { v -> seekTo(v.toLong()) },
                valueRange = 0f..result.videoInfo.durationMs.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = TrailYellow,
                    activeTrackColor = TrailYellow,
                    inactiveTrackColor = Color(0xFF2A3B31)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 控制按钮：TRAJ FX / PREV FRAME / 播放暂停 / NEXT FRAME / DOWNLOAD
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton("TRAJ FX", highlight = showTrail) { showTrail = !showTrail }
                ControlButton("PREV SHOT", highlight = false) {
                    if (hits.isNotEmpty()) currentHitIndex = (currentHitIndex - 1 + hits.size) % hits.size
                }
                // 播放/暂停（图7 中央）
                Surface(
                    shape = CircleShape,
                    color = Primary,
                    modifier = Modifier.size(46.dp).clickable { togglePlay() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (playing) "❚❚" else "▶", color = Color(0xFF06120A), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                ControlButton("NEXT SHOT", highlight = false) {
                    if (hits.isNotEmpty()) currentHitIndex = (currentHitIndex + 1) % hits.size
                }
                ControlButton("DOWNLOAD") { downloadResult(context, result) }
            }
        }
    }
}

/** 速度标签 */
@Composable
private fun SpeedTag(label: String, speed: Float) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            label,
            color = OnSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            "${"%.0f".format(speed)} KM/H",
            color = TrailYellow,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black
        )
    }
}

/** 底部控制按钮 */
@Composable
private fun ControlButton(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) Color(0x334ADE80) else Color(0xFF15241D),
        modifier = Modifier            .clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (highlight) Primary else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** 黄色流光轨迹叠加：当前播放时刻附近的球路光迹（图6-9） */
@Composable
private fun TrajectoryOverlay(result: AnalysisResult, progressMs: Long, modifier: Modifier) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Canvas(modifier.onSizeChanged { size = it }) {
        if (size.width <= 0 || size.height <= 0) return@Canvas
        val pts = result.trajectory
        if (pts.size < 2) return@Canvas

        // 显示区域（保持原视频宽高比，居中）
        val vw = result.frameWidth.coerceAtLeast(1)
        val vh = result.frameHeight.coerceAtLeast(1)
        val scale = min(size.width.toFloat() / vw, size.height.toFloat() / vh)
        val dw = vw * scale
        val dh = vh * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f

        fun map(p: com.badmintonspeed.app.domain.BallPoint): Offset =
            Offset(ox + p.x * scale, oy + p.y * scale)

        // 当前播放时刻
        val tNow = progressMs / 1000.0
        val visible = pts.filter { it.timeSec <= tNow + 0.03 }

        if (visible.size < 2) {
            // 播放初始：画一小段起始光点
            visible.lastOrNull()?.let { p ->
                val c = map(p)
                drawCircle(TrailYellow, radius = 7f, center = c)
                drawCircle(Color(0x66FFD60A), radius = 14f, center = c)
            }
            return@Canvas
        }

        // 光带（黄色流光）：从起点到当前点连成渐变光迹
        val last = visible.last()
        val segCount = 24
        val n = min(segCount, visible.size - 1)
        val tail = visible.takeLast(n)
        for (i in 1 until tail.size) {
            val a = map(tail[i - 1])
            val b = map(tail[i])
            val frac = i.toFloat() / tail.size
            val width = 2f + 7f * frac
            val alpha = 0.25f + 0.75f * frac
            drawLine(
                color = Color(0xFFFFD60A).copy(alpha = alpha),
                start = a,
                end = b,
                strokeWidth = width,
                cap = StrokeCap.Round
            )
        }

        // 亮点（当前球位）：三层光晕（图6-9 黄色流光亮点）
        val c = map(last)
        drawCircle(Color(0x33FFD60A), radius = 26f, center = c)
        drawCircle(Color(0x66FFD60A), radius = 16f, center = c)
        drawCircle(TrailYellow, radius = 8f, center = c)
        drawCircle(Color.White, radius = 3.5f, center = Offset(c.x - 2f, c.y - 2f))
    }
}

/** 导出视频 + 分析数据到公共 Downloads（图6-9 DOWNLOAD） */
private fun downloadResult(context: android.content.Context, result: AnalysisResult) {
    try {
        val videoFile = File(result.videoInfo.path)
        if (!videoFile.exists()) {
            Toast.makeText(context, "视频文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val resolver = context.contentResolver

        // 视频
        val videoValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "BadmintonSpeed_${stamp}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BadmintonSpeed")
            }
        }
        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoValues)
        if (videoUri != null) {
            resolver.openOutputStream(videoUri)?.use { out ->
                videoFile.inputStream().use { it.copyTo(out) }
            }
        }

        // 分析数据
        val dataValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "BadmintonSpeed_${stamp}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BadmintonSpeed")
            }
        }
        val dataUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dataValues)
        if (dataUri != null) {
            resolver.openOutputStream(dataUri)?.use { out ->
                out.write(buildReport(result).toByteArray())
            }
        }

        Toast.makeText(context, "已保存到 下载/BadmintonSpeed/", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun buildReport(result: AnalysisResult): String {
    val sb = StringBuilder()
    sb.appendLine("杀球测速 BadmintonSpeed 分析报告")
    sb.appendLine("版本：2.0")
    sb.appendLine("最高球速：${"%.1f".format(result.summary.maxSpeedKmh)} km/h")
    sb.appendLine("平均球速：${"%.1f".format(result.summary.avgSpeedKmh)} km/h")
    sb.appendLine("击球次数：${result.summary.totalHits}（其中杀球 ${result.summary.smashCount} 次）")
    sb.appendLine("轨迹点数：${result.trajectory.size}")
    sb.appendLine()
    sb.appendLine("--- 轨迹点（帧, 时间s, 像素x, 像素y, 球速km/h, 场地x, 场地y） ---")
    result.trajectory.forEach { p ->
        sb.appendLine("${p.frame}, ${"%.2f".format(p.timeSec)}, ${"%.0f".format(p.x)}, ${"%.0f".format(p.y)}, ${"%.1f".format(p.speedKmh ?: 0f)}, ${"%.2f".format(p.courtX)}, ${"%.2f".format(p.courtY)}")
    }
    return sb.toString()
}
