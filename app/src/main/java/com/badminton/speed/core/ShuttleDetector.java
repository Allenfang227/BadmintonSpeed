package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractor;
import org.opencv.video.Video;
import org.opencv.videoio.VideoCapture;

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
        this.bs = Video.createBackgroundSubtractorMOG2(500, 25, true);
    }

    public void reset() {
        this.bs = Video.createBackgroundSubtractorMOG2(500, 25, true);
    }

    /**
     * 在单帧上检测羽毛球位置。
     * @return 羽毛球中心点（像素坐标），未检测到返回 null。
     */
    public Point detect(Mat frame) {
        if (frame == null || frame.empty()) return null;

        Mat mask = new Mat();
        bs.apply(frame, mask, 0.002);

        // 形态学开/闭运算去噪
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);

        // 找轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Point bestCenter = null;
        double bestScore = -1;

        for (MatOfPoint cnt : contours) {
            double area = Imgproc.contourArea(cnt);
            if (area < 10 || area > 500) continue; // 羽毛球面积通常 10~500 px

            MatOfPoint2f cnt2f = new MatOfPoint2f(cnt.toArray());
            Point2f center = new Point2f();
            float[] radius = new float[1];
            Imgproc.minEnclosingCircle(cnt2f, center, radius);

            // 圆形度检查
            double peri = Imgproc.arcLength(cnt2f, true);
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

        return bestCenter;
    }

    /**
     * 霍夫圆检测辅助（备选方案）。
     */
    public Point detectHough(Mat gray) {
        Mat circles = new Mat();
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1, 5, 200, 25, 2, 15);
        if (circles.empty()) return null;
        double[] c = circles.get(0, 0);
        if (c == null) return null;
        return new Point(c[0], c[1]);
    }

    /** Point2f 兼容类。 */
    public static class Point2f extends org.opencv.core.Point {
        public float radius;
        public Point2f() { super(0, 0); }
        public Point2f(double x, double y) { super(x, y); }
    }
}
