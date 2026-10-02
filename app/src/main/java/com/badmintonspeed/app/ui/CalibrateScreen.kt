package com.badmintonspeed.app.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 手动标定场地4个角点（融合自 AI-YuJian-AI 的 annotate_court）：
 * ABC 三套自动检测全部失败后，显示视频第一帧，用户依次点击
 * 左上、右上、右下、左下 4个角点，确认后用 CourtMapper 透视变换补全完整场地线。
 */
@Composable
fun CalibrateScreen(vm: MainViewModel = viewModel()) {
    val frame by vm.calibrationFrame.collectAsState()
    val points = remember { mutableStateListOf<PointF>() }

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
            text = "AI 自动检测未成功，请依次点击场地 4 个角点：左上 → 右上 → 右下 → 左下",
            color = Color(0xFFB0BEC5),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(12.dp))
        ) {
            frame?.let { bmp ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // 用 Box 叠加 Image（显示视频帧）+ 透明 Canvas（画标记点和接收点击）
                    val imgScale = minOf(1f, 1f) // 占位，实际在 Canvas 里算
                    Box(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { tapOffset ->
                                        if (points.size < 4) {
                                            val scale = minOf(
                                                size.width / bmp.width.toFloat(),
                                                size.height / bmp.height.toFloat()
                                            )
                                            val drawW = bmp.width.toFloat() * scale
                                            val drawH = bmp.height.toFloat() * scale
                                            val offsetX = (size.width - drawW) / 2f
                                            val offsetY = (size.height - drawH) / 2f
                                            val bx = (tapOffset.x - offsetX) / scale
                                            val by = (tapOffset.y - offsetY) / scale
                                            if (bx in 0f..bmp.width.toFloat() && by in 0f..bmp.height.toFloat()) {
                                                points.add(PointF(bx, by))
                                            }
                                        }
                                    }
                                }
                        ) {
                            val scale = minOf(
                                size.width / bmp.width.toFloat(),
                                size.height / bmp.height.toFloat()
                            )
                            val drawW = bmp.width.toFloat() * scale
                            val drawH = bmp.height.toFloat() * scale
                            val offsetX = (size.width - drawW) / 2f
                            val offsetY = (size.height - drawH) / 2f
                            val displayPoints = points.map { p ->
                                Offset(offsetX + p.x * scale, offsetY + p.y * scale)
                            }
                            for (pt in displayPoints) {
                                drawCircle(
                                    color = Color(0xFFFFEB3B),
                                    radius = 10f,
                                    center = pt,
                                    style = Stroke(width = 3f)
                                )
                                drawCircle(color = Color(0xFFFFEB3B), radius = 4f, center = pt)
                            }
                            if (displayPoints.size >= 2) {
                                for (i in 0 until displayPoints.size - 1) {
                                    drawLine(
                                        color = Color(0xFFFFEB3B),
                                        start = displayPoints[i],
                                        end = displayPoints[i + 1],
                                        strokeWidth = 3f
                                    )
                                }
                            }
                            if (displayPoints.size == 4) {
                                drawLine(
                                    color = Color(0xFFFFEB3B),
                                    start = displayPoints[3],
                                    end = displayPoints[0],
                                    strokeWidth = 3f
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { if (points.isNotEmpty()) points.removeAt(points.size - 1) },
                enabled = points.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF263238),
                    contentColor = Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("撤销", fontSize = 14.sp)
            }
            Button(
                onClick = { points.clear() },
                enabled = points.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF263238),
                    contentColor = Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("重标", fontSize = 14.sp)
            }
            Button(
                onClick = {
                    if (points.size == 4) vm.submitManualCourtCorners(points.toList())
                },
                enabled = points.size == 4,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color.Black
                ),
                modifier = Modifier.weight(1.5f)
            ) {
                Text(if (points.size == 4) "确认并开始测速" else "已选 ${points.size}/4", fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { vm.cancelCalibration() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF78909C)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("取消", fontSize = 13.sp)
        }
    }
}
