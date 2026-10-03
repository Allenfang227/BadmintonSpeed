package com.badmintonspeed.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary

/**
 * v2.19 液态玻璃（Liquid Glass）观感组件
 *
 * 复刻 HDS「沉浸光感」的安卓近似实现：
 * 玻璃主体高透渐变 + 顶部高光折射层 + 白色细描边 + 内部绿色微光，
 * 叠在深色背景上呈现"流动玻璃"质感（非普通毛玻璃，强调高光与折射而非模糊）。
 */
fun Modifier.liquidGlass(
    cornerRadius: Dp = 22.dp,
    tint: Color = Color(0xFF3EE08F)
): Modifier = drawBehind {
    val r = cornerRadius.toPx()
    val stroke1 = 1.dp.toPx()
    val stroke2 = 2.dp.toPx()
    // 1) 玻璃主体：顶部高透、向下渐隐的半透明白（透出深色背景 = 玻璃的通透感）
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.04f))
        ),
        cornerRadius = CornerRadius(r, r)
    )
    // 2) 顶部高光层：液态玻璃的折射辉光（上亮下灭）
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.36f),
            0.45f to Color.White.copy(alpha = 0.0f)
        ),
        cornerRadius = CornerRadius(r, r)
    )
    // 3) 白色细描边：边缘光
    drawRoundRect(
        color = Color.White.copy(alpha = 0.32f),
        style = Stroke(width = stroke1),
        cornerRadius = CornerRadius(r, r)
    )
    // 4) 内部折射微光：左右边缘的绿色流光（玻璃内部的色散感）
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(tint.copy(alpha = 0.16f), Color.Transparent, tint.copy(alpha = 0.16f))
        ),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = stroke2)
    )
    // 5) 底部柔和落地影：玻璃厚度感
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = 0.18f)
        ),
        cornerRadius = CornerRadius(r, r)
    )
}

/** 液态玻璃卡片容器（替代普通 Card）——叠加陀螺仪动态阴影（裸眼 3D） */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .liveShadow(cornerRadius = cornerRadius, strengthDp = 11.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .liquidGlass(cornerRadius = cornerRadius)
    ) {
        content()
    }
}

/**
 * 底部悬浮液态玻璃导航栏（v2.19）
 * 对应 HDS「自适应悬浮导航」：悬浮于内容之上、圆角液态玻璃、底部留白；
 * 页面之间做横向平移切换（HorizontalPager 联动）。
 */
@Composable
fun LiquidGlassNavBar(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .liveShadow(cornerRadius = 30.dp, strengthDp = 10.dp)
                .clip(RoundedCornerShape(30.dp))
                .liquidGlass(cornerRadius = 30.dp)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, label ->
                val isSel = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .then(
                            if (isSel) Modifier.background(Color.White.copy(alpha = 0.13f))
                            else Modifier
                        )
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 状态点（选中实心绿 / 未选中空心灰）
                        Text(
                            if (isSel) "●" else "○",
                            color = if (isSel) Primary else OnSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Text(
                            label,
                            color = if (isSel) Color.White else OnSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
