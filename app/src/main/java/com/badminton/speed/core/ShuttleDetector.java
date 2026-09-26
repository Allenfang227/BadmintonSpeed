package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 羽毛球检测：帧差法 + 轮廓 + 圆形度 + 白色过滤。
 *
 * 已移除 BackgroundSubtractorMOG2 —— 它会为每个像素累积多个高斯分布的 native 模型，
 * 1080p 视频下模型内存可达数百 MB，在默认 Java 堆下极易触发 native OOM/SIGSEGV 闪退
 * （无法被 Java try-catch 捕获）。帧差法只保留上一帧灰度图，内存占用极低且稳定。
 *
 * 羽毛球特点：白色/浅色、小圆形、高速运动 → 帧差后表现为小亮斑。
 */
public class ShuttleDetector {

    private static final String TAG = "ShuttleDetector";
    private Mat prevFrame = null;
    private boolean hasPrev = false;

    public ShuttleDetector() {
        // 无 native 资源，构造不会 OOM
    }

    /** 重置状态（新一轮检测前调用） */
    public void reset() {
        hasPrev = false;
        if (prevFrame != null) {
            prevFrame.release();
            prevFrame = null;
        }
    }

    /**
     * 在单帧上检测羽毛球位置。
     * 优化：缩放帧到 480px 宽度处理；帧差法不累积 native 模型。
     * @return 羽毛球中心点（原始像素坐标），未检测到返回 null。
     */
    public Point detect(Mat frame) {
        if (frame == null || frame.empty()) return null;

        // 缩放帧以加速（避免 else 分支泄漏 new Mat()）
        Mat workFrame;
        double scale = 1.0;
        int targetW = 480;
        if (frame.cols() > targetW) {
            scale = (double) targetW / frame.cols();
            workFrame = new Mat();
            Imgproc.resize(frame, workFrame, new Size(targetW, frame.rows() * scale));
        } else {
            workFrame = frame;
        }

        Mat gray = new Mat();
        Mat mask = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        Point bestCenter = null;

        try {
            Imgproc.cvtColor(workFrame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

            // 第一帧：只保存参考帧
            if (!hasPrev || prevFrame == null || prevFrame.empty()) {
                prevFrame = gray.clone();
                hasPrev = true;
                return null;
            }

            // 帧差：当前帧 - 上一帧
            Core.absdiff(gray, prevFrame, mask);
            // 羽毛球小且亮，阈值低一些避免漏检
            Imgproc.threshold(mask, mask, 20, 255, Imgproc.THRESH_BINARY);

            // 形态学开运算去噪 + 膨胀连接
            Mat kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kOpen);
            kOpen.release();
            Mat kDil = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(2, 2));
            Imgproc.dilate(mask, mask, kDil);
            kDil.release();

            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double bestScore = -1;
            for (MatOfPoint cnt : contours) {
                double area = Imgproc.contourArea(cnt);
                // 羽毛球在缩放图上面积很小
                if (area < 3 || area > 300) continue;

                MatOfPoint2f cnt2f = new MatOfPoint2f(cnt.toArray());
                try {
                    Point center = new Point(0, 0);
                    float[] radius = new float[1];
                    Imgproc.minEnclosingCircle(cnt2f, center, radius);

                    double peri = Imgproc.arcLength(cnt2f, true);
                    if (peri < 0.1) continue;
                    double circularity = 4 * Math.PI * area / (peri * peri);
                    if (circularity < 0.3) continue;

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
                        bestCenter = new Point(center.x / scale, center.y / scale);
                    }
                } finally {
                    cnt2f.release();
                }
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

        return bestCenter;
    }

    /**
     * 备选：纯霍夫圆检测，不依赖帧差（静态场景下使用）。
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
