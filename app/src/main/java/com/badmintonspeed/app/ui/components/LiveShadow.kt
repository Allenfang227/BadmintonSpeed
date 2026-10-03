package com.badmintonspeed.app.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badmintonspeed.app.ui.sensors.LocalGravity
import kotlin.math.sqrt

/**
 * v2.20 裸眼 3D 动态阴影
 *
 * 读取 LocalGravity（重力方向），阴影方向 = 重力反方向：
 *  阳光固定在屏幕 6 点钟方向斜射，手机倾斜时光影实时流动。
 *
 * 用法（顺序很重要）：
 *   Modifier.liveShadow(...).clip(...)   —— 阴影先画（可溢出边界），内容后被裁剪绘制
 */
@Composable
fun Modifier.liveShadow(
    cornerRadius: Dp = 20.dp,
    strengthDp: Dp = 9.dp,
    blurRadius: Float = 22f,
    alpha: Float = 0.42f
): Modifier {
    val gravity = LocalGravity.current
    return drawBehind {
        // 阴影方向 = -重力（重力 y 向下为正；竖直持机 → 影子朝屏幕上方）
        var dx = -gravity.x
        var dy = -gravity.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) {
            dx = 0f
            dy = -1f
        } else {
            dx /= len
            dy /= len
        }
        // 轻微压缩 x 分量：横屏持机时阴影更偏纵向，避免横向位移过猛
        val off = strengthDp.toPx()
        val topLeft = Offset(size.width * 0.03f + dx * off * 0.85f, size.height * 0.02f + dy * off)

        val nativePaint = android.graphics.Paint().apply {
            color = Color.Black.copy(alpha = alpha).toArgb()
            isAntiAlias = true
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val left = topLeft.x
            val top = topLeft.y
            val right = left + size.width
            val bottom = top + size.height
            val r = cornerRadius.toPx()
            native.drawRoundRect(left, top, right, bottom, r, r, nativePaint)
        }
    }
}

/** 便捷封装：动态阴影容器（阴影 + 内容，阴影在容器绘制之下） */
@Composable
fun LiveShadowBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier.liveShadow(cornerRadius = cornerRadius)
    ) {
        content()
    }
}
