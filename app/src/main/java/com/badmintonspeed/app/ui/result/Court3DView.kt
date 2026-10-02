package com.badmintonspeed.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.TrailYellow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 右上角 3D 可拖拽实时模拟回放（图6-9）：
 * 羽毛球场地 3D 透视投影，支持拖拽旋转（水平转 yaw / 垂直转 pitch），
 * 随播放进度实时运动：球从远端飞向近端，还原整段飞行轨迹回放。
 */
@Composable
fun Court3DView(result: AnalysisResult, progressMs: Long, modifier: Modifier) {
    var yaw by remember { mutableStateOf(0f) }
    var pitch by remember { mutableStateOf(0.55f) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    yaw = (yaw + dragAmount.x * 0.008f) % (2f * PI.toFloat())
                    pitch = (pitch + dragAmount.y * 0.006f).coerceIn(0.25f, 1.15f)
                }
            }
    ) {
        if (size.width <= 0 || size.height <= 0) return@Canvas
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f + h * 0.06f

        // 世界坐标：双打场地 6.10m x 13.40m，y 近端=0、远端=13.4；z 向上
        val W = 6.10f
        val L = 13.40f
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosP = cos(pitch); val sinP = sin(pitch)

        fun project(x: Float, y: Float, z: Float): Offset {
            val dx = x - W / 2f
            val dy = y - L / 2f
            val rx = dx * cosY - dy * sinY
            val ry = dx * sinY + dy * cosY
            val sy = ry * cosP - z * sinP
            val depth = ry * sinP + z * cosP
            val persp = 1f / (1f + depth / 40f)
            val zoom = min(w, h) * 0.9f
            return Offset(cx + rx * persp * zoom / 1.8f, cy + sy * persp * zoom / 1.8f)
        }

        // ---- 场地绘制 ----
        val lineColor = Color(0xAAFFFFFF)
        val s2 = 1.5f

        // 外框（双打全场）
        drawLine(lineColor, project(0f, 0f, 0f), project(W, 0f, 0f), s2)
        drawLine(lineColor, project(W, 0f, 0f), project(W, L, 0f), s2)
        drawLine(lineColor, project(W, L, 0f), project(0f, L, 0f), s2)
        drawLine(lineColor, project(0f, L, 0f), project(0f, 0f, 0f), s2)

        // 中线
        drawLine(Color(0x88FFFFFF), project(W / 2f, 0f, 0f), project(W / 2f, L, 0f), 1.2f)
        // 网（中部）
        drawLine(Primary, project(0f, L / 2f, 0f), project(W, L / 2f, 0f), 3.5f)
        // 前发球线 / 后发球线
        drawLine(Color(0x66FFFFFF), project(0f, L * 0.28f, 0f), project(W, L * 0.28f, 0f), 1f)
        drawLine(Color(0x66FFFFFF), project(0f, L * 0.72f, 0f), project(W, L * 0.72f, 0f), 1f)
        // 单打边线
        drawLine(Color(0x55FFFFFF), project(W * 0.21f, 0f, 0f), project(W * 0.21f, L, 0f), 0.8f)
        drawLine(Color(0x55FFFFFF), project(W * 0.79f, 0f, 0f), project(W * 0.79f, L, 0f), 0.8f)

        // ---- 轨迹回放（黄色流光，随进度实时运动） ----
        val pts = result.trajectory
        if (pts.size >= 2) {
            val tNow = progressMs / 1000.0
            val visible = pts.filter { it.timeSec <= tNow + 0.03 }

            // 已飞行轨迹线（黄色光带）
            for (i in 1 until visible.size) {
                val a = project(visible[i - 1].courtX, visible[i - 1].courtY, 0f)
                val b = project(visible[i].courtX, visible[i].courtY, 0f)
                drawLine(
                    color = Color(0xFFFFD60A).copy(alpha = 0.75f),
                    start = a,
                    end = b,
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
            }

            // 当前球位（抛物线高度模拟 + 光晕）
            val cur = visible.lastOrNull()
            if (cur != null) {
                val tailLen = pts.last().timeSec - pts.first().timeSec
                val frac = ((cur.timeSec - pts.first().timeSec) / tailLen.coerceAtLeast(0.001)).toFloat()
                val hz = 1.6f * sin(PI * frac.coerceIn(0f, 1f)).toFloat()
                val p = project(cur.courtX, cur.courtY, hz)
                drawCircle(Color(0x33FFD60A), radius = 16f, center = p)
                drawCircle(Color(0x66FFD60A), radius = 9f, center = p)
                drawCircle(TrailYellow, radius = 5f, center = p)
            }
        }

        // 落点标记（最后一个轨迹点 IN/OUT）
        pts.lastOrNull()?.let { last ->
            val lp = project(last.courtX, last.courtY, 0f)
            val inCourt = last.courtX in 0f..6.10f && last.courtY in 0f..13.40f
            drawCircle(
                color = if (inCourt) Color(0xFF4ADE80) else Color(0xFFEF4444),
                radius = 7f,
                center = lp,
                style = Stroke(width = 2.5f)
            )
        }

        // 标注
        val tagPaint = android.graphics.Paint().apply {
            color = 0xFF9FB3A7.toInt()
            textSize = 16f
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText("360°拖动旋转", 14f, h - 12f, tagPaint)
    }
}
