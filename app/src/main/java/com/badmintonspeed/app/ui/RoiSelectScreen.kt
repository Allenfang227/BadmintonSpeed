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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v2.17 ROI 框选屏：在首帧上画凸多边形（≥4 点）框住目标场地。
 * 所有候选线段/关键点若落在多边形外直接丢弃——邻场线/广告/地板缝不再干扰。
 * 支持：双击撤销、双指缩放/拖动、完成确认。
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
            Button(onClick = onBack) { Text("返回") }
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    // 单击立即加点：不用 onDoubleTap（双击延迟会让点击"没反应"）
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
                detectTransformGestures { _, pan, zoom, _ ->
                    lastGestureMs = System.currentTimeMillis()
                    scale = (scale * zoom).coerceIn(0.4f, 8f)
                    panX += pan.x
                    panY += pan.y
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
            // 已选顶点 + 连线
            val display = points.map { p -> Offset(offX + p.x * fitScale * scale, offY + p.y * fitScale * scale) }
            for ((i, pt) in display.withIndex()) {
                val inside = points[i].x in 0f..bmpW && points[i].y in 0f..bmpH
                val c = if (inside) Color(0xFF4FC3F7) else Color(0xFFFF6D00)
                drawCircle(color = c, radius = 12f, center = pt, style = Stroke(width = 3f))
                drawCircle(color = c, radius = 5f, center = pt)
            }
            if (display.size >= 2) {
                for (i in 0 until display.size - 1) {
                    drawLine(color = Color(0xFF4FC3F7), start = display[i], end = display[i + 1], strokeWidth = 3f)
                }
            }
            if (display.size >= 4) {
                drawLine(color = Color(0xFF4FC3F7), start = display.last(), end = display.first(), strokeWidth = 3f)
            }
            // 场地线参考：预览帧叠加的半透明提示
            drawCircle(color = Color(0xFFFFEB3B), radius = 6f, center = Offset(offX + 0.3f * drawW, offY + 0.3f * drawH))
        }
        // 底部操作栏
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已选 ${points.size} 点（≥4 点闭合，单点加点，底部按钮撤销）", Modifier.weight(1f), fontSize = 13.sp,
                color = Color(0xFFB0BEC5))
            if (points.isNotEmpty()) {
                Button(onClick = { points.removeAt(points.size - 1) }, Modifier.padding(end = 8.dp)) { Text("撤销") }
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
