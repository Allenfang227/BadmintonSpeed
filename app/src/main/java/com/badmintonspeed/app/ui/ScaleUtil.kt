package com.badmintonspeed.app.ui

import kotlin.math.min

/** 图片等比缩放显示信息（px 坐标） */
data class ScaleInfo(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val width: Int,
    val height: Int
)

fun computeScale(srcW: Int, srcH: Int, dstW: Int, dstH: Int): ScaleInfo {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
        return ScaleInfo(1f, 0f, 0f, srcW, srcH)
    }
    val scale = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
    val w = (srcW * scale).toInt().coerceAtLeast(1)
    val h = (srcH * scale).toInt().coerceAtLeast(1)
    val ox = (dstW - w) / 2f
    val oy = (dstH - h) / 2f
    return ScaleInfo(scale, ox, oy, w, h)
}
