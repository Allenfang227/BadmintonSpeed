package com.badmintonspeed.app.ui

import com.badmintonspeed.app.domain.SpeedUnit
import com.badmintonspeed.app.domain.SpeedUnitConverter
import java.util.Locale

fun formatSpeed(kmh: Float, unit: SpeedUnit): String {
    val v = SpeedUnitConverter.fromKmh(kmh, unit)
    return String.format(Locale.US, "%.1f", v)
}
