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
     * @return 羽毛球中心点（像素坐标），未检测到返回 null。
     */
    public Point detect(Mat frame) {
        if (frame == null || frame.empty()) return null;
        if (bs == null) return detectFallback(frame);

        Mat mask = new Mat();
        Mat kernel = null;
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Point bestCenter = null;

        try {
            bs.apply(frame, mask, 0.002);

            // 形态学开/闭运算去噪
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);

            // 找轮廓
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double bestScore = -1;

            for (MatOfPoint cnt : contours) {
                double area = Imgproc.contourArea(cnt);
                if (area < 10 || area > 500) continue;

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
                if (circularity < 0.4) continue;

                // 白色检查：在该位置采样原图像素
                int cx = (int) center.x, cy = (int) center.y;
                if (cx < 0 || cy < 0 || cx >= frame.cols() || cy >= frame.rows()) continue;
                double[] bgr = frame.get(cy, cx);
                if (bgr == null) continue;
                double b = bgr[0], g = bgr[1], r = bgr[2];
                double brightness = (b + g + r) / 3.0;

                // 羽毛球通常是白色/浅色，亮度偏高
                double score = circularity * brightness * area;
                if (score > bestScore) {
                    bestScore = score;
                    bestCenter = new Point(center.x, center.y);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "detect failed", e);
        } finally {
            // 释放所有 Mat，防止内存泄漏
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
