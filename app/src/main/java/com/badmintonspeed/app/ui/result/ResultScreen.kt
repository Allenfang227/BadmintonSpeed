package com.badmintonspeed.app.ui.result

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.analysis.VideoFrameExtractor
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.BallPoint
import com.badmintonspeed.app.domain.HitType
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.Screen
import com.badmintonspeed.app.ui.computeScale
import com.badmintonspeed.app.ui.formatSpeed
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurface
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.SpeedColors
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ResultScreen(vm: MainViewModel) {
    val result = vm.result.collectAsState().value ?: return
    val unit = vm.settings.speedUnit
    val maxSpeed = result.summary.maxSpeedKmh

    val frameBmp by produceState<Bitmap?>(null, result) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val timeMs = (result.frameAtMaxSpeed / result.videoInfo.fps.coerceAtLeast(1f) * 1000).toLong()
                VideoFrameExtractor().getFrame(File(result.videoInfo.path), timeMs, 720)
            }.getOrNull()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 最高球速
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("本次最高球速", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatSpeed(maxSpeed, unit),
                        color = SpeedColors.forSpeed(maxSpeed),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(unit.displayName, color = OnSurfaceVariant, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                if (result.summary.smashCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("包含 ${result.summary.smashCount} 次杀球（≥140 km/h）", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 统计行
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("平均", formatSpeed(result.summary.avgSpeedKmh, unit), Modifier.weight(1f))
            Stat("击球", result.summary.totalHits.toString(), Modifier.weight(1f))
            Stat("杀球", result.summary.smashCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        // 球路回放
        SectionTitle("球路回放")
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            TrajectoryOverlay(frameBmp, result, Modifier.fillMaxWidth().height(300.dp))
        }
        Spacer(Modifier.height(16.dp))

        // 速度曲线
        SectionTitle("速度曲线")
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            SpeedChart(result.trajectory, result.summary.maxSpeedKmh, Modifier.fillMaxWidth().height(220.dp))
        }
        Spacer(Modifier.height(16.dp))

        // 击球列表
        SectionTitle("击球明细")
        if (result.hits.isEmpty()) {
            Text("未检测到明显击球（速度峰值低于 80 km/h），可尝试在设置中调低检测亮度阈值", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            result.hits.forEach { h ->
                HitRow(h, unit)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("已自动保存到历史记录", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { vm.goTo(Screen.Home) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("返回首页", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = OnBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(title, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun HitRow(hit: com.badmintonspeed.app.domain.HitAnalysis, unit: com.badmintonspeed.app.domain.SpeedUnit) {
    val typeColor = when (hit.hitType) {
        HitType.SMASH -> Error
        HitType.CLEAR -> SpeedColors.Fast
        HitType.DRIVE -> SpeedColors.Medium
        HitType.DROP -> SpeedColors.Slow
        else -> OnSurfaceVariant
    }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.hitType.displayName,
                color = typeColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(64.dp)
            )
            Column(Modifier.weight(1f)) {
                Text("${formatSpeed(hit.maxSpeedKmh, unit)} ${unit.displayName}", color = OnBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("平均 ${formatSpeed(hit.avgSpeedKmh, unit)} ${unit.displayName}", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("t=${"%.1f".format(hit.timeSeconds)}s", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 轨迹叠加层：帧图 + 按速度着色的球路 + 击球标记 */
@Composable
private fun TrajectoryOverlay(bmp: Bitmap?, result: AnalysisResult, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val maxSpeed = result.summary.maxSpeedKmh.coerceAtLeast(1f)

    Box(modifier.onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()) {
            if (size.width <= 0 || size.height <= 0) return@Canvas
            val dstInfo = computeScale(bmp?.width ?: result.frameWidth, bmp?.height ?: result.frameHeight, size.width, size.height)

            // 背景帧
            if (bmp != null) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(dstInfo.offsetX.toInt(), dstInfo.offsetY.toInt()),
                    dstSize = IntSize(dstInfo.width, dstInfo.height)
                )
            }

            // 轨迹坐标 -> 显示坐标（分析帧分辨率 -> 帧图分辨率 -> 显示）
            fun mapPoint(p: BallPoint): Offset {
                val fx = p.x / result.frameWidth
                val fy = p.y / result.frameHeight
                val bmpX = fx * (bmp?.width ?: result.frameWidth)
                val bmpY = fy * (bmp?.height ?: result.frameHeight)
                return Offset(
                    dstInfo.offsetX + bmpX * dstInfo.scale,
                    dstInfo.offsetY + bmpY * dstInfo.scale
                )
            }

            val pts = result.trajectory.filter { (it.speedKmh ?: 0f) > 5f }
            for (i in 1 until pts.size) {
                val a = mapPoint(pts[i - 1])
                val b = mapPoint(pts[i])
                drawLine(
                    color = SpeedColors.forSpeed(pts[i].speedKmh ?: 0f, maxSpeed),
                    start = a,
                    end = b,
                    strokeWidth = 5f
                )
            }

            // 击球标记
            for (h in result.hits) {
                val start = h.trajectory.firstOrNull() ?: continue
                val c = mapPoint(start)
                drawCircle(Color.Black, radius = 16f, center = c)
                drawCircle(Error, radius = 13f, center = c)
            }

            // 最高速点
            result.trajectory.maxByOrNull { it.speedKmh ?: 0f }?.let { top ->
                val c = mapPoint(top)
                drawCircle(SpeedColors.Fastest, radius = 18f, center = c, style = Stroke(width = 5f))
            }
        }
    }
}

/** 速度-时间曲线 */
@Composable
private fun SpeedChart(points: List<BallPoint>, maxSpeed: Float, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()) {
            if (size.width <= 0 || size.height <= 0 || points.size < 2) return@Canvas
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val padL = 46f; val padR = 10f; val padT = 14f; val padB = 28f
            val cw = w - padL - padR
            val ch = h - padT - padB
            val tMax = points.last().timeSec.coerceAtLeast(0.1)
            val sMax = (maxSpeed * 1.15f).coerceAtLeast(10f)

            fun xOf(t: Double) = padL + (t / tMax * cw).toFloat()
            fun yOf(s: Float) = padT + ch - (s / sMax * ch)

            // 网格与刻度
            val textPaint = Paint().apply {
                color = 0xFFCBD5E1.toInt()
                textSize = 22f
                textAlign = Paint.Align.RIGHT
            }
            val gridColor = Color(0xFF334155)
            for (tick in 0..4) {
                val v = sMax * tick / 4f
                val y = yOf(v)
                drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText("${v.toInt()}", padL - 6f, y + 8f, textPaint)
            }
            // 时间刻度
            textPaint.textAlign = Paint.Align.LEFT
            for (tick in 0..4) {
                val t = tMax * tick / 4f
                val x = xOf(t)
                drawLine(gridColor, Offset(x, padT), Offset(x, padT + ch), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    "${"%.1f".format(t)}s", x - 14f, padT + ch + 20f, textPaint
                )
            }

            // 曲线
            val line = Path()
            val fill = Path()
            points.forEachIndexed { i, p ->
                val x = xOf(p.timeSec)
                val y = yOf(p.speedKmh ?: 0f)
                if (i == 0) { line.moveTo(x, y); fill.moveTo(x, y) }
                else { line.lineTo(x, y); fill.lineTo(x, y) }
            }
            val lastX = xOf(points.last().timeSec)
            val lastY = yOf(points.last().speedKmh ?: 0f)
            fill.lineTo(lastX, padT + ch)
            fill.lineTo(xOf(points.first().timeSec), padT + ch)
            fill.close()
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    listOf(Color(0x553B82F6), Color(0x003B82F6))
                )
            )
            drawPath(line, color = Primary, style = Stroke(width = 4f))

            // 击球点
            val hitTimes = points.filter { it.isSmash }.map { it.timeSec }.toSet()
            hitTimes.forEach { t ->
                val x = xOf(t)
                drawCircle(Color.Black, radius = 9f, center = Offset(x, yOf(points.first { it.timeSec == t }.speedKmh ?: 0f)))
                drawCircle(Error, radius = 6f, center = Offset(x, yOf(points.first { it.timeSec == t }.speedKmh ?: 0f)))
            }
        }
    }
}
