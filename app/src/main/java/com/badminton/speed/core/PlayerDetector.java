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
 * 人员检测：运行均值背景建模（不含 native 高斯模型，内存极低，不会 OOM 闪退）。
 * 原理：背景 = 帧的指数滑动平均；当前帧 - 背景 = 运动前景 → 整身轮廓框。
 * 对比帧间差分：可得到完整人体块（含静止时的身体），且对噪声更鲁棒。
 */
public class PlayerDetector {

    private static final String TAG = "PlayerDetector";
    private Mat bgModel = null;       // 运行均值背景
    private boolean hasBg = false;

    public static class PlayerBox {
        public Rect rect;
        public double confidence;
        public int id;
    }

    public PlayerDetector() {
        // 无 native 资源，构造不会 OOM
    }

    /**
     * 用背景差分检测运动人员（整身框）。
     * @param frame 当前帧（BGR）
     * @return 检测到的人员列表（最多 4 人）
     */
    public List<PlayerBox> detect(Mat frame) {
        List<PlayerBox> result = new ArrayList<>();
        if (frame == null || frame.empty()) return result;

        // 缩放到 320px 宽度加速
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
            // 转灰度 + 轻微模糊去噪
            Imgproc.cvtColor(workFrame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

            if (!hasBg || bgModel == null || bgModel.empty()) {
                // 首帧作为背景
                bgModel = gray.clone();
                hasBg = true;
                return result;
            }

            // 背景慢速更新（alpha=3%），再差分 → 运动前景为整个身体
            Core.addWeighted(bgModel, 0.97, gray, 0.03, 0, bgModel);
            Core.absdiff(gray, bgModel, mask);

            // 阈值二值化
            Imgproc.threshold(mask, mask, 30, 255, Imgproc.THRESH_BINARY);

            // 形态学膨胀连接身体部位
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(9, 9));
            Imgproc.dilate(mask, mask, kernel);
            kernel.release();

            // 找轮廓
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            int id = 0;
            for (MatOfPoint cnt : contours) {
                double area = Imgproc.contourArea(cnt);
                // 过滤光影噪点和超大区域（320px 宽度下整身面积通常 800~60000）
                if (area < 600 || area > 60000) continue;

                Rect r = Imgproc.boundingRect(cnt);

                // 整身高度至少 40px
                if (r.height < 40 || r.width < 25) continue;

                // 长宽比过滤（人体竖长，宽高比 0.2~1.6）
                float ratio = (float) r.height / Math.max(1, r.width);
                if (ratio < 0.6 || ratio > 5) continue;

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

                // 羽毛球对局场景最多 4 人（双打）
                if (id >= 4) break;
            }

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
        hasBg = false;
        if (bgModel != null) {
            bgModel.release();
            bgModel = null;
        }
    }
}
