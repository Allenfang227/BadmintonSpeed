package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 人员检测：用帧差法 + 运动轮廓代替 HOG（避免 native OOM 崩溃）。
 * 原理：连续两帧做差分 → 阈值二值化 → 找轮廓 → 按面积过滤为人员。
 * 优点：速度快 10 倍以上，内存占用极低，不会 native 崩溃。
 */
public class PlayerDetector {

    private static final String TAG = "PlayerDetector";
    private Mat prevFrame = null;
    private boolean hasPrev = false;

    public static class PlayerBox {
        public Rect rect;
        public double confidence;
        public int id;
    }

    public PlayerDetector() {
        // 无需初始化 HOG，避免 native 内存分配
    }

    /**
     * 用帧差法检测运动人员。
     * @param frame 当前帧（BGR）
     * @return 检测到的人员列表（运动区域边界框）
     */
    public List<PlayerBox> detect(Mat frame) {
        List<PlayerBox> result = new ArrayList<>();
        if (frame == null || frame.empty()) return result;

        // 缩放到 320px 宽度加速（避免 new Mat() 泄漏）
        Mat workFrame;
        double scale = 1.0;
        int targetW = 320;
        if (frame.cols() > targetW) {
            scale = (double) targetW / frame.cols();
            workFrame = new Mat();
            Imgproc.resize(frame, workFrame, new Size(targetW, frame.rows() * scale));
        } else {
            workFrame = frame; // 直接引用，不 new
        }

        Mat gray = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            // 转灰度
            Imgproc.cvtColor(workFrame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

            if (!hasPrev || prevFrame == null || prevFrame.empty()) {
                // 第一帧，保存参考帧
                prevFrame = gray.clone();
                hasPrev = true;
                return result;
            }

            // 帧差：当前帧 - 上一帧
            Core.absdiff(gray, prevFrame, mask);

            // 阈值二值化
            Imgproc.threshold(mask, mask, 25, 255, Imgproc.THRESH_BINARY);

            // 形态学膨胀连接运动区域
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.dilate(mask, mask, kernel);
            kernel.release();

            // 找轮廓
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            int id = 0;
            for (MatOfPoint cnt : contours) {
                double area = Imgproc.contourArea(cnt);
                // 过滤太小（光影噪点）和太大（非人体）的区域
                // 320px 宽度下，人员面积通常 > 800
                if (area < 800 || area > 80000) continue;

                // 获取边界矩形
                Rect r = Imgproc.boundingRect(cnt);

                // 人员至少 50px 高（320px 宽度下）
                if (r.height < 50) continue;

                // 过滤长宽比不合理区域（人员通常高 > 宽）
                float ratio = (float) r.height / Math.max(1, r.width);
                if (ratio < 0.8 || ratio > 5) continue;

                PlayerBox pb = new PlayerBox();
                // 转回原始坐标
                pb.rect = new Rect(
                        (int) (r.x / scale),
                        (int) (r.y / scale),
                        (int) (r.width / scale),
                        (int) (r.height / scale)
                );
                pb.confidence = Math.min(1.0, area / 10000.0);
                pb.id = id++;
                result.add(pb);

                // 每帧最多取 2 个人员（羽毛球场景通常 2 人）
                if (id >= 2) break;
            }

            // 更新参考帧
            prevFrame.release();
            prevFrame = gray.clone();

        } catch (Exception e) {
            Log.e(TAG, "detect failed", e);
        } finally {
            if (workFrame != frame) workFrame.release();
            gray.release();
            mask.release();
            hierarchy.release();
            for (MatOfPoint c : contours) c.release();
        }

        return result;
    }

    public void reset() {
        hasPrev = false;
        if (prevFrame != null) {
            prevFrame.release();
            prevFrame = null;
        }
    }
}
