package com.badmintonspeed.app.analysis

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/** 单个骨骼关键点（图像像素坐标） */
data class PoseKeyPoint(
    val x: Float,   // 图像像素 x
    val y: Float,   // 图像像素 y
    val z: Float,   // 相对深度（模型输出，越小越靠近相机，用于景深 3D）
    val visibility: Float
)

/** 一个人物的骨骼（33 个关键点，MediaPipe Pose 标准） */
data class PoseSkeleton(val points: List<PoseKeyPoint>)

/**
 * 骨骼识别（用户要求：加骨骼识别运动员击球动作 + 一直随视频播放动态显示）：
 * MediaPipe PoseLandmarker（官方 AI 模型，单人/多人 33 关键点，含相对深度 z 用于景深）。
 * 模型：assets/models/pose_landmarker.task（MediaPipe 官方 lite float16，5.7MB）
 *
 * 使用 IMAGE 模式逐帧检测（分析阶段每隔几帧跑一次，结果页按播放进度叠加）。
 */
class PoseDetector(context: Context) {

    private val landmarker: PoseLandmarker = run {
        val base = BaseOptions.builder()
            .setModelAssetPath("models/pose_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(6)              // 最多识别 6 名运动员
            .setMinPoseDetectionConfidence(0.4f)
            .setMinPosePresenceConfidence(0.4f)
            .build()
        PoseLandmarker.createFromOptions(context.applicationContext, opts)
    }

    /**
     * 对单帧做骨骼检测。
     * @return 该帧检测到的所有人物骨骼（像素坐标，已按帧宽高换算）
     */
    fun detect(bitmap: Bitmap): List<PoseSkeleton> {
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result: PoseLandmarkerResult = landmarker.detect(mpImage)
            val skeletons = ArrayList<PoseSkeleton>()
            result.landmarks()?.let { persons ->
                for (person in persons) {
                    val pts = ArrayList<PoseKeyPoint>()
                    for (lm in person) {
                        pts.add(
                            PoseKeyPoint(
                                x = lm.x() * bitmap.width,
                                y = lm.y() * bitmap.height,
                                z = lm.z(),
                                visibility = lm.visibility().orElse(0f)
                            )
                        )
                    }
                    if (pts.isNotEmpty()) skeletons.add(PoseSkeleton(pts))
                }
            }
            skeletons
        } catch (e: Exception) {
            emptyList() // 防闪退
        }
    }

    fun close() {
        runCatching { landmarker.close() }
    }

    companion object {
        /** MediaPipe Pose 33 点骨骼连接对（画骨架线用） */
        val CONNECTIONS: Array<IntArray> = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 7),
            intArrayOf(0, 4), intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 8),
            intArrayOf(9, 10), intArrayOf(11, 12),
            intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(15, 17),
            intArrayOf(15, 19), intArrayOf(15, 21), intArrayOf(17, 19),
            intArrayOf(12, 14), intArrayOf(14, 16), intArrayOf(16, 18),
            intArrayOf(16, 20), intArrayOf(16, 22), intArrayOf(18, 20),
            intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
            intArrayOf(23, 25), intArrayOf(24, 26),
            intArrayOf(25, 27), intArrayOf(26, 28), intArrayOf(27, 29),
            intArrayOf(28, 30), intArrayOf(29, 31), intArrayOf(30, 32),
            intArrayOf(27, 31), intArrayOf(28, 32)
        )
    }
}
