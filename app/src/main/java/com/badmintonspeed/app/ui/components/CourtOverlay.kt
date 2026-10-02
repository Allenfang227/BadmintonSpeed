package com.badmintonspeed.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.badmintonspeed.app.analysis.CourtMapper
import com.badmintonspeed.app.domain.CourtResult
import kotlin.math.min

/**
 * 场地黄线常驻叠加层（v2.12 用户要求："场地显示了之后，就一直悬在上面就好了，不要闪一下就消失"）。
 * 在视频/图像上层一直显示完整标准场地线（4外边+单打边线+前发球线+双打后发球线+中线+球网），
 * 由 UI 层绘制，不依赖分析预览帧是否更新——标定完成后全程常驻。
 */
@Composable
fun CourtOverlay(court: CourtResult?, frameW: Int, frameH: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = court ?: return@Canvas
        if (frameW <= 0 || frameH <= 0) return@Canvas
        val scale = min(size.width / frameW, size.height / frameH)
        val dw = frameW * scale
        val dh = frameH * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f

        fun map(px: Float, py: Float): Offset = Offset(ox + px * scale, oy + py * scale)
        val mapper = try { CourtMapper(c.cornersPx) } catch (e: Exception) {
            // 透视异常：回退只画4角连线
            val pts = c.cornersPx
            if (pts.size >= 4) {
                drawLine(Color(0xFFFACC15), map(pts[0].x, pts[0].y), map(pts[1].x, pts[1].y), 3f)
                drawLine(Color(0xFFFACC15), map(pts[1].x, pts[1].y), map(pts[2].x, pts[2].y), 3f)
                drawLine(Color(0xFFFACC15), map(pts[2].x, pts[2].y), map(pts[3].x, pts[3].y), 3f)
                drawLine(Color(0xFFFACC15), map(pts[3].x, pts[3].y), map(pts[0].x, pts[0].y), 3f)
                for (pt in pts) drawCircle(Color(0xFFFACC15), radius = 6f, center = map(pt.x, pt.y))
            }
            return@Canvas
        }

        fun line(mx: Float, my: Float, nx: Float, ny: Float, color: Color, width: Float) {
            val a = mapper.courtToImage(mx, my)
            val b = mapper.courtToImage(nx, ny)
            drawLine(color, map(a.x, a.y), map(b.x, b.y), width)
        }

        // 外边界（双打场地 6.10 x 13.40）
        line(0f, 0f, 6.10f, 0f, Color(0xFFFACC15), 3f)
        line(6.10f, 0f, 6.10f, 13.40f, Color(0xFFFACC15), 3f)
        line(6.10f, 13.40f, 0f, 13.40f, Color(0xFFFACC15), 3f)
        line(0f, 13.40f, 0f, 0f, Color(0xFFFACC15), 3f)
        // 单打边线
        line(0.46f, 0f, 0.46f, 13.40f, Color(0xBBFACC15), 2f)
        line(5.64f, 0f, 5.64f, 13.40f, Color(0xBBFACC15), 2f)
        // 前发球线
        line(0f, 4.72f, 6.10f, 4.72f, Color(0xBBFACC15), 2f)
        line(0f, 8.68f, 6.10f, 8.68f, Color(0xBBFACC15), 2f)
        // 双打后发球线
        line(0f, 0.76f, 6.10f, 0.76f, Color(0x99FACC15), 1.5f)
        line(0f, 12.64f, 6.10f, 12.64f, Color(0x99FACC15), 1.5f)
        // 中线
        line(3.05f, 4.72f, 3.05f, 8.68f, Color(0xBBFACC15), 2f)
        // 球网（加粗，标注）
        line(0f, 6.70f, 6.10f, 6.70f, Color(0xFF22D3EE), 4f)
    }
}
