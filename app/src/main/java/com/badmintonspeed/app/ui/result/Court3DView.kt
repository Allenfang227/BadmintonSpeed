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
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.TrailYellow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 右上角 3D 实时模拟回放 v2（用户要求"不是几条线，是真正意义上的场地模拟"）：
 * - 完整标准球场：外边界、双打后发球线、前发球线、单打边线、中线、球网（加粗+立柱+"球网"标注）
 * - 只画当前选中球的轨迹（从击球点→落点），不是所有轨迹混在一起
 * - 落点 IN/OUT 大标记 + 文字（绿=界内 / 红=界外）
 * - SHOT n/m 球号标注；支持 360° 拖拽旋转
 */
@Composable
fun Court3DView(
    result: AnalysisResult,
    currentHit: HitAnalysis?,
    hitIndex: Int,
    hitCount: Int,
    progressMs: Long,
    modifier: Modifier
) {
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

        // ---- 完整场地绘制（标准羽毛球场所有线） ----
        val lineColor = Color(0xAAFFFFFF)
        val s2 = 1.5f

        // 外边界（双打全场 6.10 x 13.40）
        drawLine(lineColor, project(0f, 0f, 0f), project(W, 0f, 0f), s2)
        drawLine(lineColor, project(W, 0f, 0f), project(W, L, 0f), s2)
        drawLine(lineColor, project(W, L, 0f), project(0f, L, 0f), s2)
        drawLine(lineColor, project(0f, L, 0f), project(0f, 0f, 0f), s2)

        // 中线
        drawLine(Color(0x88FFFFFF), project(W / 2f, 0f, 0f), project(W / 2f, L, 0f), 1.2f)
        // 前发球线（4.72 / 8.68）
        drawLine(Color(0x66FFFFFF), project(0f, 4.72f, 0f), project(W, 4.72f, 0f), 1f)
        drawLine(Color(0x66FFFFFF), project(0f, 8.68f, 0f), project(W, 8.68f, 0f), 1f)
        // 双打后发球线（0.76 / 12.64）
        drawLine(Color(0x66FFFFFF), project(0f, 0.76f, 0f), project(W, 0.76f, 0f), 1f)
        drawLine(Color(0x66FFFFFF), project(0f, 12.64f, 0f), project(W, 12.64f, 0f), 1f)
        // 单打边线（0.46 / 5.64）
        drawLine(Color(0x55FFFFFF), project(0.46f, 0f, 0f), project(0.46f, L, 0f), 0.8f)
        drawLine(Color(0x55FFFFFF), project(5.64f, 0f, 0f), project(5.64f, L, 0f), 0.8f)

        // ---- 球网（加粗 + 立柱 + 标注，用户要求"加上球网的标注"） ----
        val netY = 6.70f
        val netP1 = project(0f, netY, 0.3f)
        val netP2 = project(W, netY, 0.3f)
        drawLine(Primary, netP1, netP2, 6f, cap = StrokeCap.Round)
        // 网下沿 + 两侧立柱
        drawLine(Color(0x99FFFFFF), project(0f, netY, 0f), project(W, netY, 0f), 1f)
        drawLine(Primary, project(0f, netY, 0f), netP1, 4f)
        drawLine(Primary, project(W, netY, 0f), netP2, 4f)

        // ---- 当前选中球的轨迹（从击球点→落点） ----
        currentHit?.let { hit ->
            val traj = hit.trajectory
            if (traj.size >= 2) {
                // 飞行轨迹（黄色光带）：近端→远端 z 抛物线模拟
                val t0 = traj.first().timeSec
                val t1 = traj.last().timeSec
                val span = (t1 - t0).coerceAtLeast(0.001)
                for (i in 1 until traj.size) {
                    val a = traj[i - 1]
                    val b = traj[i]
                    // 抛物线高度：轨迹中点最高
                    val za = 1.8f * sin(PI.toFloat() * ((a.timeSec - t0) / span).toFloat().coerceIn(0f, 1f))
                    val zb = 1.8f * sin(PI.toFloat() * ((b.timeSec - t0) / span).toFloat().coerceIn(0f, 1f))
                    drawLine(
                        color = Color(0xFFFFD60A).copy(alpha = 0.85f),
                        start = project(a.courtX, a.courtY, za),
                        end = project(b.courtX, b.courtY, zb),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
                // 当前播放进度对应的球位
                val tNow = progressMs / 1000.0
                val visible = traj.filter { it.timeSec <= tNow + 0.03 }
                visible.lastOrNull()?.let { cur ->
                    val frac = ((cur.timeSec - t0) / span).toFloat().coerceIn(0f, 1f)
                    val hz = 1.8f * sin(PI * frac).toFloat()
                    val p = project(cur.courtX, cur.courtY, hz)
                    drawCircle(Color(0x33FFD60A), radius = 16f, center = p)
                    drawCircle(Color(0x66FFD60A), radius = 9f, center = p)
                    drawCircle(TrailYellow, radius = 5f, center = p)
                }
            }

            // ---- 落点 IN/OUT 大标记 ----
            val land = traj.lastOrNull()
            if (land != null) {
                val inCourt = land.courtX in 0f..6.10f && land.courtY in 0f..13.40f
                val landColor = if (inCourt) Color(0xFF4ADE80) else Color(0xFFEF4444)
                val lp = project(land.courtX, land.courtY, 0f)
                drawCircle(landColor, radius = 10f, center = lp, style = Stroke(width = 3.5f))
                drawCircle(landColor, radius = 4f, center = lp)
                val label = if (inCourt) "IN" else "OUT"
                val labelPaint = android.graphics.Paint().apply {
                    setColor(if (inCourt) 0xFF4ADE80.toInt() else 0xFFEF4444.toInt())
                    textSize = 18f
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    lp.x + 12f,
                    lp.y + 6f,
                    labelPaint
                )
            }
        }

        // ---- 标注：球号 + 旋转提示 ----
        val tagPaint = android.graphics.Paint().apply {
            color = 0xFF9FB3A7.toInt()
            textSize = 15f
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(
            "SHOT ${hitIndex + 1}/$hitCount",
            14f,
            20f,
            tagPaint
        )
        drawContext.canvas.nativeCanvas.drawText("360°拖动旋转", 14f, h - 12f, tagPaint)
    }
}
