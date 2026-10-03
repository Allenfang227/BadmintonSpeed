package com.badmintonspeed.app.analysis

import android.graphics.PointF
import kotlin.math.abs

/**
 * 透视变换工具：根据像素角点与标准场地角点计算单应矩阵（3x3），
 * 实现 像素坐标 -> 场地实际坐标（米） 的映射，用于球速计算。
 *
 * 实现方式：DLT（直接线性变换）+ 最小二乘（正规方程 + 高斯消元）。
 * 4 组对应点即可求解 8 个未知数（固定 h33=1）。
 */
object Homography {

    /**
     * @param src 图像中的场地角点（像素坐标），顺序与 dst 一致
     * @param dst 标准场地角点（米）：左上(0,0)、右上(6.10,0)、右下(6.10,13.40)、左下(0,13.40)
     * @return 9 元素行主序 3x3 矩阵
     */
    fun compute(src: List<PointF>, dst: List<PointF>): FloatArray {
        require(src.size >= 4 && dst.size >= 4) { "至少需要 4 组对应点" }
        val n = src.size

        // 构建 2n x 8 的系数矩阵 A 与 2n 维向量 b（h33 固定为 1）
        // 对每对点: [x, y, 1, 0,0,0, -X*x, -X*y]·h = X ; [0,0,0, x, y, 1, -Y*x, -Y*y]·h = Y
        // 最小二乘：A^T A h = A^T b （8x8 线性方程组）
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)

        for (i in 0 until n) {
            val x = src[i].x.toDouble()
            val y = src[i].y.toDouble()
            val X = dst[i].x.toDouble()
            val Y = dst[i].y.toDouble()

            val row1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -X * x, -X * y)
            val row2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -Y * x, -Y * y)

            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    ata[r][c] += row1[r] * row1[c] + row2[r] * row2[c]
                }
                atb[r] += row1[r] * X + row2[r] * Y
            }
        }

        val h8 = solveLinear(ata, atb) ?: return identity()

        return floatArrayOf(
            h8[0].toFloat(), h8[1].toFloat(), h8[2].toFloat(),
            h8[3].toFloat(), h8[4].toFloat(), h8[5].toFloat(),
            h8[6].toFloat(), h8[7].toFloat(), 1.0f
        )
    }

    /** 高斯消元（部分主元法）求解 8x8 线性方程组 */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 8
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            var pivot = col
            var maxVal = abs(m[col][col])
            for (r in col + 1 until n) {
                val av = abs(m[r][col])
                if (av > maxVal) { maxVal = av; pivot = r }
            }
            if (maxVal < 1e-12) return null
            if (pivot != col) {
                val tmp = m[pivot]; m[pivot] = m[col]; m[col] = tmp
                val tv = v[pivot]; v[pivot] = v[col]; v[col] = tv
            }
            val div = m[col][col]
            for (c in col until n) m[col][c] /= div
            v[col] /= div
            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col]
                if (abs(factor) < 1e-15) continue
                for (c in col until n) m[r][c] -= factor * m[col][c]
                v[r] -= factor * v[col]
            }
        }
        return v
    }

    /** 将像素坐标转换为场地坐标（米） */
    fun pixelToCourt(h: FloatArray, x: Float, y: Float): PointF {
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-9f) return PointF(0f, 0f)
        val cx = (h[0] * x + h[1] * y + h[2]) / w
        val cy = (h[3] * x + h[4] * y + h[5]) / w
        return PointF(cx, cy)
    }

    /** 将场地坐标（米）转换为像素坐标（单应性逆变换，供模板反投影） */
    fun courtToImage(h: FloatArray, x: Float, y: Float): PointF {
        // 手动求 3x3 逆矩阵
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val i = h[7]; val j = h[8]
        val det = a * (e * j - f * i) - b * (d * j - f * g) + c * (d * i - e * g)
        if (abs(det) < 1e-9f) return PointF(x, y)
        val invDet = 1.0f / det
        val ia = (e * j - f * i) * invDet
        val ib = (c * i - b * j) * invDet
        val ic = (b * f - c * e) * invDet
        val id = (f * g - d * j) * invDet
        val ie = (a * j - c * g) * invDet
        val iff = (c * d - a * f) * invDet
        val ig = (d * i - e * g) * invDet
        val ih = (b * g - a * i) * invDet
        val ij = (a * e - b * d) * invDet
        val w = ig * x + ih * y + ij
        if (abs(w) < 1e-9f) return PointF(x, y)
        return PointF((ia * x + ib * y + ic) / w, (id * x + ie * y + iff) / w)
    }

    private fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
}
