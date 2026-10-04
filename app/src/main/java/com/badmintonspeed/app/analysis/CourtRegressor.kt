package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 场地关键点配准 v3（宿主 8/8 样本验证版）。
 *
 * 核心（用户要求的"真AI识别交点 → 套 BWF 模板"落地）：
 *  1. 白线掩码：仅在"绿色塑胶地板"区域（HSV hue 55~130 且 sat≥60）内提取白线，
 *     人物白衣/广告白底/黄色护墙/显示屏全部排除 —— 根治 B 误检（邻场线/广告/地板缝）
 *  2. 任意方向 Hough 线段（不限制横竖，斜向场地线不再丢）
 *  3. 主方向按"平行线族数量"选（场地横线族 4~7 条等距平行线 vs 干扰 1~2 条）
 *  4. 两方向直线族求交 → 十字校验（两方向都须有白像素延伸）过滤伪交点
 *  5. BWF 模板（13.40×6.10m）RANSAC：随机 4 交点解 H → 全模板线（6横5竖）
 *     白线掩码支撑评分 + 面积/长宽比几何过滤
 *  6. 选场：多个候选场地中，模板网线（y=6.70m）白线支撑最强者为对局场地
 *  7. 迭代精配准：交点反投影到模板坐标 → 落在模板线<0.45m 的点最小二乘重拟合 H
 *  8. 输出四角（左上/右上/右下/左下，原图像素）+ 置信度（支撑线/11 + 角点/4）
 */
object CourtRegressor {

    private const val WORK_MAX_SIDE = 720
    private const val W_M = 6.10f
    private const val H_M = 13.40f
    private val TPL_X = floatArrayOf(0f, W_M, W_M, 0f)
    private val TPL_Y = floatArrayOf(0f, 0f, H_M, H_M)

    /** 最近一次配准的置信度 0~1（失败为 0） */
    @Volatile
    var lastConfidence: Float = 0f
        private set

    /** 依次返回 左上、右上、右下、左下 四个角点（原图像素坐标），失败返回 null。
     *  @param aiMask v2.28 开源分割模型的场地掩码（原图尺寸）：非空且有效时，
     *   白线提取直接用 AI 掩码作为空间约束（不依赖颜色），失败回退颜色掩码逻辑 */
    fun regress(frame: Bitmap, roi: List<PointF>? = null, aiMask: BooleanArray? = null): List<PointF>? {
        lastConfidence = 0f
        val srcW = frame.width
        val srcH = frame.height
        val scale = min(1f, WORK_MAX_SIDE.toFloat() / max(srcW, srcH))
        val W = (srcW * scale).roundToInt().coerceAtLeast(1)
        val H = (srcH * scale).roundToInt().coerceAtLeast(1)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(frame, W, H, true) else frame

        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        if (bmp !== frame) bmp.recycle()

        val gray = IntArray(W * H)
        val sat = IntArray(W * H)
        val hue = IntArray(W * H)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            sat[i] = mx - mn
            val diff = (mx - mn).toFloat()
            hue[i] = when {
                diff < 1e-3f -> 0
                mx == r -> ((60f * (((g - b) / diff) % 6f)) % 360f).roundToInt()
                mx == g -> ((60f * ((b - r) / diff + 2f)) % 360f).roundToInt()
                else -> ((60f * ((r - g) / diff + 4f)) % 360f).roundToInt()
            }
            if (hue[i] < 0) hue[i] += 360
        }

        // ---- 1. 白线掩码（绿色地板空间约束 + 亮度/饱和度/局部对比） ----
        // v2.28：开源 AI 分割掩码优先作为空间约束（原图尺寸，需按 scale 缩放裁剪）
        var aiScaled: BooleanArray? = null
        if (aiMask != null) {
            val mW = frame.width; val mH = frame.height
            val xs = (W.toFloat() / mW); val ys = (H.toFloat() / mH)
            aiScaled = BooleanArray(W * H)
            for (yy in 0 until H) {
                val sy = (yy / ys).toInt().coerceIn(0, mH - 1)
                var base = yy * W
                var srcBase = sy * mW
                for (xx in 0 until W) {
                    val sx = (xx / xs).toInt().coerceIn(0, mW - 1)
                    aiScaled[base + xx] = aiMask[srcBase + sx]
                }
            }
        }
        val white = enhanceWhite(gray, sat, hue, W, H, roi, scale, aiScaled)
        val whiteCount = countTrue(white)
        if (whiteCount < 400) return null

        // 白像素数组（降采样到 ≤7000 控制 RANSAC 支撑评分开销）
        val step = max(1, (whiteCount / 7000).coerceAtLeast(1))
        val wx = IntArray(7000); val wy = IntArray(7000)
        var nw = 0
        var cnt = 0
        for (i in white.indices) {
            if (white[i]) {
                cnt++
                if (cnt % step == 0) {
                    if (nw < 7000) { wx[nw] = i % W; wy[nw] = i / W; nw++ }
                }
            }
        }
        if (nw < 400) return null

        // ---- 2. 多方向游程线段 + 主方向（平行线族数量优先） ----
        val segs = runLengthSegments(wx, wy, nw, W, H)
        if (segs.size < 20) return null
        val axes = dominantAxes(segs) ?: return null
        val a0 = axes[0]; val a1 = axes[1]
        val L1 = fitLines(segs, a0).first
        val L2 = fitLines(segs, a1).first
        val n0 = -sin(Math.toRadians(a0.toDouble())).toFloat()
        val n1 = cos(Math.toRadians(a0.toDouble())).toFloat()
        val m0 = -sin(Math.toRadians(a1.toDouble())).toFloat()
        val m1 = cos(Math.toRadians(a1.toDouble())).toFloat()
        if (L1.size < 2 || L2.size < 2) return null

        // ---- 4. 两族求交 + 十字校验 ----
        val kps = ArrayList<FloatArray>() // (x, y)
        for (c1 in L1) {
            for (c2 in L2) {
                val det = n0 * m1 - n1 * m0
                if (abs(det) < 1e-6f) continue
                val x = (c1 * m1 - n1 * c2) / det
                val y = (n0 * c2 - c1 * m0) / det
                if (x in 0f..W.toFloat() && y in 0f..H.toFloat()) kps.add(floatArrayOf(x, y))
            }
        }
        if (kps.size < 5) return null
        // 十字校验：交点沿 L1 方向与沿 L2 方向都须有白像素延伸（两条真实线的交叉）
        val kpsFiltered = ArrayList<FloatArray>()
        for ((x, y) in kps) {
            val i1 = bestLineIdx(L1, n0, n1, x, y)
            val i2 = bestLineIdx(L2, m0, m1, x, y)
            var a = 0; var b = 0
            for (j in 0 until nw) {
                if (abs(n0 * wx[j] + n1 * wy[j] - L1[i1]) < 8f) a++
                if (abs(m0 * wx[j] + m1 * wy[j] - L2[i2]) < 8f) b++
            }
            if (a > 40 && b > 40) kpsFiltered.add(floatArrayOf(x, y))
        }
        if (kpsFiltered.size < 5) return null
        val n = kpsFiltered.size

        // ---- 5. BWF 模板 RANSAC + 支撑评分 + 候选收集 ----
        val rng = java.util.Random(42)
        var bestH: FloatArray? = null
        var bestScore = -1f
        var bestSup = 0
        var bestCorners: ArrayList<PointF>? = null
        val candidates = ArrayList<Pair<IntArray, FloatArray>>() // (corner0 rounded, H)
        val iterations = min(400, max(120, n * n))
        val tol = W * 0.09f
        for (iter in 0 until iterations) {
            val idx = IntArray(4)
            idx[0] = rng.nextInt(n); idx[1] = rng.nextInt(n)
            idx[2] = rng.nextInt(n); idx[3] = rng.nextInt(n)
            val p0 = kpsFiltered[idx[0]]; val p1 = kpsFiltered[idx[1]]
            val p2 = kpsFiltered[idx[2]]; val p3 = kpsFiltered[idx[3]]
            val quadArea = abs(
                (p1[0] - p0[0]) * (p3[1] - p0[1]) - (p1[1] - p0[1]) * (p3[0] - p0[0]) +
                (p3[0] - p2[0]) * (p0[1] - p2[1]) - (p3[1] - p2[1]) * (p0[0] - p2[0])
            ) / 2f
            if (quadArea < W * H * 0.02f) continue
            val Hm = try {
                Homography.compute(
                    listOf(PointF(p0[0], p0[1]), PointF(p1[0], p1[1]), PointF(p2[0], p2[1]), PointF(p3[0], p3[1])),
                    listOf(PointF(0f, 0f), PointF(W_M, 0f), PointF(W_M, H_M), PointF(0f, H_M))
                )
            } catch (e: Exception) { null } ?: continue
            val Hinv = invH(Hm) ?: continue
            val corners = projectTemplate(Hinv)
            // 面积 ≥10% 且长宽比 0.25~4
            val area = abs(quadAreaOf(corners))
            if (area < W * H * 0.10f) continue
            val ew = max(hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y),
                hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y))
            val eh = max(hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y),
                hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y))
            if (ew < 1f || eh / ew > 4f || eh / ew < 0.25f) continue
            val sup = lineSupportMask(Hinv, wx, wy, nw, W, H)
            var score = 0f
            for (c in 0 until 4) {
                var minD = Double.MAX_VALUE
                for (kp in kpsFiltered) {
                    val d = hypot((kp[0] - corners[c].x).toDouble(), (kp[1] - corners[c].y).toDouble())
                    if (d < minD) minD = d
                }
                if (minD < tol.toDouble()) score += 1f
            }
            val total = sup * 3f + score
            if (total > bestScore) {
                bestScore = total; bestH = Hm; bestSup = sup; bestCorners = corners
            }
            if (sup >= 5) {
                val c0 = intArrayOf(corners[0].x.roundToInt(), corners[0].y.roundToInt())
                var dup = false
                for ((ch, _) in candidates) {
                    if (hypot((ch[0] - c0[0]).toFloat(), (ch[1] - c0[1]).toFloat()) < 40f) { dup = true; break }
                }
                if (!dup) candidates.add(c0 to Hm)
            }
        }
        val bestHInit = bestH ?: return null
        if (bestSup < 1) return null

        // ---- 6. 选场：模板网线 y=6.70m 白线支撑最强 → 对局场地 ----
        if (candidates.isNotEmpty()) {
            var bestNet = -1f
            var bestNetH = bestHInit
            for ((_, Hm) in candidates) {
                val Hinv = invH(Hm) ?: continue
                val ns = netSupport(Hinv, wx, wy, nw)
                if (ns > bestNet) { bestNet = ns; bestNetH = Hm }
            }
            if (bestNet > 0.35f) {
                bestH = bestNetH
                bestSup = lineSupportMask(invH(bestNetH)!!, wx, wy, nw, W, H)
            }
        }

        // ---- 7. 迭代精配准：交点 → 模板线上点 → 最小二乘重拟合 ----
        var refineH = bestH!!
        var refineSup = bestSup
        for (r in 0 until 4) {
            val Hinv = invH(refineH) ?: break
            val goodSrc = ArrayList<PointF>()
            val goodDst = ArrayList<PointF>()
            for (kp in kpsFiltered) {
                val t = applyH(refineH, kp[0], kp[1])
                val dh = minOf(
                    abs(t.y - 0f), abs(t.y - 0.76f), abs(t.y - 4.72f), abs(t.y - 8.68f),
                    abs(t.y - 12.64f), abs(t.y - 13.40f)
                )
                val dv = minOf(
                    abs(t.x - 0f), abs(t.x - 0.46f), abs(t.x - 3.05f), abs(t.x - 5.64f),
                    abs(t.x - 6.10f)
                )
                if (dh < 0.45f || dv < 0.45f) {
                    goodSrc.add(PointF(kp[0], kp[1])); goodDst.add(t)
                }
            }
            if (goodSrc.size < 5) break
            val H2 = try {
                Homography.compute(goodSrc, goodDst)
            } catch (e: Exception) { null } ?: break
            val H2inv = invH(H2) ?: break
            val sup2 = lineSupportMask(H2inv, wx, wy, nw, W, H)
            if (sup2 > refineSup) { refineH = H2; refineSup = sup2 }
        }

        // ---- 8. 最终判定 + 输出 ----
        val finalInv = invH(refineH) ?: return null
        val finalCorners = projectTemplate(finalInv)
        var nCorner = 0
        for (c in finalCorners) {
            var minD = Double.MAX_VALUE
            for (kp in kpsFiltered) {
                val d = hypot((kp[0] - c.x).toDouble(), (kp[1] - c.y).toDouble())
                if (d < minD) minD = d
            }
            if (minD < tol.toDouble()) nCorner++
        }
        if (refineSup < 5 || nCorner < 2) return null
        lastConfidence = 0.6f * (refineSup / 11f) + 0.4f * (nCorner / 4f)
        return finalCorners.map { PointF(it.x / scale, it.y / scale) }
    }

    // ===================== 白线掩码 =====================

    private fun enhanceWhite(
        gray: IntArray, sat: IntArray, hue: IntArray, W: Int, H: Int,
        roi: List<PointF>?, scale: Float, aiScaled: BooleanArray? = null
    ): BooleanArray {
        // 地板灰度基准：中部区域直方图峰（占比 ≥1% 的最大峰）
        val cy0 = (H * 0.25).toInt(); val cy1 = (H * 0.75).toInt()
        val cx0 = (W * 0.15).toInt(); val cx1 = (W * 0.85).toInt()
        val hist = IntArray(256)
        for (y in cy0 until cy1) {
            var x = cx0
            while (x < cx1) { hist[gray[y * W + x]]++; x++ }
        }
        val cropSize = (cy1 - cy0) * (cx1 - cx0)
        var floorVal = 128
        var bestCnt = -1
        for (i in 20 until 240) {
            if (hist[i] > cropSize / 100 && hist[i] > bestCnt) { bestCnt = hist[i]; floorVal = i }
        }

        // v2.28：开源 AI 分割掩码优先——掩码有效（覆盖≥15% 且含足够区域）时
        // 直接作为空间约束（不依赖颜色，蓝色/木地板/深色场地都能罩住），跳过颜色定位逻辑
        if (aiScaled != null) {
            val hits = countTrue(aiScaled)
            if (hits.toFloat() / (W * H) >= CourtSegDetector.MIN_COVERAGE) {
                val mFloor = BooleanArray(W * H)
                val mLoc = BooleanArray(W * H)
                for (i in gray.indices) {
                    mFloor[i] = gray[i] - floorVal > 40 && sat[i] < 70
                }
                val mean = boxMean(gray, W, H, 31)
                for (i in gray.indices) {
                    mLoc[i] = gray[i] - mean[i] > 25 && sat[i] < 80
                }
                // 形态学去孤点（白线是连续亮条）：OPEN 1px = erode→dilate
                var white = BooleanArray(W * H)
                for (i in white.indices) {
                    white[i] = (mFloor[i] || mLoc[i]) && aiScaled[i]
                }
                white = dilate(erode(white, W, H, 1), W, H, 1)
                return white
            }
        }

        val mFloor = BooleanArray(W * H)
        val mLoc = BooleanArray(W * H)
        val greenMask = BooleanArray(W * H)
        val blueMask = BooleanArray(W * H)
        for (i in gray.indices) {
            val brighter = gray[i] - floorVal > 40
            mFloor[i] = brighter && sat[i] < 70
            val isGreen = hue[i] in 55..130 && sat[i] >= 60
            greenMask[i] = isGreen
            // v2.27.2 颜色域扩展：蓝色场地（hue 165~260 & sat≥50）此前不在此区间 → 蓝色场地必失败
            blueMask[i] = hue[i] in 165..260 && sat[i] >= 50
        }
        // 局部对比：gray - 31×31 均值 > 25（积分图加速）
        val mean = boxMean(gray, W, H, 31)
        for (i in gray.indices) {
            mLoc[i] = gray[i] - mean[i] > 25 && sat[i] < 80
        }
        // ---- v2.27.2 颜色区域上下定位 + 白线双重检测（用户方案）----
        // ① 上半部同色干扰抑制：y < 0.35H 的颜色区域内，白线样像素占比 < 0.5% → 该色块是布/背景，剔除
        val upY = (H * 0.35f).toInt()
        var upColor = 0; var upWhite = 0
        for (y in 0 until upY) {
            var base = y * W
            for (x in 0 until W) {
                val i = base + x
                if (greenMask[i] || blueMask[i]) {
                    upColor++
                    if (mFloor[i] || mLoc[i]) upWhite++
                }
            }
        }
        if (upColor > 0 && upWhite.toFloat() / upColor < 0.005f) {
            for (y in 0 until upY) {
                var base = y * W
                for (x in 0 until W) { val i = base + x; greenMask[i] = false; blueMask[i] = false }
            }
        }
        // ② 下半部主色验证：y ≥ 0.5H 颜色占比 < 5% → 该颜色不是场地（整面蓝墙/绿墙）→ 颜色约束降级
        val lowY = (H * 0.5f).toInt()
        var lowerColor = 0; var lowerTotal = 0
        for (y in lowY until H) {
            var base = y * W
            for (x in 0 until W) {
                val i = base + x
                if (greenMask[i] || blueMask[i]) lowerColor++
                lowerTotal++
            }
        }
        val colorMask = if (lowerTotal > 0 && lowerColor.toFloat() / lowerTotal < 0.05f) {
            // 木地板/深色场地兜底：白线不受颜色约束（旧版全图行为）
            null
        } else {
            BooleanArray(W * H) { greenMask[it] || blueMask[it] }
        }
        // ③ 颜色掩码膨胀 7px（白线本身非场地色，其两侧是）；无颜色域时用全真掩码
        val floorMask = if (colorMask == null) {
            BooleanArray(W * H) { true }
        } else {
            dilate(colorMask, W, H, 7)
        }
        var white = BooleanArray(W * H)
        for (i in white.indices) {
            white[i] = (mFloor[i] || mLoc[i]) && floorMask[i]
        }
        // ROI 拦截：多边形外的白点全部剔除
        if (roi != null && roi.size >= 3) {
            val roiMask = polygonMask(W, H, roi, scale)
            for (i in white.indices) if (!roiMask[i]) white[i] = false
        }
        // 形态学：CLOSE 5×5 + OPEN 3×3
        white = morphClose(white, W, H, 5)
        white = morphOpen(white, W, H, 3)
        return white
    }

    private fun boxMean(gray: IntArray, W: Int, H: Int, k: Int): IntArray {
        // 水平滑窗 → 垂直滑窗（O(N)）
        val hAvg = IntArray(W * H)
        val half = k / 2
        for (y in 0 until H) {
            var sum = 0
            for (x in -half..half) sum += gray[y * W + x.coerceIn(0, W - 1)]
            for (x in 0 until W) {
                hAvg[y * W + x] = sum
                val xOut = x - half
                val xIn = x + half + 1
                if (xOut >= 0) sum -= gray[y * W + xOut]
                if (xIn < W) sum += gray[y * W + xIn]
            }
        }
        val res = IntArray(W * H)
        for (x in 0 until W) {
            var sum = 0
            for (y in -half..half) sum += hAvg[y.coerceIn(0, H - 1) * W + x]
            for (y in 0 until H) {
                res[y * W + x] = sum / (k * k)
                val yOut = y - half
                val yIn = y + half + 1
                if (yOut >= 0) sum -= hAvg[yOut * W + x]
                if (yIn < H) sum += hAvg[yIn * W + x]
            }
        }
        return res
    }

    private fun dilate(src: BooleanArray, W: Int, H: Int, r: Int): BooleanArray {
        val out = BooleanArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                if (!src[y * W + x]) continue
                val y0 = (y - r).coerceAtLeast(0); val y1 = (y + r).coerceAtMost(H - 1)
                val x0 = (x - r).coerceAtLeast(0); val x1 = (x + r).coerceAtMost(W - 1)
                for (yy in y0..y1) for (xx in x0..x1) out[yy * W + xx] = true
            }
        }
        return out
    }

    private fun morphClose(src: BooleanArray, W: Int, H: Int, k: Int): BooleanArray =
        dilate(erode(src, W, H, k), W, H, k)

    private fun morphOpen(src: BooleanArray, W: Int, H: Int, k: Int): BooleanArray =
        erode(dilate(src, W, H, k), W, H, k)

    private fun erode(src: BooleanArray, W: Int, H: Int, k: Int): BooleanArray {
        val r = k / 2
        val out = BooleanArray(W * H)
        for (y in r until H - r) {
            for (x in r until W - r) {
                var ok = true
                loop@ for (yy in y - r..y + r) {
                    for (xx in x - r..x + r) {
                        if (!src[yy * W + xx]) { ok = false; break@loop }
                    }
                }
                out[y * W + x] = ok
            }
        }
        return out
    }

    private fun polygonMask(W: Int, H: Int, roiPx: List<PointF>, scale: Float): BooleanArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val paint = Paint().apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val path = Path()
            path.moveTo(roiPx[0].x * scale, roiPx[0].y * scale)
            for (i in 1 until roiPx.size) path.lineTo(roiPx[i].x * scale, roiPx[i].y * scale)
            path.close()
            canvas.drawPath(path, paint)
            val px = IntArray(W * H)
            bmp.getPixels(px, 0, W, 0, 0, W, H)
            val mask = BooleanArray(W * H)
            for (i in px.indices) mask[i] = (px[i] ushr 24) > 0x7F
            return mask
        } finally {
            bmp.recycle()
        }
    }

    // ===================== 线段检测 / 主方向 / 直线族（纯手写，无 OpenCV） =====================

    /**
     * 多方向游程线段：对 16 个方向把白像素投影到 (切向u, 法向v)，按 v 行对 u 游程取连续段。
     * 等价于任意方向 Hough（斜向场地线不再丢），但不依赖 OpenCV。
     * 返回 FloatArray 数组，每条 (x1, y1, x2, y2)。
     */
    private fun runLengthSegments(wx: IntArray, wy: IntArray, nw: Int, W: Int, H: Int): ArrayList<FloatArray> {
        val segs = ArrayList<FloatArray>()
        val nDir = 16
        for (di in 0 until nDir) {
            val a = Math.toRadians(di * 180.0 / nDir)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            // v 行分组
            val rows = HashMap<Int, ArrayList<Int>>()
            for (j in 0 until nw) {
                val v = (-sa * wx[j] + ca * wy[j]).roundToInt()
                val arr = rows[v] ?: ArrayList<Int>().also { rows[v] = it }
                arr.add(j)
            }
            for ((vi, idxs) in rows) {
                if (idxs.size < 3) continue
                // u 排序
                val uArr = FloatArray(idxs.size)
                for (k in idxs.indices) {
                    val j = idxs[k]
                    uArr[k] = ca * wx[j] + sa * wy[j]
                }
                val order = (0 until uArr.size).sortedBy { uArr[it] }
                var start = uArr[order[0]]; var prev = start; var run = 1
                for (k in 1 until order.size) {
                    val u = uArr[order[k]]
                    if (u - prev <= 2f) run++
                    else {
                        if (run >= 8) {
                            val mid = (start + prev) / 2f
                            val ln = prev - start
                            val midx = mid * ca - vi * sa
                            val midy = mid * sa + vi * ca
                            segs.add(floatArrayOf(midx - 0.5f * ln * ca, midy - 0.5f * ln * sa,
                                midx + 0.5f * ln * ca, midy + 0.5f * ln * sa))
                        }
                        start = u; run = 1
                    }
                    prev = u
                }
                if (run >= 8) {
                    val mid = (start + prev) / 2f
                    val ln = prev - start
                    val midx = mid * ca - vi * sa
                    val midy = mid * sa + vi * ca
                    segs.add(floatArrayOf(midx - 0.5f * ln * ca, midy - 0.5f * ln * sa,
                        midx + 0.5f * ln * ca, midy + 0.5f * ln * sa))
                }
            }
        }
        return segs
    }

    private fun angleOf(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        var d = Math.toDegrees(Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()))
        d = (d % 180.0 + 180.0) % 180.0
        return d.toFloat()
    }

    /** 按"平行线族数量"选主方向（diag2 同逻辑）：前 10 角度桶 → 每桶 fitLines 族数排序 */
    private fun dominantAxes(segs: ArrayList<FloatArray>): List<Float>? {
        val hist = HashMap<Int, Float>()
        for (s in segs) {
            val ln = hypot(s[2] - s[0], s[3] - s[1])
            val a = angleOf(s[0], s[1], s[2], s[3])
            val b = (a / 22.5f).toInt()
            hist[b] = (hist[b] ?: 0f) + ln
        }
        val peaks = hist.entries.sortedByDescending { it.value }.take(10)
        val cand = ArrayList<Pair<Float, List<Float>>>()
        for ((b, _) in peaks) {
            val a = b * 22.5f + 11.25f
            cand.add(a to fitLines(segs, a).first)
        }
        cand.sortByDescending { it.second.size }
        if (cand.isEmpty()) return null
        val a0 = cand[0].first
        if (cand[0].second.size < 2) return listOf(a0)
        var bestA = -1f; var bestD = 999f
        for (i in 1 until cand.size) {
            val (a, lines) = cand[i]
            if (lines.size < 2) continue
            var d = abs((a - a0) % 180f); d = min(d, 180f - d)
            val dd = abs(d - 90f)
            if (dd < bestD) { bestD = dd; bestA = a }
        }
        if (bestA < 0f) return listOf(a0)
        return listOf(min(a0, bestA), max(a0, bestA))
    }

    /** 该方向线段投影到法向量，1D 贪心聚类成直线族（gap=8px；簇须 ≥2 段或单段长 >50px） */
    private fun fitLines(segs: ArrayList<FloatArray>, axisDeg: Float): Triple<List<Float>, Float, Float> {
        val rad = Math.toRadians(axisDeg.toDouble())
        val n0 = (-sin(rad)).toFloat()
        val n1 = cos(rad).toFloat()
        val proj = ArrayList<Float>()
        val lens = ArrayList<Float>()
        for (s in segs) {
            var d = abs((angleOf(s[0], s[1], s[2], s[3]) - axisDeg) % 180f)
            d = min(d, 180f - d)
            if (d > 22f) continue
            val ln = hypot(s[2] - s[0], s[3] - s[1])
            if (ln < 15f) continue
            proj.add(((s[0] + s[2]) / 2f) * n0 + ((s[1] + s[3]) / 2f) * n1)
            lens.add(ln)
        }
        if (proj.size < 2) return Triple(emptyList(), n0, n1)
        val order = proj.indices.sortedBy { proj[it] }
        val clusters = ArrayList<ArrayList<Int>>()
        var cur = ArrayList<Int>()
        cur.add(order[0])
        for (oi in order.drop(1)) {
            if (proj[oi] - proj[cur.last()] <= 8f) cur.add(oi)
            else { clusters.add(cur); cur = ArrayList<Int>().apply { add(oi) } }
        }
        clusters.add(cur)
        val lines = ArrayList<Float>()
        for (c in clusters) {
            val avg = c.map { lens[it] }.average().toFloat()
            if (c.size >= 2 || avg > 50f) {
                val sortedProj = c.map { proj[it] }.sorted()
                lines.add(sortedProj[sortedProj.size / 2])
            }
        }
        lines.sort()
        return Triple(lines, n0, n1)
    }

    private fun bestLineIdx(lines: List<Float>, n0: Float, n1: Float, x: Float, y: Float): Int {
        var best = 0; var bestD = Float.MAX_VALUE
        for (i in lines.indices) {
            val d = abs(n0 * x + n1 * y - lines[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ===================== 单应性工具 =====================

    /** 3x3 行主序矩阵求逆（不存在返回 null） */
    private fun invH(h: FloatArray): FloatArray? {
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val i = h[7]; val j = h[8]
        val det = a * (e * j - f * i) - b * (d * j - f * g) + c * (d * i - e * g)
        if (abs(det) < 1e-9f) return null
        val inv = 1f / det
        return floatArrayOf(
            (e * j - f * i) * inv, (c * i - b * j) * inv, (b * f - c * e) * inv,
            (f * g - d * j) * inv, (a * j - c * g) * inv, (c * d - a * f) * inv,
            (d * i - e * g) * inv, (b * g - a * i) * inv, (a * e - b * d) * inv
        )
    }

    /** Hinv(模板→像素) 投影模板 4 角 */
    private fun projectTemplate(Hinv: FloatArray): ArrayList<PointF> {
        val out = ArrayList<PointF>(4)
        for (c in 0 until 4) {
            val x = TPL_X[c]; val y = TPL_Y[c]
            val w = Hinv[6] * x + Hinv[7] * y + Hinv[8]
            out.add(PointF((Hinv[0] * x + Hinv[1] * y + Hinv[2]) / w, (Hinv[3] * x + Hinv[4] * y + Hinv[5]) / w))
        }
        return out
    }

    /** H(像素→模板) 投影单个像素点 → 模板坐标 */
    private fun applyH(h: FloatArray, x: Float, y: Float): PointF {
        val w = h[6] * x + h[7] * y + h[8]
        return PointF((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    private fun quadAreaOf(corners: List<PointF>): Float =
        abs(
            (corners[1].x - corners[0].x) * (corners[3].y - corners[0].y) -
                (corners[1].y - corners[0].y) * (corners[3].x - corners[0].x) +
                (corners[3].x - corners[2].x) * (corners[0].y - corners[2].y) -
                (corners[3].y - corners[2].y) * (corners[0].x - corners[2].x)
        ) / 2f

    // ===================== 支撑评估 =====================

    /** 全模板线（6横5竖）白像素支撑数：沿线 6px 条带内白像素跨度 ≥ 35% 线长 */
    private fun lineSupportMask(Hinv: FloatArray, wx: IntArray, wy: IntArray, nw: Int, W: Int, H: Int): Int {
        val hLines = floatArrayOf(0f, 0.76f, 4.72f, 8.68f, 12.64f, 13.40f)
        val vLines = floatArrayOf(0f, 0.46f, 3.05f, 5.64f, 6.10f)
        var sup = 0
        val prevNx = ArrayList<Float>(); val prevNy = ArrayList<Float>(); val prevC = ArrayList<Float>()
        for (yT in hLines) {
            val p1 = projT(Hinv, 0f, yT); val p2 = projT(Hinv, W_M, yT)
            if (evalLine(p1, p2, wx, wy, nw, prevNx, prevNy, prevC)) sup++
        }
        for (xT in vLines) {
            val p1 = projT(Hinv, xT, 0f); val p2 = projT(Hinv, xT, H_M)
            if (evalLine(p1, p2, wx, wy, nw, prevNx, prevNy, prevC)) sup++
        }
        return sup
    }

    private fun projT(Hinv: FloatArray, x: Float, y: Float): FloatArray {
        val w = Hinv[6] * x + Hinv[7] * y + Hinv[8]
        return floatArrayOf((Hinv[0] * x + Hinv[1] * y + Hinv[2]) / w, (Hinv[3] * x + Hinv[4] * y + Hinv[5]) / w)
    }

    private fun evalLine(
        p1: FloatArray, p2: FloatArray, wx: IntArray, wy: IntArray, nw: Int,
        prevNx: ArrayList<Float>, prevNy: ArrayList<Float>, prevC: ArrayList<Float>
    ): Boolean {
        val dx = p2[0] - p1[0]; val dy = p2[1] - p1[1]
        val ln = hypot(dx, dy)
        if (ln < 20f) return false
        val nx = -dy / ln; val ny = dx / ln
        val c = -(nx * p1[0] + ny * p1[1])
        for (i in prevNx.indices) {
            if (abs(nx - prevNx[i]) + abs(ny - prevNy[i]) < 0.15f && abs(c - prevC[i]) < 15f) return false
        }
        var hits = 0
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        val tx = dx / ln; val ty = dy / ln
        for (j in 0 until nw) {
            if (abs(nx * wx[j] + ny * wy[j] + c) < 6f) {
                val a = tx * wx[j] + ty * wy[j]
                if (a < minA) minA = a
                if (a > maxA) maxA = a
                hits++
            }
        }
        if (hits >= 8 && maxA - minA > 0.35f * ln) {
            prevNx.add(nx); prevNy.add(ny); prevC.add(c)
            return true
        }
        return false
    }

    /** 模板网线 y=6.70m 白线支撑跨度占比（选对局场地判据） */
    private fun netSupport(Hinv: FloatArray, wx: IntArray, wy: IntArray, nw: Int): Float {
        val p1 = projT(Hinv, 0f, 6.70f); val p2 = projT(Hinv, W_M, 6.70f)
        val dx = p2[0] - p1[0]; val dy = p2[1] - p1[1]
        val ln = hypot(dx, dy)
        if (ln < 20f) return 0f
        val nx = -dy / ln; val ny = dx / ln
        val c = -(nx * p1[0] + ny * p1[1])
        val tx = dx / ln; val ty = dy / ln
        var hits = 0
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (j in 0 until nw) {
            if (abs(nx * wx[j] + ny * wy[j] + c) < 6f) {
                val a = tx * wx[j] + ty * wy[j]
                if (a < minA) minA = a
                if (a > maxA) maxA = a
                hits++
            }
        }
        if (hits < 8) return 0f
        return (maxA - minA) / ln
    }

    private fun countTrue(a: BooleanArray): Int {
        var n = 0
        for (v in a) if (v) n++
        return n
    }
}
