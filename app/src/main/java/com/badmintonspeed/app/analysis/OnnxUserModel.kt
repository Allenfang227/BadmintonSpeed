package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.badmintonspeed.app.data.BallLearner
import java.io.File
import java.nio.FloatBuffer

/**
 * 用户训练的 ONNX 模型推理器（v2.24）。
 * 加载 ball_model/shuttle_user.onnx（训练完导出），对候选 patch 打分 0~1，
 * 与内置 shuttle.onnx 构成"用户模型通道"：训练过的外观更容易被识别到。
 * 加载失败（未训练/文件缺失/格式损坏）时返回 null，调用方自动回退旧模板打分。
 */
class OnnxUserModel private constructor(private val session: OrtSession) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** patch → 48×48 灰度归一化 → [1,2304] → Gemm+Sigmoid → 多模板取 max 打分 0~1 */
    fun score(patch: Bitmap): Float {
        return try {
            val t = BallLearner.toTemplatePublic(patch)
            val buf = FloatBuffer.wrap(t)
            val input = OnnxTensor.createTensor(env, buf, longArrayOf(1L, t.size.toLong()))
            val result = try {
                session.run(mapOf("input" to input))
            } finally {
                input.close()
            }
            result.use { r ->
                val v = r[0].value as Array<FloatArray>
                v[0].maxOrNull() ?: 0f
            }
        } catch (e: Exception) {
            -1f // 推理异常：调用方视为"该通道不可用"，回退模板打分
        }
    }

    companion object {
        /** 按优先级尝试加载：公共目录(用户可见/分享导入) → 私有权威目录 */
        fun load(context: Context): OnnxUserModel? {
            val names = listOf("shuttle_user.onnx", "user.onnx")
            val roots = ArrayList<File>()
            try {
                roots.add(File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "BadmintonSpeed/ball_model"))
            } catch (e: Exception) { /* 无公共目录权限时跳过 */ }
            roots.add(File(context.filesDir, "BadmintonSpeed/ball_model"))
            for (root in roots) {
                for (name in names) {
                    val f = File(root, name)
                    if (!f.exists()) continue
                    return try {
                        val env = OrtEnvironment.getEnvironment()
                        val sess = env.createSession(f.absolutePath, OrtSessions.options())
                        OnnxUserModel(sess)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            return null
        }
    }
}
