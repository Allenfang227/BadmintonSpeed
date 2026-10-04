package com.badmintonspeed.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.PointF
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地羽毛球检测模型学习（v2.18）
 * 用户上传"红框标注羽毛球"的图片 → 解析红框 → 裁剪正样本入库 →
 * 训练轻量模板库（灰度 NCC 模板 + 形状约束）→ 实测时作为球检测的本地模型通道。
 * 这是真实训练（从样本学习外观），非假识别：检测时用学到的模板对候选区域打分。
 */
object BallLearner {
    const val TEMPLATE_SIZE = 48
    const val MATCH_THRESHOLD = 0.52f

    data class LearnedModel(
        val templates: List<FloatArray>,   // 每个 48×48 灰度归一化模板
        val labels: List<String>,
        val count: Int
    )

    /**
     * 解析图片里的红色标注框（v2.21 增强：只识别"空心红框"）
     *
     * 原算法会把任何大片红色区域（广告、球衣、海报、墙面）当成框 → 误检。
     * 改进：候选连通域必须同时满足"空心框"特征——
     *   1. 红色像素占 bbox 面积比例低（fillRatio < 0.5，实心红块接近 1.0）
     *   2. bbox 四边边带红色占比高（≥ 0.35，说明红色集中在边框轮廓上）
     *   3. 四边都有红色（框闭合），排除只有一条红边的物体
     */
    fun detectRedBoxes(bmp: Bitmap): List<Rect> {
        val W = bmp.width; val H = bmp.height
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        val red = BooleanArray(W * H)
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF
            val g = (px[i] shr 8) and 0xFF
            val b = px[i] and 0xFF
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val v = max
            val s = if (max == 0) 0 else (max - min) * 255 / max
            // 红色：饱和度/亮度高且 R 远大于 G+B（画框一般用亮红）
            if (s > 90 && v > 90 && r > 140 && r > g * 2 && r > b * 2) red[i] = true
        }
        // 连通域（4 邻接）收集
        val visited = BooleanArray(W * H)
        val boxes = ArrayList<Rect>()
        for (i in px.indices) {
            if (!red[i] || visited[i]) continue
            var minX = W; var minY = H; var maxX = -1; var maxY = -1; var cnt = 0
            val stack = ArrayList<Int>()
            stack.add(i); visited[i] = true
            while (stack.isNotEmpty()) {
                val cur = stack.removeAt(stack.size - 1)
                val x = cur % W; val y = cur / W
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                cnt++
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until W && ny in 0 until H) {
                        val ni = ny * W + nx
                        if (red[ni] && !visited[ni]) { visited[ni] = true; stack.add(ni) }
                    }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val frameArea = W * H
            // 基础过滤：太小（噪点）/ 太大（整图）/ 长宽比畸形
            if (cnt < 80 || cnt > frameArea * 0.6f) continue
            if (bw < 10 || bh < 10) continue
            val ar = maxOf(bw, bh).toFloat() / minOf(bw, bh)
            if (ar > 8f) continue

            // ===== 空心红框校验（v2.21）=====
            // 1) 填充率：红色像素 / bbox 面积。实心红块 ≈ 1.0；空心框（细线）通常 < 0.45
            val boxArea = bw.toLong() * bh
            val fillRatio = cnt.toFloat() / boxArea
            if (fillRatio >= 0.5f) continue   // 大片实心红 → 不是框

            // 2) 四边边带红色占比：取 bbox 上下左右各 3px 边带
            val band = 3
            var bandRed = 0; var bandPx = 0
            for (x in minX..maxX) for (dy in 0 until minOf(band, bh)) {
                if (red[(minY + dy) * W + x]) bandRed++
                bandPx++
                if (bh > band && red[(maxY - dy) * W + x]) bandRed++
                if (bh > band) bandPx++
            }
            for (y in minY..maxY) for (dx in 0 until minOf(band, bw)) {
                if (red[y * W + (minX + dx)]) bandRed++
                bandPx++
                if (bw > band && red[y * W + (maxX - dx)]) bandRed++
                if (bw > band) bandPx++
            }
            val bandRatio = bandRed.toFloat() / bandPx
            if (bandRatio < 0.35f) continue   // 红色不在边框上 → 不是框

            // 3) 四边各自都有红色（框闭合，排除只有单边红的物体）
            var topOk = false; var bottomOk = false; var leftOk = false; var rightOk = false
            for (x in minX..maxX) {
                if (red[minY * W + x]) topOk = true
                if (red[maxY * W + x]) bottomOk = true
            }
            for (y in minY..maxY) {
                if (red[y * W + minX]) leftOk = true
                if (red[y * W + maxX]) rightOk = true
            }
            if (!(topOk && bottomOk && leftOk && rightOk)) continue

            // 外扩 10%（把球完整包进来，去掉红框线）
            val padX = (bw * 0.12f).toInt() + 2
            val padY = (bh * 0.12f).toInt() + 2
            val rect = Rect(
                maxOf(0, minX - padX), maxOf(0, minY - padY),
                minOf(W, maxX + 1 + padX), minOf(H, maxY + 1 + padY)
            )
            boxes.add(rect)
        }
        return boxes
    }

    /** 裁剪正样本并保存（去掉红框线：红色像素用邻域均值替换） */
    fun saveSample(src: Bitmap, box: Rect, dir: File, name: String): Boolean {
        dir.mkdirs()
        val crop = Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height())
        // 去红框：红色像素替换为最接近的非红像素均值
        val w = crop.width; val h = crop.height
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF
            val g = (px[i] shr 8) and 0xFF
            val b = px[i] and 0xFF
            val isRed = r > 140 && r > g * 2 && r > b * 2
            if (isRed) {
                var nr = 0; var ng = 0; var nb = 0; var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = i % w + dx; val yy = i / w + dy
                    if (xx in 0 until w && yy in 0 until h) {
                        val c = px[yy * w + xx]
                        if (((c shr 16) and 0xFF) < 120 || ((c shr 16) and 0xFF) >= ((c shr 8) and 0xFF) * 2) {
                            nr += (c shr 16) and 0xFF; ng += (c shr 8) and 0xFF; nb += c and 0xFF; n++
                        }
                    }
                }
                if (n > 0) px[i] = Color.rgb(nr / n, ng / n, nb / n) else px[i] = Color.rgb(240, 240, 240)
            }
        }
        val clean = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        clean.setPixels(px, 0, w, 0, 0, w, h)
        // 归一化到 64×64 存储（训练时再缩到 48）
        val dst = Bitmap.createScaledBitmap(clean, 64, 64, true)
        val out = File(dir, name)
        dst.compress(Bitmap.CompressFormat.JPEG, 90, out.outputStream())
        return true
    }

    /** 训练：从 ball_samples/ 构建模板库 → ball_model/templates.json */
    fun train(samplesDir: File, modelDir: File): LearnedModel? {
        val files = samplesDir.listFiles()?.filter { it.name.endsWith(".jpg") || it.name.endsWith(".png") } ?: return null
        if (files.isEmpty()) return null
        modelDir.mkdirs()
        val temps = ArrayList<FloatArray>()
        val labels = ArrayList<String>()
        for (f in files) {
            val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null } ?: continue
            val t = toTemplate(bmp)
            temps.add(t); labels.add(f.name)
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        }
        if (temps.isEmpty()) return null
        // 保存模型
        val arr = JSONArray()
        for (t in temps) {
            val byte = ByteArray(t.size * 4)
            var k = 0
            for (v in t) {
                val bits = java.nio.ByteBuffer.allocate(4).putFloat(v).array()
                System.arraycopy(bits, 0, byte, k, 4); k += 4
            }
            arr.put(Base64.encodeToString(byte, Base64.NO_WRAP))
        }
        val json = JSONObject()
        json.put("templates", arr)
        json.put("labels", JSONArray(labels))
        json.put("size", TEMPLATE_SIZE)
        File(modelDir, "templates.json").writeText(json.toString())
        return LearnedModel(temps, labels, temps.size)
    }

    /** 加载模型 */
    fun loadModel(modelDir: File): LearnedModel? {
        val f = File(modelDir, "templates.json")
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val arr = json.getJSONArray("templates")
            val labs = json.getJSONArray("labels")
            val temps = ArrayList<FloatArray>()
            for (i in 0 until arr.length()) {
                val raw = Base64.decode(arr.getString(i), Base64.NO_WRAP)
                val t = FloatArray(raw.size / 4)
                var k = 0
                for (j in t.indices) {
                    t[j] = java.nio.ByteBuffer.wrap(raw, k, 4).float; k += 4
                }
                temps.add(t)
            }
            val labels = ArrayList<String>()
            for (i in 0 until labs.length()) labels.add(labs.getString(i))
            LearnedModel(temps, labels, temps.size)
        } catch (e: Exception) { null }
    }

    /**
     * 导出训练模型为 ONNX 格式（v2.24，用户要求"训练完之后导数为 onnx 格式"）。
     * 生成 ball_model/shuttle_user.onnx：Gemm(模板权重)+Sigmoid 线性分类器，
     * 是真正的 ONNX 模型文件，可被 ONNX Runtime 加载推理、可分享给他人直接使用。
     */
    fun exportOnnx(modelDir: File, dst: File? = null): File? {
        val m = loadModel(modelDir) ?: return null
        if (m.templates.isEmpty()) return null
        val target = dst ?: File(modelDir, "shuttle_user.onnx")
        try {
            target.writeBytes(OnnxExporter.buildModel(m.templates))
            return target
        } catch (e: Exception) {
            return null
        }
    }

    /** patch → 48×48 灰度归一化模板（公开：用户 ONNX 推理预处理复用同一口径） */
    fun toTemplatePublic(bmp: Bitmap): FloatArray = toTemplate(bmp)

    /** patch → 48×48 灰度归一化模板 */
    private fun toTemplate(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, TEMPLATE_SIZE, TEMPLATE_SIZE, true)
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val t = FloatArray(w * h)
        var sum = 0f; var sum2 = 0f
        for (i in px.indices) {
            val v = (((px[i] shr 16) and 0xFF) * 0.299f + ((px[i] shr 8) and 0xFF) * 0.587f + (px[i] and 0xFF) * 0.114f) / 255f
            t[i] = v; sum += v; sum2 += v * v
        }
        val mean = sum / t.size
        val std = kotlin.math.sqrt((maxOf(1e-6f, sum2 / t.size - mean * mean)).toDouble()).toFloat()
        for (i in t.indices) t[i] = (t[i] - mean) / std
        if (!small.isRecycled) small.recycle()
        return t
    }

    /** 匹配打分：与全部模板的均值归一化互相关(NCC)最大值 0~1 */
    fun match(patch: Bitmap, model: LearnedModel): Float {
        val t = toTemplate(patch)
        var best = 0f
        for (m in model.templates) {
            var dot = 0f
            for (i in t.indices) dot += t[i] * m[i]
            val ncc = dot / t.size
            if (ncc > best) best = ncc
        }
        return (best + 1f) / 2f  // 归一化到 0~1
    }
}
