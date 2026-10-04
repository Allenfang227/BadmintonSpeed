package com.badmintonspeed.app.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.analysis.CourtAutoCalibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * v2.26 ROI 框选屏（大幅改进）：
 * 1) 进入自动识别场地 → 直接给出建议框（4 角点），不用从零画；
 * 2) 四个角点可单指拖动微调（"框选好用"）；
 * 3) 没有自动结果时再手动点击加点（≥4 点闭合）；
 * 4) 双指缩放/拖动画面查看细节；点过的点可底部撤销。
 */
@Composable
fun RoiSelectScreen(
    frame: android.graphics.Bitmap?,
    onSubmit: (List<PointF>) -> Unit,
    onBack: () -> Unit,
) {
    val bmp = frame ?: run {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("没有可用画面帧，请返回重试", Modifier.padding(24.dp))
            Button(onClick = onBack, modifier = Modifier) { Text("返回") }
        }
        return
    }
    val bmpW = bmp.width.toFloat()
    val bmpH = bmp.height.toFloat()
    val points = remember { mutableStateListOf<PointF>() }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var lastGestureMs by remember { mutableLongStateOf(0L) }
    var autoDetected by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(true) }

    // 进入页面自动跑一次场地识别，成功则预填建议框（核心改进：不用从零画）
    LaunchedEffect(bmp) {
        detecting = true
        val auto = withContext(Dispatchers.Default) {
            runCatching { CourtAutoCalibrator.calibrate(bmp, null) }.getOrNull()
        }
        detecting = false
        if (auto != null && auto.size >= 4) {
            points.clear()
            points.addAll(auto)
            autoDetected = true
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap ->
                        if (System.currentTimeMillis() - lastGestureMs < 150) return@detectTapGestures
                        val fitScale = minOf(size.width / bmpW, size.height / bmpH)
                        val drawW = bmpW * fitScale * scale
                        val drawH = bmpH * fitScale * scale
                        val offX = (size.width - drawW) / 2f + panX
                        val offY = (size.height - drawH) / 2f + panY
                        val bx = (tap.x - offX) / (fitScale * scale)
                        val by = (tap.y - offY) / (fitScale * scale)
                        // 允许框到画面外（场角没拍到时可以框出去）
                        if (bx in -0.4f * bmpW..1.4f * bmpW && by in -0.4f * bmpH..1.4f * bmpH) {
                            points.add(PointF(bx, by))
                        }
                    }
                )
            }.pointerInput(Unit) {
                // 单指拖动角点微调 / 双指缩放平移（角点可拖 = "框选好用"的关键）
                var dragIdx = -1
                var lastPan = Offset.Zero
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    lastGestureMs = System.currentTimeMillis()
                    val fitScale = minOf(size.width / bmpW, size.height / bmpH)
                    val drawW = bmpW * fitScale * scale
                    val drawH = bmpH * fitScale * scale
                    val offX = (size.width - drawW) / 2f + panX
                    val offY = (size.height - drawH) / 2f + panY
                    val dPan = pan - lastPan
                    lastPan = pan
                    val isDrag = zoom <= 1.02f && kotlin.math.abs(rotation) <= 0.05f
                    if (isDrag) {
                        if (dragIdx < 0) {
                            // 找到距离手指最近的角点（显示坐标 45px 内）
                            var best = -1
                            var bestD = 45f
                            for (i in points.indices) {
                                val px = offX + points[i].x * fitScale * scale
                                val py = offY + points[i].y * fitScale * scale
                                val dd = hypot((centroid.x - px).toDouble(), (centroid.y - py).toDouble()).toFloat()
                                if (dd < bestD) { bestD = dd; best = i }
                            }
                            dragIdx = best
                        }
                        if (dragIdx >= 0) {
                            // 拖动该角点（图像坐标）
                            points[dragIdx] = PointF(
                                points[dragIdx].x + dPan.x / (fitScale * scale),
                                points[dragIdx].y + dPan.y / (fitScale * scale)
                            )
                        } else {
                            panX += dPan.x
                            panY += dPan.y
                        }
                    } else {
                        dragIdx = -1
                        scale = (scale * zoom).coerceIn(0.4f, 8f)
                        panX += dPan.x
                        panY += dPan.y
                    }
                }
            }
        ) {
            val fitScale = minOf(size.width / bmpW, size.height / bmpH)
            val drawW = bmpW * fitScale * scale
            val drawH = bmpH * fitScale * scale
            val offX = (size.width - drawW) / 2f + panX
            val offY = (size.height - drawH) / 2f + panY
            drawImage(
                image = bmp.asImageBitmap(),
                dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                dstSize = IntSize(drawW.toInt(), drawH.toInt())
            )
            // 画面边界虚线
            drawRect(color = Color(0x66FFFFFF), topLeft = Offset(offX, offY),
                size = Size(drawW, drawH), style = Stroke(width = 2f))
            // 已选顶点 + 连线（拖动手柄：角点画大圆，可拖）
            val display = points.map { p -> Offset(offX + p.x * fitScale * scale, offY + p.y * fitScale * scale) }
            for ((i, pt) in display.withIndex()) {
                val inside = points[i].x in 0f..bmpW && points[i].y in 0f..bmpH
                val c = if (inside) Color(0xFF4FC3F7) else Color(0xFFFF6D00)
                drawCircle(color = c, radius = 14f, center = pt, style = Stroke(width = 3f))
                drawCircle(color = c, radius = 6f, center = pt)
                // 拖动提示
                drawContext.canvas.nativeCanvas.drawText(
                    if (autoDetected && points.size == 4 && i == 0) "拖动圆点微调" else "",
                    pt.x + 16f, pt.y - 14f,
                    android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = 22f; isAntiAlias = true }
                )
            }
            if (display.size >= 2) {
                for (i in 0 until display.size - 1) {
                    drawLine(color = Color(0xFF4FC3F7), start = display[i], end = display[i + 1], strokeWidth = 3f)
                }
            }
            if (display.size >= 4) {
                drawLine(color = Color(0xFF4FC3F7), start = display.last(), end = display.first(), strokeWidth = 3f)
            }
            // 场地线参考提示
            drawCircle(color = Color(0xFFFFEB3B), radius = 6f, center = Offset(offX + 0.3f * drawW, offY + 0.3f * drawH))
        }
        // 底部操作栏
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    detecting -> "正在自动识别场地…"
                    autoDetected -> "已自动识别，拖动圆点微调后点「完成」"
                    else -> "未自动识别到场地，请手动点击场地 4 个角点"
                },
                Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFFB0BEC5)
            )
            if (points.isNotEmpty()) {
                Button(onClick = { points.removeAt(points.size - 1); autoDetected = false },
                    Modifier.padding(end = 8.dp)) { Text("撤销") }
            }
            Button(
                onClick = { if (points.size >= 4) onSubmit(points.toList()) },
                enabled = points.size >= 4,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(if (points.size >= 4) "完成，按此框检测" else "还需 ${4 - points.size} 点")
            }
        }
        // 顶部返回
        Button(onClick = onBack, Modifier.align(Alignment.TopStart).padding(12.dp)) { Text("返回") }
    }
}
