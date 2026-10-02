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

    // ---- 画面设置（图4）：显示虚拟场地 ----
    var showVirtualCourt: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COURT, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_COURT, value).apply() }

    // 数据1（左侧球员）：出拍速度 / 实时速度 / 击球类型 / 界内界外
    var data1ShotSpeed: Boolean
        get() = prefs.getBoolean(KEY_D1_SHOT, true)
        set(value) { prefs.edit().putBoolean(KEY_D1_SHOT, value).apply() }
    var data1LiveSpeed: Boolean
        get() = prefs.getBoolean(KEY_D1_LIVE, false)
        set(value) { prefs.edit().putBoolean(KEY_D1_LIVE, value).apply() }
    var data1HitType: Boolean
        get() = prefs.getBoolean(KEY_D1_HIT, false)
        set(value) { prefs.edit().putBoolean(KEY_D1_HIT, value).apply() }
    var data1InOut: Boolean
        get() = prefs.getBoolean(KEY_D1_INOUT, true)
        set(value) { prefs.edit().putBoolean(KEY_D1_INOUT, value).apply() }

    // 数据2（右侧球员）：出拍速度 / 实时速度 / 击球类型 / 界内界外
    var data2ShotSpeed: Boolean
        get() = prefs.getBoolean(KEY_D2_SHOT, false)
        set(value) { prefs.edit().putBoolean(KEY_D2_SHOT, value).apply() }
    var data2LiveSpeed: Boolean
        get() = prefs.getBoolean(KEY_D2_LIVE, true)
        set(value) { prefs.edit().putBoolean(KEY_D2_LIVE, value).apply() }
    var data2HitType: Boolean
        get() = prefs.getBoolean(KEY_D2_HIT, false)
        set(value) { prefs.edit().putBoolean(KEY_D2_HIT, value).apply() }
    var data2InOut: Boolean
        get() = prefs.getBoolean(KEY_D2_INOUT, false)
        set(value) { prefs.edit().putBoolean(KEY_D2_INOUT, value).apply() }

    companion object {
        private const val KEY_UNIT = "speed_unit"
        private const val KEY_MODE = "performance_mode"
        private const val KEY_THRESHOLD = "bright_threshold"
        private const val KEY_SHOW_COURT = "show_virtual_court"
        private const val KEY_D1_SHOT = "data1_shot_speed"
        private const val KEY_D1_LIVE = "data1_live_speed"
        private const val KEY_D1_HIT = "data1_hit_type"
        private const val KEY_D1_INOUT = "data1_in_out"
        private const val KEY_D2_SHOT = "data2_shot_speed"
        private const val KEY_D2_LIVE = "data2_live_speed"
        private const val KEY_D2_HIT = "data2_hit_type"
        private const val KEY_D2_INOUT = "data2_in_out"
    }
}
