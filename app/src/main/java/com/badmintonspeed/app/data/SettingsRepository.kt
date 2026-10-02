package com.badmintonspeed.app.data

import android.content.Context
import com.badmintonspeed.app.domain.PerformanceMode
import com.badmintonspeed.app.domain.SpeedUnit

/** 应用设置仓库：SharedPreferences 持久化 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("badminton_speed_settings", Context.MODE_PRIVATE)

    var speedUnit: SpeedUnit
        get() = SpeedUnit.valueOf(prefs.getString(KEY_UNIT, SpeedUnit.KMH.name) ?: SpeedUnit.KMH.name)
        set(value) { prefs.edit().putString(KEY_UNIT, value.name).apply() }

    var performanceMode: PerformanceMode
        get() = PerformanceMode.valueOf(
            prefs.getString(KEY_MODE, PerformanceMode.BALANCED.name) ?: PerformanceMode.BALANCED.name
        )
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    var brightThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 195)
        set(value) { prefs.edit().putInt(KEY_THRESHOLD, value).apply() }

    companion object {
        private const val KEY_UNIT = "speed_unit"
        private const val KEY_MODE = "performance_mode"
        private const val KEY_THRESHOLD = "bright_threshold"
    }
}
