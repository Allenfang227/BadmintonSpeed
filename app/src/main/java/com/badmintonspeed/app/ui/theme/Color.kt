package com.badmintonspeed.app.ui.theme

import androidx.compose.ui.graphics.Color

// 深绿 + 黑深色主题（参考图 2/3/4/5：深绿色主视觉）
val Primary = Color(0xFF22C55E)          // 亮绿：主按钮/高亮
val OnPrimary = Color(0xFF06120A)
val PrimaryContainer = Color(0xFF14532D)
val OnPrimaryContainer = Color(0xFFBBF7D0)
val Secondary = Color(0xFF4ADE80)
val OnSecondary = Color(0xFF052E16)
val SecondaryContainer = Color(0xFF14532D)
val OnSecondaryContainer = Color(0xFFBBF7D0)
val Background = Color(0xFF0A1410)       // 深绿黑：内容区底
val OnBackground = Color(0xFFE7F0EA)
val Surface = Color(0xFF0E1B15)          // 侧栏/卡片底
val OnSurface = Color(0xFFF0F7F2)
val SurfaceVariant = Color(0xFF16281F)   // 卡片/输入底
val OnSurfaceVariant = Color(0xFF9FB3A7)
val SurfaceBright = Color(0xFF1C352A)
val DividerColor = Color(0xFF1E3327)
val Error = Color(0xFFEF4444)
val OnError = Color.White
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFFACC15)

// 黄色流光轨迹（图6-9 效果）
val TrailYellow = Color(0xFFFFD60A)
val TrailYellowSoft = Color(0x66FFD60A)

// 速度专用颜色
object SpeedColors {
    val Slow = Color(0xFF22C55E)    // 绿：慢
    val Medium = Color(0xFFFACC15)  // 黄：中
    val Fast = Color(0xFFF97316)    // 橙：快
    val Fastest = Color(0xFFEF4444) // 红：极速

    fun forSpeed(speedKmh: Float, maxSpeed: Float = 400f): Color {
        val ratio = (speedKmh / maxSpeed).coerceIn(0f, 1f)
        return when {
            ratio < 0.4f -> Slow
            ratio < 0.65f -> Medium
            ratio < 0.85f -> Fast
            else -> Fastest
        }
    }
}
