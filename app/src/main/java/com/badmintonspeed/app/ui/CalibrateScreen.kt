package com.badmintonspeed.app.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.min
import com.badmintonspeed.app.ui.components.liveShadow

/**
 * 手动标定场地4个角点 v2（修复用户实测反馈）：
 *   1. 场外扩展点击区：场地角没拍到画面时，点击图片四周的黑色区域，
 *      角点坐标可超出画面边界（-0.4~1.4 倍），CourtMapper 透视外推自动延伸
 *   2. 双指缩放 + 拖动：放大画面精确点角点（scale 0.5x~4x）
 *   3. 点完4角后 AI 会自动在框内精修贴合线（见 VideoAnalyzer LocalLineRefiner）
 */
@Composable
fun CalibrateScreen(vm: MainViewModel = viewModel()) {
    val frame by vm.calibrationFrame.collectAsState()
    val points = remember { mutableStateListOf<PointF>() }
    var scale by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var lastGestureMs by remember { mutableStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1F14))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "手动标定场地",
            color = Color(0xFF00E676),
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "依次点击 4 个角点：左上 → 右上 → 右下 → 左下；角没拍到时点画面外黑色区域；双指缩放可放大精确点",
            color = Color(0xFFB0BEC5),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111111), RoundedCornerShape(12.dp))
        ) {
            frame?.let { bmp ->
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()
                // 手势层：双指缩放/拖动 + 单指点击
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 4f)
                                panX += pan.x
                                panY += pan.y
                                lastGestureMs = System.currentTimeMillis()
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { tap ->
                                // 刚拖动/缩放完的 150ms 内忽略误触
                                if (System.currentTimeMillis() - lastGestureMs < 150) return@detectTapGestures
                                if (points.size < 4) {
                                    val fitScale = minOf(size.width / bmpW, size.height / bmpH)
                                    val drawW = bmpW * fitScale * scale
                                    val drawH = bmpH * fitScale * scale
                                    val offX = (size.width - drawW) / 2f + panX
                                    val offY = (size.height - drawH) / 2f + panY
                                    val bx = (tap.x - offX) / (fitScale * scale)
                                    val by = (tap.y - offY) / (fitScale * scale)
                                    // 允许场外：范围扩展到画面的 -0.4~1.4 倍（对应黑色扩展区）
                                    if (bx in -0.4f * bmpW..1.4f * bmpW &&
                                        by in -0.4f * bmpH..1.4f * bmpH
                                    ) {
                                        points.add(PointF(bx, by))
                                    }
                                }
                            }
                        }
                ) {
                    val fitScale = minOf(size.width / bmpW, size.height / bmpH)
                    val drawW = bmpW * fitScale * scale
                    val drawH = bmpH * fitScale * scale
                    val offX = (size.width - drawW) / 2f + panX
                    val offY = (size.height - drawH) / 2f + panY

                    // 视频帧
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(offX.toInt(), offY.toInt()),
                        dstSize = IntSize(drawW.toInt(), drawH.toInt())
                    )

                    // 场外扩展区提示（半透明虚线边框：画面边界）
                    drawRect(
                        color = Color(0x66FFFFFF),
                        topLeft = Offset(offX, offY),
                        size = androidx.compose.ui.geometry.Size(drawW, drawH),
                        style = Stroke(width = 2f)
                    )

                    // 已点击的角点 + 连线
                    val displayPoints = points.map { p ->
                        Offset(offX + p.x * fitScale * scale, offY + p.y * fitScale * scale)
                    }
                    for ((i, pt) in displayPoints.withIndex()) {
                        val inside = points[i].x in 0f..bmpW && points[i].y in 0f..bmpH
                        if (inside) {
                            drawCircle(color = Color(0xFFFFEB3B), radius = 10f, center = pt, style = Stroke(width = 3f))
                            drawCircle(color = Color(0xFFFFEB3B), radius = 4f, center = pt)
                        } else {
                            // 场外角点：空心圈表示在画面外
                            drawCircle(color = Color(0xFFFF6D00), radius = 10f, center = pt, style = Stroke(width = 3f))
                        }
                    }
                    if (displayPoints.size >= 2) {
                        for (i in 0 until displayPoints.size - 1) {
                            drawLine(color = Color(0xFFFFEB3B), start = displayPoints[i], end = displayPoints[i + 1], strokeWidth = 3f)
                        }
                    }
                    if (displayPoints.size == 4) {
                        drawLine(color = Color(0xFFFFEB3B), start = displayPoints[3], end = displayPoints[0], strokeWidth = 3f)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { scale = 1f; panX = 0f; panY = 0f },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF263238), contentColor = Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f).liveShadow(cornerRadius = 12.dp, strengthDp = 4.dp, alpha = 0.35f)
            ) { Text("重置视图", fontSize = 13.sp) }
            Button(
                onClick = { if (points.isNotEmpty()) points.removeAt(points.size - 1) },
                enabled = points.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF263238), contentColor = Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f).liveShadow(cornerRadius = 12.dp, strengthDp = 4.dp, alpha = 0.35f)
            ) { Text("撤销", fontSize = 13.sp) }
            Button(
                onClick = { points.clear() },
                enabled = points.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF263238), contentColor = Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f).liveShadow(cornerRadius = 12.dp, strengthDp = 4.dp, alpha = 0.35f)
            ) { Text("重标", fontSize = 13.sp) }
            Button(
                onClick = { if (points.size == 4) vm.submitManualCourtCorners(points.toList()) },
                enabled = points.size == 4,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676), contentColor = Color.Black
                ),
                modifier = Modifier.weight(1.6f).liveShadow(cornerRadius = 12.dp, strengthDp = 5.dp, alpha = 0.45f)
            ) { Text(if (points.size == 4) "确认（AI精修）" else "已选 ${points.size}/4", fontSize = 13.sp) }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = { vm.cancelCalibration() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent, contentColor = Color(0xFF78909C)
            ),
            modifier = Modifier.fillMaxWidth().liveShadow(cornerRadius = 12.dp, strengthDp = 4.dp, alpha = 0.3f)
        ) { Text("取消", fontSize = 13.sp) }
    }
}
