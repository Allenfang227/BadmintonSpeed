package com.badmintonspeed.app.ui.calibrate

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.computeScale
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Success
import com.badmintonspeed.app.ui.theme.Warning
import com.badmintonspeed.app.ui.theme.Error

private val cornerColors = listOf(Success, Primary, Warning, Error)

@Composable
fun CalibrateScreen(vm: MainViewModel) {
    val bitmap by vm.previewBitmap.collectAsState()
    val corners by vm.corners.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("标定场地四角", style = MaterialTheme.typography.titleLarge, color = OnBackground, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "请按顺序点击场地四个角：左上 → 右上 → 右下 → 左下（对应标准双打场地 6.10m × 13.40m）",
            color = OnSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))

        val bmp = bitmap
        var boxPx by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { boxPx = it }
                .pointerInput(bmp, boxPx) {
                    if (bmp != null) {
                        detectTapGestures { offset ->
                            if (boxPx.width > 0) {
                                val info = computeScale(bmp.width, bmp.height, boxPx.width, boxPx.height)
                                val bx = (offset.x - info.offsetX) / info.scale
                                val by = (offset.y - info.offsetY) / info.scale
                                if (bx in 0f..bmp.width.toFloat() && by in 0f..bmp.height.toFloat()) {
                                    vm.addCorner(android.graphics.PointF(bx, by))
                                }
                            }
                        }
                    }
                }
        ) {
            if (bmp != null) {
                val info = computeScale(bmp.width, bmp.height, boxPx.width, boxPx.height)
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(info.offsetX.toInt(), info.offsetY.toInt()),
                        dstSize = IntSize(info.width, info.height)
                    )
                    // 场地边框
                    if (corners.size >= 2) {
                        val pts = corners.map { Offset(info.offsetX + it.x * info.scale, info.offsetY + it.y * info.scale) }
                        val path = androidx.compose.ui.graphics.Path()
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                        if (pts.size == 4) path.close()
                        drawPath(path, color = Warning, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
                    }
                    // 角点标记 + 序号
                    val textPaint = Paint().apply {
                        color = 0xFFFFFFFF.toInt()
                        textSize = 34f
                        isFakeBoldText = true
                        textAlign = Paint.Align.CENTER
                    }
                    corners.forEachIndexed { i, p ->
                        val c = Offset(info.offsetX + p.x * info.scale, info.offsetY + p.y * info.scale)
                        drawCircle(cornerColors[i], radius = 20f, center = c)
                        drawCircle(Color.Black, radius = 22f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                        drawContext.canvas.nativeCanvas.drawText(
                            "${i + 1}", c.x, c.y + 12f, textPaint
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载预览帧中…", color = OnSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("已标定 ${corners.size}/4", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        RowButtons(vm, corners.size) { vm.goTo(com.badmintonspeed.app.ui.Screen.Home) }
    }
}

@Composable
private fun RowButtons(vm: MainViewModel, cornerCount: Int, onBack: () -> Unit) {
    val enabled = cornerCount == 4
    Button(
        onClick = { vm.startAnalysis() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            disabledContainerColor = Primary.copy(alpha = 0.35f)
        )
    ) {
        Text(if (enabled) "开始测速分析" else "点击画面标定场地四角", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { if (cornerCount > 0) vm.undoCorner() else onBack() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (cornerCount > 0) "撤销上一个角点" else "返回首页")
    }
}
