package com.badmintonspeed.app.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AI 自动场地标定 v2.7（对应参考图1 的"场地基准检测"模块）：
 *   Canny边缘检测 -> 霍夫直线变换 -> RANSAC迭代拟合 -> 单应性矩阵计算
 *
 * ABC 三套级联方案（任一成功即返回，防止 E101 反复报错）：
 *   方案A（标准白线）：Otsu 自适应白线 / 阈值130白线 / 阈值100白线 / Sobel边缘，正常参数
 *   方案B（宽松边缘）：低阈值 Sobel + 更宽松聚类（20°）+ 更低门槛
 *   方案C（最后防线）：超低阈值边缘 + 最宽松参数（面积≥2%、贴合度≥0.02）
 * 每个方案内部都含"部分线+羽毛球先验构造"：
 *   斜拍远景场地近端角在画面外时，检测到边线组(≥2条平行线)+横线组(≥1条)即可，
 *   按羽毛球场固定长宽比(13.40m/6.10m≈2.196)沿纵深方向延伸构造完整场地。
 *
 * 修改依据：外部专业 AI 建议 6 点全部采纳
 *   ① 白线阈值放宽：Otsu 100..230、130/100 双低阈值兜底、饱和度差 70→90
 *   ② 霍夫保留线下限：max(3, maxVotes*8%)，远景小场地不再丢线
 *   ③ 四边形贴合评分阈值 0.08→0.04
 *   ④ partialConstruct：副组多条线取离中心最近一条即可构造（不再 return null）
 *   ⑤ 面积下限 10%→4%（部分构造 6%→3%）
 *   ⑥ 边长比上限 6.0→10.0（斜拍透视压缩）
 *   + 多帧探测从 3 帧均匀扩到 8 帧（VideoAnalyzer.kt）
 */
object CourtAutoCalibrator {

    /** 单帧标定诊断（供 E101 失败分类 A漏检/B误检/C拓扑错/D几何歪 统计占比） */
    data class Diagnosis(
        val whiteRatio: Float = 0f,   // 白线像素占帧面积比（<0.03 判漏检）
        val foundQuad: Boolean = false, // 是否找到四边形（没找到判误检/漏检）
        val geomOk: Boolean = false,    // 几何五项校验是否通过（形状歪判几何）
        val regressOk: Boolean = false  // 关键点配准是否成功
    )

    /** 最近一次 calibrate 的诊断结果（线程安全，VideoAnalyzer 每次调用后读取） */
    @Volatile
    var lastDiagnosis = Diagnosis()


    private const val WORK_MAX_SIDE = 720
    private const val THETA_STEP_DEG = 2.0
    private const val WHITE_SAT_DIFF = 90 // 放宽到 90：发黄的场地线饱和度会略高

    /** 方向组：同方向直线簇（角度 + 各线到原点距离 rho 列表 + 总投票数） */
    data class DirGroup(val angleDeg: Double, val rhos: List<Double>, val totalVotes: Int)

    /** 管道参数集（每套方案一套） */
    private data class Params(
        val strongRatio: Double,   // 霍夫保留线阈值（峰值比例，至少3票）
        val joinDeg: Double,       // 方向组聚类最大夹角（度）
        val areaMin: Double,       // 场地面积占画面下限
        val sideMax: Double,       // 长边/短边比上限
        val scoreMin: Double,      // 四边形贴合评分下限
        val partialFitMin: Double, // 部分构造贴合度单边下限
        val partialFitCount: Int   // 部分构造需贴合边数下限
    )

    private val paramsA = Params(0.08, 12.0, 0.04, 10.0, 0.04, 0.10, 1)
    private val paramsB = Params(0.05, 20.0, 0.03, 12.0, 0.03, 0.08, 1)
    private val paramsC = Params(0.04, 30.0, 0.02, 14.0, 0.02, 0.05, 1)

    /** 依次返回 左上、右上、右下、左下 四个角点（原图像素坐标），失败返回 null。
     *  @param roi 用户框选的目标场地凸多边形（原图坐标，≥4点），非空时所有候选线段/关键点
     *             先做掩码拦截——落在多边形外的线直接丢弃（B误检 100% 修复：邻场线/广告/地板缝） */
    fun calibrate(frame: Bitmap, roi: List<PointF>? = null, aiMask: BooleanArray? = null): List<PointF>? {
        val srcW = frame.width
        val srcH = frame.height
        val scale = min(1f, WORK_MAX_SIDE.toFloat() / max(srcW, srcH))
        val W = (srcW * scale).roundToInt().coerceAtLeast(1)
        val H = (srcH * scale).roundToInt().coerceAtLeast(1)
        // ROI 掩码（多边形外全部拦截）
        val roiMask = if (roi != null && roi.size >= 3) polygonMask(W, H, roi, scale) else null

        // v2.15 相机去畸变（直线自标定）：桶形畸变会把白线拍弯，Hough/直线假设全部失效。
        // 无标定板时用"场景长直线最直化"估算径向系数 k1，先校正再检测。
        val undistorted = try { DistortionEstimator.undistort(frame) } catch (e: Exception) { null }
        val bmp = if (undistorted != null) {
            if (scale < 1f) Bitmap.createScaledBitmap(undistorted, W, H, true)
            else undistorted
        } else {
            if (scale < 1f) Bitmap.createScaledBitmap(frame, W, H, true) else frame
        }

        // v2.14 背景色验证降级为"参考"（不再硬性拒绝）：
        // 用户实测 v2.13 因地板色验证太严导致 E101——实际场馆地板可能是
        // 灰色/暗色/反光，或画面被墙面观众席占据。白线四边形本身清晰即可标定。
        val floorOk = verifyCourtFloorColor(bmp, W, H)

        // v2.15 诊断：白色像素占比（漏检/误检分类依据）
        var whitePx = 0
        for (i in 0 until W * H step 7) {
            val p = bmp.getPixel(i % W, i / W)
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            if (mx > 150 && mx - mn < 60) whitePx++
        }
        val sampled = (W * H + 6) / 7
        val whiteRatio = whitePx.toFloat() / sampled

        // ---- 1) 灰度 + 饱和度 ----
        val pixels = IntArray(W * H)
        bmp.getPixels(pixels, 0, W, 0, 0, W, H)
        if (bmp !== frame) bmp.recycle()
        val gray = IntArray(W * H)
        val sat = IntArray(W * H)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            sat[i] = max(r, max(g, b)) - min(r, min(g, b))
        }

        // ---- 1.5) v3 关键点配准通道（宿主 8/8 样本验证版，优先）：
        // 绿色地板掩码 + 多方向游程线段 + 平行线族选向 + 交点十字校验 + BWF 模板 RANSAC + 球网选场。
        // 旧 ABC 管线常输出"半构造贴合"的结果而被校验放行，必须让新算法先跑。 ----
        val roiScaled = roi?.map { PointF(it.x * scale, it.y * scale) }
        val regressed = try { CourtRegressor.regress(bmp, roiScaled, aiMask) } catch (e: Exception) { null }
        if (regressed != null) {
            val v = GeometricVerifier.verify(regressed, srcW, srcH)
            lastDiagnosis = Diagnosis(whiteRatio, true, v.ok, v.ok)
            if (v.ok) return regressed
        }

        // ---- 2) ABC 三套方案级联，任一成功即返回 ----
        // 方案A：多阈值白线 + Sobel（正常参数）
        val otsuT = otsu(gray).coerceIn(100, 230) // 下限从150降到100，暗场馆不丢线
        val masksA = listOf(
            applyRoi(whiteMask(gray, sat, otsuT), roiMask),
            applyRoi(whiteMask(gray, sat, 130), roiMask), // 170 → 130
            applyRoi(whiteMask(gray, sat, 100), roiMask), // 新增更低阈值兜底
            applyRoi(sobelMask(gray, W, H, 160), roiMask)
        )
        var corners = runPipelines(masksA, W, H, paramsA, roiMask)
        if (corners != null) {
            val scaled = scaleCorners(corners, scale)
            val v = GeometricVerifier.verify(scaled, srcW, srcH)
            lastDiagnosis = Diagnosis(whiteRatio, true, v.ok, false)
            if (v.ok) return scaled
        }

        // 方案B：低阈值 Sobel 边缘 + 宽松聚类
        val masksB = listOf(applyRoi(sobelMask(gray, W, H, 100), roiMask))
        corners = runPipelines(masksB, W, H, paramsB, roiMask)
        if (corners != null) {
            val scaled = scaleCorners(corners, scale)
            val v = GeometricVerifier.verify(scaled, srcW, srcH)
            lastDiagnosis = Diagnosis(whiteRatio, true, v.ok, false)
            if (v.ok) return scaled
        }

        // 方案C：超低阈值边缘 + 最宽松参数（最后防线）
        val masksC = listOf(applyRoi(sobelMask(gray, W, H, 60), roiMask))
        corners = runPipelines(masksC, W, H, paramsC, roiMask)
        if (corners != null) {
            val scaled = scaleCorners(corners, scale)
            val v = GeometricVerifier.verify(scaled, srcW, srcH)
            lastDiagnosis = Diagnosis(whiteRatio, true, v.ok, false)
            if (v.ok) return scaled
        }

        // v2.15 关键点配准通道（v3 已在 1.5 步优先执行，此处仅 ABC 失败后的最后兜底重试）：
        // 白线扫描 -> 线交点（关键点）-> RANSAC 拟合 BWF 模板 -> 反投影 4 角。
        val regressedFallback = try { CourtRegressor.regress(bmp, roiScaled, aiMask) } catch (e: Exception) { null }
        if (regressedFallback != null) {
            val v = GeometricVerifier.verify(regressedFallback, srcW, srcH)
            lastDiagnosis = Diagnosis(whiteRatio, true, v.ok, v.ok)
            if (v.ok) return regressedFallback
        }

        // 全部通道失败/校验不过：返回 ABC 的最优结果由上层多帧投票兜底（不在这里硬判）
        if (corners != null) {
            val scaled = scaleCorners(corners, scale)
            lastDiagnosis = Diagnosis(whiteRatio, true, false, false)
            return scaled
        }
        lastDiagnosis = Diagnosis(whiteRatio, false, false, false)
        return null
    }

    /**
     * v2.13 地板色验证：统计采样像素，判断画面主体是否为羽毛球馆地板色
     * （绿色/蓝色/红色系：木地板/塑胶地常见色）。
     * 用户要求："如果检测发现识别出来的长实线不在这种场地上，就判断为误识别"。
     */
    private fun verifyCourtFloorColor(bmp: Bitmap, W: Int, H: Int): Boolean {
        val step = max(1, min(W, H) / 60) // 采样步长
        var total = 0
        var courtHue = 0
        var y = 0
        while (y < H) {
            var x = 0
            while (x < W) {
                val p = bmp.getPixel(x, y)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = mx - mn
                // 低饱和（灰/黑/白）不参与判定；饱和度适中才算"有色地板"
                if (sat > 18) {
                    total++
                    // 色相粗判：红(0)/黄(60)/绿(120)/蓝(240)
                    val hue = when (mx) {
                        r -> ((g - b).toFloat() / sat * 60f + 360f) % 360f
                        g -> ((b - r).toFloat() / sat * 60f + 120f) % 360f
                        else -> ((r - g).toFloat() / sat * 60f + 240f) % 360f
                    }
                    val isCourtColor =
                        (hue in 20f..80f) ||   // 橙黄/黄（塑胶场）
                        (hue in 90f..160f) ||  // 绿（木地板上漆/塑胶绿）
                        (hue in 190f..280f)    // 蓝（常见羽毛球塑胶场）
                    if (isCourtColor) courtHue++
                }
                x += step
            }
            y += step
        }
        // 有色采样中 ≥35% 是地板色系才算"在这种场地上"
        return total > 0 && courtHue.toFloat() / total >= 0.35f
    }

    /**
     * v2.13 长实线验证（用户要求）：真正的场地线是"长实线"——
     * 白线在绿色/蓝色/红色地板上非常显眼。对输出四边形做最后校验：
     * 每条边都要落在白色亮线上（边的中心采样 ≥3 个点亮度高且颜色偏白）。
     */
    fun verifyLongLines(frame: Bitmap, corners: List<PointF>): Boolean {
        if (corners.size < 4) return false
        var hit = 0
        var visibleEdges = 0
        val edges = arrayOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
        for ((a, b) in edges) {
            val p1 = corners[a]
            val p2 = corners[b]
            val len = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
            if (len < frame.width * 0.12) continue // 边太短不可能是场地外边界
            // 画面内可见段占比：整条边的中心段有多少在画面内（角在画面外的场景，外边会超出画面）
            var inFrame = 0
            var total = 0
            var whitePx = 0
            var sample = 0
            var t = 0.15f
            while (t <= 0.85f) {
                val rawX = p1.x + (p2.x - p1.x) * t
                val rawY = p1.y + (p2.y - p1.y) * t
                total++
                if (rawX in 0f..frame.width.toFloat() && rawY in 0f..frame.height.toFloat()) {
                    inFrame++
                    val q = frame.getPixel(rawX.toInt().coerceIn(0, frame.width - 1), rawY.toInt().coerceIn(0, frame.height - 1))
                    val r = q shr 16 and 0xFF
                    val g = q shr 8 and 0xFF
                    val b = q and 0xFF
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    if (mx > 150 && mx - mn < 60) whitePx++ // 白色亮线
                    sample++
                }
                t += 0.1f
            }
            // 画面内可见段 < 40% 的边（大半在画面外）不参与判罚：用户视角场地的角经常拍不到
            if (total > 0 && inFrame.toFloat() / total < 0.4f) continue
            visibleEdges++
            if (sample > 0 && whitePx.toFloat() / sample >= 0.55f) hit++
        }
        // v2.14：画面内可见边 ≥2 条是白色长实线即可信
        // （用户视频是斜拍远景，场地 1-2 个角常在画面外，v2.13 要求3条边导致 E101）
        return hit >= 2 && visibleEdges >= 2
    }

    private fun scaleCorners(corners: List<PointF>, scale: Float): List<PointF> {
        val inv = 1f / scale
        return corners.map { PointF(it.x * inv, it.y * inv) }
    }

    private fun runPipelines(masks: List<BooleanArray>, W: Int, H: Int, params: Params, roiMask: BooleanArray? = null): List<PointF>? {
        for (mask in masks) {
            val r = calibrateWithMask(mask, W, H, params, roiMask)
            if (r != null) return r
        }
        return null
    }

    /** 白线掩码：亮度达标且低饱和（饱和度上限放宽到 90） */
    /** 掩码位与：ROI 多边形外的像素全部清零 */
    private fun applyRoi(mask: BooleanArray, roiMask: BooleanArray?): BooleanArray {
        if (roiMask == null) return mask
        for (i in mask.indices) {
            if (!roiMask[i]) mask[i] = false
        }
        return mask
    }

    /** 凸/凹多边形 → 二值掩码（Canvas Path 填充，外黑内白） */
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
            val p0 = roiPx[0]
            path.moveTo(p0.x * scale, p0.y * scale)
            for (i in 1 until roiPx.size) {
                val p = roiPx[i]
                path.lineTo(p.x * scale, p.y * scale)
            }
            path.close()
            canvas.drawPath(path, paint)
            val px = IntArray(W * H)
            bmp.getPixels(px, 0, W, 0, 0, W, H)
            val mask = BooleanArray(W * H)
            for (i in px.indices) {
                // 黑色填充：alpha 高位的非零 = 多边形内
                mask[i] = (px[i] ushr 24) > 0x7F
            }
            return mask
        } finally {
            bmp.recycle()
        }
    }

    private fun whiteMask(gray: IntArray, sat: IntArray, threshold: Int): BooleanArray {
        val mask = BooleanArray(gray.size)
        for (i in gray.indices) {
            if (gray[i] >= threshold && sat[i] <= WHITE_SAT_DIFF) mask[i] = true
        }
        return mask
    }

    /** Sobel 亮度边缘掩码：不依赖颜色，明显的亮度边缘都保留（阈值越低越宽松） */
    private fun sobelMask(gray: IntArray, W: Int, H: Int, edgeThresh: Int): BooleanArray {
        val mask = BooleanArray(W * H)
        for (y in 1 until H - 1) {
            val row0 = (y - 1) * W
            val row1 = y * W
            val row2 = (y + 1) * W
            for (x in 1 until W - 1) {
                if (gray[row1 + x] < 30) continue
                val gx = (gray[row0 + x + 1] + 2 * gray[row1 + x + 1] + gray[row2 + x + 1]) -
                    (gray[row0 + x - 1] + 2 * gray[row1 + x - 1] + gray[row2 + x - 1])
                val gy = (gray[row2 + x - 1] + 2 * gray[row2 + x] + gray[row2 + x + 1]) -
                    (gray[row0 + x - 1] + 2 * gray[row0 + x] + gray[row0 + x + 1])
                if (abs(gx) + abs(gy) > edgeThresh) mask[row1 + x] = true
            }
        }
        return mask
    }

    /** 在给定掩码上执行：形态学闭 -> 霍夫 -> 方向组枚举 -> 四边形评分；失败时"部分线+羽毛球先验"构造 */
    private fun calibrateWithMask(maskIn: BooleanArray, W: Int, H: Int, params: Params, roiMask: BooleanArray? = null): List<PointF>? {
        // ---- 形态学闭：膨胀 + 腐蚀（3x3 十字）----
        val closed = BooleanArray(W * H)
        val dil = BooleanArray(W * H)
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                if (maskIn[y * W + x] || maskIn[y * W + x - 1] || maskIn[y * W + x + 1] ||
                    maskIn[(y - 1) * W + x] || maskIn[(y + 1) * W + x]
                ) dil[y * W + x] = true
            }
        }
        for (y in 1 until H - 1) {
            for (x in 1 until W - 1) {
                if (dil[y * W + x] && dil[y * W + x - 1] && dil[y * W + x + 1] &&
                    dil[(y - 1) * W + x] && dil[(y + 1) * W + x]
                ) closed[y * W + x] = true
            }
        }

        // ---- 霍夫直线投票 ----
        val numTheta = (180.0 / THETA_STEP_DEG).toInt()
        val diag = ceil(sqrt((W * W + H * H).toDouble())).toInt()
        val rhoOffset = diag
        val rhoBins = 2 * diag + 1
        val acc = IntArray(numTheta * rhoBins)
        for (y in 0 until H step 2) {
            for (x in 0 until W step 2) {
                if (!closed[y * W + x]) continue
                for (tt in 0 until numTheta) {
                    val theta = tt * THETA_STEP_DEG * PI / 180.0
                    val rho = x * cos(theta) + y * sin(theta)
                    val r = (rho + rhoOffset).roundToInt().coerceIn(0, rhoBins - 1)
                    acc[tt * rhoBins + r]++
                }
            }
        }

        // ---- 峰值提取 ----
        data class Line(val rho: Double, val thetaDeg: Double, val votes: Int)
        val candidates = ArrayList<Line>()
        var maxVotes = 0
        for (tt in 0 until numTheta) {
            val col = tt * rhoBins
            var bestR = -1
            var bestV = 0
            var i = 0
            while (i < rhoBins) {
                var run = i
                var runV = acc[col + i]
                while (run + 1 < rhoBins && acc[col + run + 1] >= runV - 2) {
                    run++
                    runV = max(runV, acc[col + run])
                }
                if (runV >= 3) {
                    if (runV > bestV) { bestV = runV; bestR = (i + run) / 2 }
                    i = run + 1
                } else i++
            }
            if (bestV >= 3) {
                val rho = bestR - rhoOffset
                candidates.add(Line(rho.toDouble(), tt * THETA_STEP_DEG, bestV))
                if (bestV > maxVotes) maxVotes = bestV
            }
        }
        if (candidates.isEmpty()) return null

        // 保留高票线：下限降到 8%，且至少保留得票数 >= 3 的线（远景 maxVotes 只有 6~10 时也能保留足够线条）
        val keepVotes = max(3, (maxVotes * params.strongRatio).toInt())
        val strong = candidates.filter { it.votes >= keepVotes }

        // ---- 角度聚类成方向组 ----
        val sorted = strong.sortedBy { it.thetaDeg }
        val groups = ArrayList<DirGroup>()
        var curAngles = ArrayList<Double>()
        var curRhos = ArrayList<Double>()
        var curVotes = 0
        var curStart = sorted.first().thetaDeg
        for (l in sorted) {
            if (curAngles.isEmpty() || angleJoin(curStart, curAngles.lastOrNull(), l.thetaDeg, params.joinDeg)) {
                curAngles.add(l.thetaDeg)
                curRhos.add(l.rho)
                curVotes += l.votes
            } else {
                groups.add(DirGroup(avgAngle(curAngles), curRhos.toList(), curVotes))
                curAngles = ArrayList(listOf(l.thetaDeg))
                curRhos = ArrayList(listOf(l.rho))
                curVotes = l.votes
                curStart = l.thetaDeg
            }
        }
        if (curAngles.isNotEmpty()) groups.add(DirGroup(avgAngle(curAngles), curRhos.toList(), curVotes))
        if (groups.size < 2) return null
        val gSorted = groups.sortedByDescending { it.totalVotes }

        // ---- 枚举方向组组合，构造候选四边形并评分 ----
        data class Candidate(val corners: List<PointF>, val score: Double, val area: Double)
        val candidatesQ = ArrayList<Candidate>()
        for (gi in gSorted.indices) {
            val A = gSorted[gi]
            if (A.rhos.size < 2) continue
            for (gj in gi + 1 until gSorted.size) {
                val B = gSorted[gj]
                if (B.rhos.size < 2) continue
                val diff = angleDiff(A.angleDeg, B.angleDeg)
                if (diff !in 70.0..110.0) continue
                val aRhos = A.rhos.sorted()
                val bRhos = B.rhos.sorted()
                val a1 = quantile(aRhos, 0.12)
                val a2 = quantile(aRhos, 0.88)
                val b1 = quantile(bRhos, 0.12)
                val b2 = quantile(bRhos, 0.88)
                if (abs(a2 - a1) < 6 || abs(b2 - b1) < 6) continue

                val thetaA = A.angleDeg * PI / 180.0
                val thetaB = B.angleDeg * PI / 180.0
                val pts = ArrayList<PointF>()
                for (ra in listOf(a1, a2)) {
                    for (rb in listOf(b1, b2)) {
                        val inter = intersect(ra, thetaA, rb, thetaB) ?: continue
                        pts.add(inter)
                    }
                }
                if (pts.size != 4) continue
                val corners = orderCorners(pts)
                if (corners == null) continue
                val area = quadArea(corners)
                val areaRatio = area / (W * H)
                if (areaRatio < params.areaMin || areaRatio > 0.97) continue
                val sides = edgeLengths(corners)
                val minSide = sides.min() ?: continue
                val maxSide = sides.max() ?: continue
                if (maxSide / minSide > params.sideMax) continue
                val fit = lineFitScore(closed, W, H, corners)
                // v2.17 置信度排序：几何验证不过的候选直接丢弃；过则按
                // 线贴合(IoU) + 几何一致性 + ROI 交集占比 加权选最优
                val gv = GeometricVerifier.verify(corners, W, H)
                if (!gv.ok) continue
                val geoScore = gv.score
                val roiOverlap = if (roiMask != null) quadRoiOverlap(corners, roiMask, W, H) else 1.0
                val score = fit * 0.5 + geoScore * 0.25 + roiOverlap * 0.25
                candidatesQ.add(Candidate(corners, score, area))
            }
        }
        if (candidatesQ.isEmpty()) return partialConstruct(maskIn, W, H, groups, params, roiMask)
        val best = candidatesQ.maxBy { it.score }
        if (best.score < params.scoreMin) return partialConstruct(maskIn, W, H, groups, params, roiMask)
        return best.corners
    }

    /**
     * 阶段2：部分线 + 羽毛球先验构造完整场地（用户场景：斜拍远景，场地近端角在画面外）
     *
     * 原理：检测到"一组 ≥2 条平行线（边线）" + "另一组 ≥1 条线（底线/横线）"后，
     * 用羽毛球场固定长宽比（13.40m / 6.10m ≈ 2.196）沿纵深方向把场地延伸到画面边缘，
     * 构造出完整羽毛球场地覆盖在画面上——不要求四条边都可见。
     */
    private fun partialConstruct(
        maskIn: BooleanArray, W: Int, H: Int, groups: List<DirGroup>, params: Params,
        roiMask: BooleanArray? = null
    ): List<PointF>? {
        if (groups.size < 2) return null
        // 主组：平行线最多的一组（边线）
        val gMain = groups.maxByOrNull { it.rhos.size } ?: return null
        if (gMain.rhos.size < 2) return null
        // 副组：与主组近似垂直、线数最多的一组（底线/横线）
        val gOther = groups.filter { angleDiff(it.angleDeg, gMain.angleDeg) in 70.0..110.0 }
            .maxByOrNull { it.rhos.size } ?: return null
        if (gOther.rhos.isEmpty()) return null
        // 副组有几条线都可以：取"离画面中心最近"的那条作为已知横线（rho 绝对值最小者）
        val otherRho = gOther.rhos.sortedBy { abs(it) }.first()

        val m = gMain.rhos.sorted()
        val r1 = quantile(m, 0.15)
        val r2 = quantile(m, 0.85)
        if (abs(r2 - r1) < 8) return null
        val thetaM = gMain.angleDeg * PI / 180.0
        val thetaO = gOther.angleDeg * PI / 180.0

        val p1 = intersect(r1, thetaM, otherRho, thetaO) ?: return null
        val p2 = intersect(r2, thetaM, otherRho, thetaO) ?: return null

        // 纵深方向 = 主组线走向；选"远离画面中心"的方向（场地朝画面边缘延伸）
        val dx = cos(thetaM + PI / 2)
        val dy = sin(thetaM + PI / 2)
        val cx = (p1.x + p2.x) / 2.0 - W / 2.0
        val cy = (p1.y + p2.y) / 2.0 - H / 2.0
        val dot = dx * cx + dy * cy
        val ux = if (dot > 0) dx else -dx
        val uy = if (dot > 0) dy else -dy

        // 延伸长度：羽毛球先验 2.196 倍底线长度；若先到达画面边缘则以画面边缘为准
        val baseLen = max(abs(p2.x - p1.x).toDouble(), abs(p2.y - p1.y).toDouble())
        val depthFull = baseLen * 2.196
        val tB = min(rayToBoundary(p1.x.toDouble(), p1.y.toDouble(), ux, uy, W, H),
                     rayToBoundary(p2.x.toDouble(), p2.y.toDouble(), ux, uy, W, H))
        var D = if (tB < depthFull) tB else depthFull
        if (D < min(W, H) * 0.35) D = min(W, H) * 0.35
        if (D <= 0) return null

        val p1n = PointF((p1.x + ux * D).toFloat(), (p1.y + uy * D).toFloat())
        val p2n = PointF((p2.x + ux * D).toFloat(), (p2.y + uy * D).toFloat())
        val quad = listOf(p1, p2, p2n, p1n)
        val corners = orderCorners(quad) ?: return null
        val area = quadArea(corners)
        if (area / (W * H) < params.areaMin * 0.75) return null

        // 贴合度验证：至少 params.partialFitCount 条边有真实线支撑（可见边），防止墙面/座椅噪声线伪造场地
        val fits = edgeFitScores(maskIn, W, H, corners)
        if (fits.count { it > params.partialFitMin } < params.partialFitCount) return null
        return corners
    }

    /** 射线到画面边界的距离（单位：方向向量长度倍数）；射线不出画面则返回极大值 */
    private fun rayToBoundary(px: Double, py: Double, ux: Double, uy: Double, W: Int, H: Int): Double {
        var t = Double.MAX_VALUE
        if (ux > 1e-9) t = min(t, (W - 1 - px) / ux)
        if (ux < -1e-9) t = min(t, px / -ux)
        if (uy > 1e-9) t = min(t, (H - 1 - py) / uy)
        if (uy < -1e-9) t = min(t, py / -uy)
        return t
    }

    /** Otsu 最大类间方差阈值 */
    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val total = gray.size
        var sum = 0L
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0L
        var wB = 0
        var best = 120
        var bestVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > bestVar) { bestVar = v; best = t }
        }
        return best
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 180.0
        if (d > 90) d = 180 - d
        return d
    }

    /** 角度环形分组：检查新角度能否并入当前组（组内跨度 <= joinDeg） */
    private fun angleJoin(start: Double, last: Double?, next: Double, joinDeg: Double): Boolean {
        if (last == null) return true
        val span = if (abs(next - start) <= 90) abs(next - start) else 180 - abs(next - start)
        return span <= joinDeg
    }

    private fun avgAngle(angles: List<Double>): Double {
        var sx = 0.0; var sy = 0.0
        for (a in angles) {
            val r = a * PI / 180.0
            sx += cos(2 * r); sy += sin(2 * r)
        }
        var avg = 0.5 * atan2(sy, sx) * 180.0 / PI
        if (avg < 0) avg += 180
        return avg
    }

    /** 稳健分位数 */
    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** 两条极坐标直线求交（像素坐标） */
    private fun intersect(r1: Double, t1: Double, r2: Double, t2: Double): PointF? {
        val det = sin(t2 - t1)
        if (abs(det) < 1e-6) return null
        val x = (r2 * sin(t1) - r1 * sin(t2)) / det
        val y = (r1 * cos(t2) - r2 * cos(t1)) / det
        return PointF(x.toFloat(), y.toFloat())
    }

    /** 排序为 左上 右上 右下 左下（凸四边形） */
    private fun orderCorners(pts: List<PointF>): List<PointF>? {
        val n = pts.size
        var sign = 0
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val c = pts[(i + 2) % n]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross != 0f) {
                val s = if (cross > 0) 1 else -1
                if (sign == 0) sign = s else if (sign != s) return null
            }
        }
        if (sign == 0) return null
        val sortedByY = pts.sortedBy { it.y }
        val topRow = sortedByY.take(2).sortedBy { it.x }
        val bottomRow = sortedByY.drop(2).sortedBy { it.x }
        return listOf(topRow[0], topRow[1], bottomRow[1], bottomRow[0])
    }

    /** 四边形面积（鞋带公式） */
    private fun quadArea(p: List<PointF>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2.0
    }

    /** 四条边长度 */
    private fun edgeLengths(p: List<PointF>): List<Double> {
        return (0 until 4).map { i ->
            val a = p[i]; val b = p[(i + 1) % 4]
            sqrt((a.x - b.x).toDouble() * (a.x - b.x) + (a.y - b.y).toDouble() * (a.y - b.y))
        }
    }

    /**
     * 白线贴合度：沿四边形四边均匀采样，统计采样点附近是否有掩码线像素。
     * 得分 0-1，越高说明检测框越贴合实际场地线。画面外采样点跳过（不计分）。
     */
    /** 候选四边形与 ROI 掩码的交集占比：5 点采样（4 角 + 中心）落在多边形内的比例 */
    private fun quadRoiOverlap(corners: List<PointF>, roiMask: BooleanArray, W: Int, H: Int): Double {
        if (corners.size != 4) return 0.0
        val cx = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4f
        val cy = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4f
        val samples = listOf(corners[0], corners[1], corners[2], corners[3], PointF(cx, cy))
        var hit = 0
        for (p in samples) {
            val px = p.x.toInt().coerceIn(0, W - 1)
            val py = p.y.toInt().coerceIn(0, H - 1)
            if (roiMask[py * W + px]) hit++
        }
        return hit / 5.0
    }

    private fun lineFitScore(mask: BooleanArray, W: Int, H: Int, corners: List<PointF>): Double {
        val fits = edgeFitScores(mask, W, H, corners)
        if (fits.isEmpty()) return 0.0
        return fits.average()
    }

    /** 每条边单独的白线贴合度（返回 4 个值，对应 4 条边） */
    private fun edgeFitScores(mask: BooleanArray, W: Int, H: Int, corners: List<PointF>): List<Double> {
        val scores = ArrayList<Double>(4)
        for (e in 0 until 4) {
            val a = corners[e]
            val b = corners[(e + 1) % 4]
            var hit = 0
            var total = 0
            val steps = 40
            for (k in 0..steps) {
                val f = k.toFloat() / steps
                val x = (a.x + (b.x - a.x) * f).roundToInt()
                val y = (a.y + (b.y - a.y) * f).roundToInt()
                if (x < 0 || x >= W || y < 0 || y >= H) continue // 画面外采样点不计
                total++
                var found = false
                for (dy in -3..3) {
                    val ny = y + dy
                    if (ny < 0 || ny >= H) continue
                    for (dx in -3..3) {
                        val nx = x + dx
                        if (nx < 0 || nx >= W) continue
                        if (mask[ny * W + nx]) { found = true; break }
                    }
                    if (found) break
                }
                if (found) hit++
            }
            scores.add(if (total == 0) 0.0 else hit.toDouble() / total)
        }
        return scores
    }
}
