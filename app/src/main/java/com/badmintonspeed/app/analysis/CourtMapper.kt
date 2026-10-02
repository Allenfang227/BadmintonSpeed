package com.badmintonspeed.app.analysis

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin

/**
 * 场地透视映射器（融合自 lzylovec/AI-YuJian-AI 的 CourtMapper）：
 * 输入图像中场地4个角点，建立图像↔标准球场(6.10m×13.40m)的透视变换，
 * 然后反算出所有标准场地线在图像中的位置，画出完整精确的场地线覆盖
 * （不只是4条外边，还包括中线、发球线、短发球线等）。
 *
 * 标准羽毛球场线（米）：
 *   外边界：x=0, x=6.10, y=0, y=13.40
 *   双打后发球线：y=0.76, y=12.64
 *   单打边线：x=0.46, x=5.64
 *   前发球线：y=4.72, y=8.68
 *   中线：x=3.05（y=4.72..8.68 之间）
 *   球网：y=6.70
 */
class CourtMapper(cornersPx: List<PointF>) {

    // 标准球场4角（米）：左上、右上、右下、左下
    private val courtPoints = floatArrayOf(
        0f, 0f,
        6.10f, 0f,
        6.10f, 13.40f,
        0f, 13.40f
    )

    // 图像4角（像素）
    private val imagePoints = floatArrayOf(
        cornersPx[0].x, cornersPx[0].y,
        cornersPx[1].x, cornersPx[1].y,
        cornersPx[2].x, cornersPx[2].y,
        cornersPx[3].x, cornersPx[3].y
    )

    // 透视矩阵：球场(米) -> 图像(像素)
    private val matrix = computePerspective(courtPoints, imagePoints)

    /** 球场坐标(米) -> 图像坐标(像素) */
    fun courtToImage(x: Float, y: Float): PointF {
        val m = matrix
        val w = m[6] * x + m[7] * y + m[8]
        return PointF(
            (m[0] * x + m[1] * y + m[2]) / w,
            (m[3] * x + m[4] * y + m[5]) / w
        )
    }

    /** 在 canvas 上画出完整标准场地线（所有线），paint 已设置颜色和宽度 */
    fun drawFullCourt(canvas: Canvas, paint: Paint) {
        // 外边界（双打场地）
        drawLine(canvas, paint, 0f, 0f, 6.10f, 0f)       // 上底线
        drawLine(canvas, paint, 0f, 13.40f, 6.10f, 13.40f) // 下底线
        drawLine(canvas, paint, 0f, 0f, 0f, 13.40f)       // 左边线
        drawLine(canvas, paint, 6.10f, 0f, 6.10f, 13.40f) // 右边线

        // 单打边线
        drawLine(canvas, paint, 0.46f, 0f, 0.46f, 13.40f)
        drawLine(canvas, paint, 5.64f, 0f, 5.64f, 13.40f)

        // 前发球线
        drawLine(canvas, paint, 0f, 4.72f, 6.10f, 4.72f)
        drawLine(canvas, paint, 0f, 8.68f, 6.10f, 8.68f)

        // 双打后发球线
        drawLine(canvas, paint, 0f, 0.76f, 6.10f, 0.76f)
        drawLine(canvas, paint, 0f, 12.64f, 6.10f, 12.64f)

        // 中线（发球区之间）
        drawLine(canvas, paint, 3.05f, 4.72f, 3.05f, 8.68f)

        // 球网（虚线感，用较浅的线）
        val netPaint = Paint(paint)
        netPaint.alpha = 120
        drawLine(canvas, netPaint, 0f, 6.70f, 6.10f, 6.70f)

        // 4个角点标记
        for (p in listOf(
            courtToImage(0f, 0f),
            courtToImage(6.10f, 0f),
            courtToImage(6.10f, 13.40f),
            courtToImage(0f, 13.40f)
        )) {
            canvas.drawCircle(p.x, p.y, 6f, paint)
        }
    }

    private fun drawLine(canvas: Canvas, paint: Paint, x1: Float, y1: Float, x2: Float, y2: Float) {
        val p1 = courtToImage(x1, y1)
        val p2 = courtToImage(x2, y2)
        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
    }

    /** 计算 3x3 透视变换矩阵（行主序），从 src 4点映射到 dst 4点 */
    private fun computePerspective(src: FloatArray, dst: FloatArray): FloatArray {
        // 解 8x8 线性方程组求透视矩阵的8个自由度（m[8]=1）
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val sx = src[2 * i].toDouble()
            val sy = src[2 * i + 1].toDouble()
            val dx = dst[2 * i].toDouble()
            val dy = dst[2 * i + 1].toDouble()
            a[2 * i] = doubleArrayOf(sx, sy, 1.0, 0.0, 0.0, 0.0, -dx * sx, -dx * sy, dx)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, sx, sy, 1.0, -dy * sx, -dy * sy, dy)
        }
        // 高斯消元
        for (col in 0 until 8) {
            var pivot = col
            for (row in col + 1 until 8) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row
            }
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (row in 0 until 8) {
                if (row != col && Math.abs(a[row][col]) > 1e-12) {
                    val factor = a[row][col] / a[col][col]
                    for (k in col until 9) a[row][k] -= factor * a[col][k]
                }
            }
        }
        val m = FloatArray(9)
        for (i in 0 until 8) m[i] = (a[i][8] / a[i][i]).toFloat()
        m[8] = 1f
        return m
    }

    companion object {
        const val COURT_WIDTH_M = 6.10f
        const val COURT_LENGTH_M = 13.40f
        const val NET_Y_M = 6.70f
    }
}
