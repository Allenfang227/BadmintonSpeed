package com.badmintonspeed.app.ui.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * v2.20 裸眼 3D：陀螺仪 / 重力传感器驱动
 *
 * 阳光固定在"6 点钟方向"（屏幕正下方）：
 *  - 手机竖直平放时，重力方向 ≈ (0, +1)（屏幕 y 向下），
 *    阴影方向 = 重力反方向 = (0, -1)（朝上，即光源在 6 点、影子朝 12 点）；
 *  - 手机倾斜时，重力分量随之变化，阴影方向实时流动 → 类裸眼 3D 视差。
 */

/** 归一化重力方向（屏幕坐标系，分量 ∈ [-1,1]，默认竖直持机 ≈ (0, 1)） */
val LocalGravity = staticCompositionLocalOf { Offset(0f, 1f) }

/**
 * 注册 TYPE_GRAVITY 传感器（低通滤波），并把实时重力方向注入 CompositionLocal。
 * 用法：在 AppRoot 最外层包一层即可，全树按钮/卡片通过 liveShadow 自动感知。
 */
@Composable
fun GravityShadowProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val gravity = remember { mutableStateOf(Offset(0f, 1f)) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        var fx = 0f
        var fy = 0f
        var fz = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // 低通滤波：平滑掉传感器抖动，避免阴影抖闪
                val alpha = 0.25f
                fx = fx * (1f - alpha) + event.values[0] * alpha
                fy = fy * (1f - alpha) + event.values[1] * alpha
                fz = fz * (1f - alpha) + event.values[2] * alpha
                val len = sqrt(fx * fx + fy * fy + fz * fz)
                if (len > 0.1f) {
                    gravity.value = Offset(fx / len, fy / len)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    CompositionLocalProvider(LocalGravity provides gravity.value) {
        content()
    }
}
