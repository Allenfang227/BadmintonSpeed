package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractor;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * 羽毛球检测：背景差分（MOG2）+ 形态学 + 轮廓 + 霍夫圆。
 * 羽毛球特点：白色/浅色，小圆形/椭圆形，高速运动。
 */
public class ShuttleDetector {

    private static final String TAG = "ShuttleDetector";
    private BackgroundSubtractor bs;

    public ShuttleDetector() {
        try {
            this.bs = Video.createBackgroundSubtractorMOG2(500, 25, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create BackgroundSubtractorMOG2", e);
        }
    }

    public void reset() {
        try {
            this.bs = Video.createBackgroundSubtractorMOG2(500, 25, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset BackgroundSubtractorMOG2", e);
        }
    }

    /**
     * 在单帧上检测羽毛球位置。
     * 优化：缩放帧到 480px 宽度处理，速度快 3-4 倍。
     * @return 羽毛球中心点（原始像素坐标），未检测到返回 null。
     */
    public Point detect(Mat frame) {
        if (frame == null || frame.empty()) return null;
        if (bs == null) return detectFallback(frame);

        // 缩放帧以加速处理
        Mat workFrame = new Mat();
        double scale = 1.0;
        int targetW = 480;
        if (frame.cols() > targetW) {
            scale = (double) targetW / frame.cols();
            Imgproc.resize(frame, workFrame, new Size(targetW, frame.rows() * scale));
        } else {
            workFrame = frame; // 不需要缩放，直接用
        }

        Mat mask = new Mat();
        Mat kernel = null;
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Point bestCenter = null;

        try {
            bs.apply(workFrame, mask, 0.005);

            // 只做一次形态学开运算（去掉开+闭，节省一半时间）
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);

            // 找轮廓
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double bestScore = -1;

            for (MatOfPoint cnt : contours) {
                double area = Imgproc.contourArea(cnt);
                if (area < 5 || area > 300) continue;

                MatOfPoint2f cnt2f = new MatOfPoint2f(cnt.toArray());
                Point center = new Point(0, 0);
                float[] radius = new float[1];
                try {
                    Imgproc.minEnclosingCircle(cnt2f, center, radius);
                } catch (Exception e) {
                    cnt2f.release();
                    continue;
                }

                // 圆形度检查
                double peri = Imgproc.arcLength(cnt2f, true);
                cnt2f.release();
                if (peri < 0.1) continue;
                double circularity = 4 * Math.PI * area / (peri * peri);
                if (circularity < 0.35) continue;

                // 白色检查：在缩放图上采样
                int cx = (int) center.x, cy = (int) center.y;
                if (cx < 0 || cy < 0 || cx >= workFrame.cols() || cy >= workFrame.rows()) continue;
                double[] bgr = workFrame.get(cy, cx);
                if (bgr == null) continue;
                double brightness = (bgr[0] + bgr[1] + bgr[2]) / 3.0;
                if (brightness < 100) continue; // 羽毛球偏白

                double score = circularity * brightness * area;
                if (score > bestScore) {
                    bestScore = score;
                    // 转换回原始坐标
                    bestCenter = new Point(center.x / scale, center.y / scale);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "detect failed", e);
        } finally {
            if (workFrame != frame) workFrame.release();
            mask.release();
            if (kernel != null) kernel.release();
            hierarchy.release();
            for (MatOfPoint c : contours) c.release();
        }

        return bestCenter;
    }

    /**
     * 备选方案：用霍夫圆检测，不依赖背景差分。
     */
    private Point detectFallback(Mat frame) {
        Mat gray = new Mat();
        Mat circles = new Mat();
        try {
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
            Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1, 5, 200, 25, 2, 15);
            if (circles.empty()) return null;
            double[] c = circles.get(0, 0);
            if (c == null) return null;
            return new Point(c[0], c[1]);
        } catch (Exception e) {
            Log.e(TAG, "detectFallback failed", e);
            return null;
        } finally {
            gray.release();
            circles.release();
        }
    }

    /**
     * 霍夫圆检测辅助（备选方案）。
     */
    public Point detectHough(Mat gray) {
        Mat circles = new Mat();
        try {
            Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1, 5, 200, 25, 2, 15);
            if (circles.empty()) return null;
            double[] c = circles.get(0, 0);
            if (c == null) return null;
            return new Point(c[0], c[1]);
        } catch (Exception e) {
            Log.e(TAG, "detectHough failed", e);
            return null;
        } finally {
            circles.release();
        }
    }
}
