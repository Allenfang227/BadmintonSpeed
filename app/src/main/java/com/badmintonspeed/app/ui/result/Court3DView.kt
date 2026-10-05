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
import com.badmintonspeed.app.analysis.Homography
import com.badmintonspeed.app.analysis.PoseKeyPoint
import com.badmintonspeed.app.domain.AnalysisResult
import com.badmintonspeed.app.domain.HitAnalysis
import com.badmintonspeed.app.domain.PoseFrameData
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
    poseFrames: List<PoseFrameData> = emptyList(),
    // v2.43：视角状态提升到结果页，小窗与全屏共享——点进全屏保持当前视角，不重置、不"换视角"
    yaw: Float,
    pitch: Float,
    onViewChange: (newYaw: Float, newPitch: Float) -> Unit,
    modifier: Modifier
) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val ny = (yaw + dragAmount.x * 0.008f) % (2f * PI.toFloat())
                    val np = (pitch + dragAmount.y * 0.006f).coerceIn(0.22f, 1.15f)
                    onViewChange(ny, np)
                }
            }
    ) {
        if (size.width <= 0 || size.height <= 0) return@Canvas
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 世界坐标：双打场地 6.10m x 13.40m，y 近端=0、远端=13.4；z 向上
        val W = 6.10f
        val L = 13.40f
        val cosY = cos(yaw); val sinY = sin(yaw)
        val cosP = cos(pitch); val sinP = sin(pitch)

        // v2.40 自适应缩放：让场地长边（俯仰投影后）适配屏幕高度、短边适配宽度，
        // 取较小者保证整块场地（含网高）完整可见，不再用固定 min(w,h)*0.5 导致全屏错乱。
        val fitH = h * 0.82f / (L * cosP + 1.2f)   // 纵向（含网高余量）
        val fitW = w * 0.92f / W                   // 横向
        val baseScale = min(fitH, fitW)

        fun project(x: Float, y: Float, z: Float): Offset {
            val dx = x - W / 2f
            val dy = y - L / 2f
            val rx = dx * cosY - dy * sinY
            val ry = dx * sinY + dy * cosY
            val sy = ry * cosP - z * sinP
            val depth = ry * sinP + z * cosP
            val persp = 1f / (1f + depth / 45f)
            return Offset(cx + rx * persp * baseScale, cy + sy * persp * baseScale)
        }

        // ---- v2.35 球场地面填充（半透明深色，让场地有体积感而非空框） ----
        val groundPath = androidx.compose.ui.graphics.Path().apply {
            val p0 = project(0f, 0f, 0f)
            val p1 = project(W, 0f, 0f)
            val p2 = project(W, L, 0f)
            val p3 = project(0f, L, 0f)
            moveTo(p0.x, p0.y)
            lineTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            close()
        }
        drawPath(groundPath, Color(0x223B82F6))  // 半透明深蓝（模拟蓝色场地）

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

        // ---- 球网（加粗 + 立柱 + 网孔，用户要求"加上球网的标注"，v2.25 加网格纹理更醒目） ----
        val netY = 6.70f
        val netP1 = project(0f, netY, 0.35f)
        val netP2 = project(W, netY, 0.35f)
        drawLine(Primary, netP1, netP2, 8f, cap = StrokeCap.Round)
        // 网下沿 + 两侧立柱
        drawLine(Color(0x99FFFFFF), project(0f, netY, 0f), project(W, netY, 0f), 1.5f)
        drawLine(Primary, project(0f, netY, 0f), netP1, 5f)
        drawLine(Primary, project(W, netY, 0f), netP2, 5f)
        // v2.25 网孔纹理：沿网横向等分画垂直短线（模拟网眼），让网一眼可见
        val meshSegs = 7
        for (i in 1 until meshSegs) {
            val fx = W * i / meshSegs
            drawLine(
                Color(0x88FFFFFF),
                project(fx, netY, 0f),
                project(fx, netY, 0.30f),
                1f
            )
        }

        // ---- v2.25 空间立体：完整 33 点骨骼映射进 3D 场地（脚部→场地坐标，全身骨架连线） ----
        if (poseFrames.isNotEmpty() && result.court != null) {
            val tNow = progressMs / 1000.0
            val snap = poseFrames.lastOrNull { it.timeSec <= tNow + 0.03 }
            if (snap != null) {
                val h = result.court.homography
                for (skel in snap.skeletons) {
                    // 脚部锚点：脚跟(29/30)→脚踝(27/28)→最下可见点
                    val footIdx = when {
                        (skel.points.getOrNull(29)?.visibility ?: 0f) > 0.3f -> 29
                        (skel.points.getOrNull(30)?.visibility ?: 0f) > 0.3f -> 30
                        (skel.points.getOrNull(27)?.visibility ?: 0f) > 0.3f -> 27
                        (skel.points.getOrNull(28)?.visibility ?: 0f) > 0.3f -> 28
                        else -> (skel.points.indices.maxByOrNull { skel.points[it].visibility } ?: 0)
                    }
                    val foot = skel.points.getOrNull(footIdx) ?: continue
                    if (foot.visibility <= 0.1f) continue
                    val footCourt = Homography.pixelToCourt(h, foot.x, foot.y)
                    // 身高基准：可见关键点中最高点→头
                    val vis = skel.points.filter { it.visibility > 0.3f }
                    if (vis.isEmpty()) continue
                    val topY = vis.minOf { it.y }
                    val heightPx = (foot.y - topY).coerceIn(0f, 400f)
                    fun p3d(pt: PoseKeyPoint?): Offset? {
                        if (pt == null || pt.visibility <= 0.2f) return null
                        val c = Homography.pixelToCourt(h, pt.x, pt.y)
                        val z = (foot.y - pt.y).coerceIn(-50f, 400f) / heightPx.coerceAtLeast(1f) * 1.6f
                        return project(c.x, c.y, z.coerceAtLeast(0f))
                    }
                    // 画完整骨架连线（头-肩-肘-腕 / 髋-膝-踝 / 躯干）
                    val conn = arrayOf(
                        0 to 7, 7 to 11, 11 to 12, 12 to 8, 8 to 0,   // 头肩环
                        11 to 13, 13 to 15, 12 to 14, 14 to 16,       // 上肢
                        11 to 23, 12 to 24, 23 to 24,                 // 躯干
                        23 to 25, 25 to 27, 27 to 29, 24 to 26, 26 to 28, 28 to 30 // 下肢
                    )
                    for ((a, b) in conn) {
                        val pa = p3d(skel.points.getOrNull(a)) ?: continue
                        val pb = p3d(skel.points.getOrNull(b)) ?: continue
                        drawLine(Color(0xFF22D3EE).copy(alpha = 0.9f), pa, pb, 2.5f)
                    }
                    // 关节点
                    for (pt in skel.points) {
                        val pp = p3d(pt) ?: continue
                        drawCircle(Color(0xFF67E8F9), radius = 3f, center = pp)
                    }
                }
            }
        }

        // ---- 当前选中球的轨迹（从击球点→落点） ----
        currentHit?.let { hit ->
            val traj = hit.trajectory
            if (traj.size >= 2) {
                // v2.35 飞行轨迹（渐变光带：起点暗→当前点亮，模拟球拖尾）
                for (i in 1 until traj.size) {
                    val a = traj[i - 1]
                    val b = traj[i]
                    val frac = i.toFloat() / traj.size
                    drawLine(
                        color = Color(0xFFFFD60A).copy(alpha = 0.25f + 0.65f * frac),
                        start = project(a.courtX, a.courtY, a.zMeters),
                        end = project(b.courtX, b.courtY, b.zMeters),
                        strokeWidth = 2f + 4f * frac,
                        cap = StrokeCap.Round
                    )
                }
                // 当前播放进度对应的球位（v2.35：地面阴影 + 三层发光 + 核心白球）
                val tNow = progressMs / 1000.0
                val visible = traj.filter { it.timeSec <= tNow + 0.03 }
                visible.lastOrNull()?.let { cur ->
                    // 地面阴影（球正下方 z=0）
                    val shadowP = project(cur.courtX, cur.courtY, 0f)
                    drawCircle(Color(0x55000000), radius = 7f, center = shadowP)
                    // 三层发光
                    val p = project(cur.courtX, cur.courtY, cur.zMeters)
                    drawCircle(Color(0x22FFD60A), radius = 22f, center = p)
                    drawCircle(Color(0x55FFD60A), radius = 13f, center = p)
                    drawCircle(Color(0xAAFFFFFF), radius = 7f, center = p)
                    drawCircle(TrailYellow, radius = 4f, center = p)
                }
            }

            // ---- 落点 IN/OUT 大标记 ----
            val land = traj.lastOrNull()
            if (land != null) {
                val inCourt = land.groundX in 0f..6.10f && land.groundY in 0f..13.40f
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
