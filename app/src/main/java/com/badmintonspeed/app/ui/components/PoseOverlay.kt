package com.badmintonspeed.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.badmintonspeed.app.analysis.PoseDetector
import com.badmintonspeed.app.domain.PoseFrameData
import kotlin.math.min

/**
 * 骨骼叠加层（v2.12 用户要求："骨骼识别也要一直随视频播放，一直在上面，动态的"）。
 * 按当前播放进度取最近骨骼快照，把多人骨架（青绿色）画在视频上，随进度动态更新。
 */
@Composable
fun PoseOverlay(
    poseFrames: List<PoseFrameData>,
    progressMs: Long,
    frameW: Int,
    frameH: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (frameW <= 0 || frameH <= 0 || poseFrames.isEmpty()) return@Canvas
        val tNow = progressMs / 1000.0
        // 取最近（不超过当前时刻）的骨骼快照
        val snap = poseFrames.lastOrNull { it.timeSec <= tNow + 0.03 }
            ?: poseFrames.firstOrNull() ?: return@Canvas

        val scale = min(size.width / frameW, size.height / frameH)
        val dw = frameW * scale
        val dh = frameH * scale
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f
        fun map(px: Float, py: Float): Offset = Offset(ox + px * scale, oy + py * scale)

        val boneColor = Color(0xFF22D3EE)
        val jointColor = Color(0xFF67E8F9)
        for (skel in snap.skeletons) {
            // 骨架线
            for (conn in PoseDetector.CONNECTIONS) {
                val a = skel.points.getOrNull(conn[0]) ?: continue
                val b = skel.points.getOrNull(conn[1]) ?: continue
                if (a.visibility > 0.3f && b.visibility > 0.3f) {
                    drawLine(boneColor, map(a.x, a.y), map(b.x, b.y), 3f)
                }
            }
            // 关节点
            for (pt in skel.points) {
                if (pt.visibility > 0.3f) {
                    drawCircle(jointColor, radius = 4.5f, center = map(pt.x, pt.y))
                }
            }
        }
    }
}
