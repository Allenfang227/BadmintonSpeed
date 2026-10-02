package com.badmintonspeed.app.ui.theme

import androidx.compose.ui.graphics.Color

// 文档 8.7.1 颜色系统：深色主题为主（运动科技感）
val Primary = Color(0xFF3B82F6)
val OnPrimary = Color.White
val PrimaryContainer = Color(0xFF1E40AF)
val OnPrimaryContainer = Color(0xFFBFDBFE)
val Secondary = Color(0xFF8B5CF6)
val OnSecondary = Color.White
val SecondaryContainer = Color(0xFF5B21B6)
val OnSecondaryContainer = Color(0xFFDDD6FE)
val Background = Color(0xFF0F172A)
val OnBackground = Color(0xFFE2E8F0)
val Surface = Color(0xFF1E293B)
val OnSurface = Color(0xFFF1F5F9)
val SurfaceVariant = Color(0xFF334155)
val OnSurfaceVariant = Color(0xFFCBD5E1)
val Error = Color(0xFFEF4444)
val OnError = Color.White
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFFACC15)

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
