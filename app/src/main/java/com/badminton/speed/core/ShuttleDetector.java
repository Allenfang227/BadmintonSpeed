package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 羽毛球检测（v2.46 优化版）：
 *   帧差法 + 轮廓 + 圆形度 + 白色过滤 作为基础召回；
 *   新增「跟踪状态」提升精度：
 *     - ROI 提示搜索：有上一帧位置时，先在预测点附近 80px 窗口内用更低阈值复检，
 *       大幅降低高速运动下的漏检与误检（背景里同色小亮斑很难再得高分）；
 *     - 尺寸先验：羽毛球在 480px 工作分辨率下面积约 3~60px，
 *       评分对「过大轮廓」（人体肢端/光影）施加惩罚，解决原评分被大面积轮廓带偏的问题；
 *     - 速度预测与短时容错：连续 3 帧未检出才清空跟踪，
 *       期间继续按预测位置给提示，保证轨迹连续（测速不被缺口打断）。
 *
 * 内存纪律：全程只保留上一帧灰度图，无 native 累积模型（不可重入 MOG2，防 OOM）。
 */
public class ShuttleDetector {

    private static final String TAG = "ShuttleDetector";

    private Mat prevFrame = null;      // 上一帧灰度（工作分辨率）
    private boolean hasPrev = false;

    // ==== 跟踪状态（工作分辨率坐标）====
    private final Deque<Point> recent = new ArrayDeque<>(); // 最近检测点（最多 4 个）
    private Point hint = null;         // 预测提示位置
    private double velX = 0, velY = 0; // 估计速度（px/检测帧）
    private int missCount = 0;
    private static final int MAX_MISS = 3;      // 连续丢帧容忍
    private static final int ROI_HALF = 40;     // ROI 半径（px，工作分辨率）
    private static final double PROXIMITY_PX = 60; // 全帧模式下视为"靠近提示"的距离

    public ShuttleDetector() {
        // 无 native 资源，构造不会 OOM
    }

    /** 重置状态（新一轮检测前调用） */
    public void reset() {
        hasPrev = false;
        missCount = 0;
        hint = null;
        velX = velY = 0;
        recent.clear();
        if (prevFrame != null) {
            prevFrame.release();
            prevFrame = null;
        }
    }

    /**
     * 在单帧上检测羽毛球位置。
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

            // 帧差：当前帧 - 上一帧（羽毛球小且亮，表现为小亮斑）
            Core.absdiff(gray, prevFrame, mask);

            // 主检测：常规阈值 + 形态学
            Point found = searchMask(mask, workFrame, 20, 0.3, false);

            // 复检：有跟踪提示但主检测没跟上时，在预测点附近 ROI 内降低阈值再找一次
            if (found == null && hint != null && missCount < MAX_MISS) {
                found = searchMask(mask, workFrame, 12, 0.22, true);
            }

            bestCenter = found;

            // 更新参考帧
            prevFrame.release();
            prevFrame = gray.clone();

        } catch (Exception e) {
            Log.e(TAG, "detect failed", e);
        } finally {
            if (workFrame != frame) workFrame.release();
            gray.release();
            mask.release();
        }

        // 更新跟踪状态（工作坐标 → 原始坐标仅用于返回）
        if (bestCenter != null) {
            onDetected(bestCenter);
            return new Point(bestCenter.x / scale, bestCenter.y / scale);
        }
        onMissed();
        return null;
    }

    /**
     * 在帧差掩码上搜索羽毛球候选。
     *
     * @param threshold   二值化阈值
     * @param minCirc     最小圆形度
     * @param roiOnly     true=只在提示点附近 ROI 内搜索（需 hint != null）
     * @return 工作分辨率下的中心点，未找到返回 null
     */
    private Point searchMask(Mat diffSrc, Mat workFrame, double threshold,
                             double minCirc, boolean roiOnly) {
        Mat mask = new Mat();
        try {
            Imgproc.threshold(diffSrc, mask, threshold, 255, Imgproc.THRESH_BINARY);

            // 形态学开运算去噪 + 轻微膨胀连接
            Mat kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kOpen);
            kOpen.release();
            Mat kDil = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(2, 2));
            Imgproc.dilate(mask, mask, kDil);
            kDil.release();

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            try {
                if (roiOnly && hint != null) {
                    int x0 = (int) Math.max(0, hint.x - ROI_HALF);
                    int y0 = (int) Math.max(0, hint.y - ROI_HALF);
                    int x1 = (int) Math.min(mask.cols(), hint.x + ROI_HALF);
                    int y1 = (int) Math.min(mask.rows(), hint.y + ROI_HALF);
                    if (x1 - x0 < 4 || y1 - y0 < 4) return null;
                    Mat roi = new Mat(mask, new Rect(x0, y0, x1 - x0, y1 - y0));
                    Imgproc.findContours(roi, contours, hierarchy,
                            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                    roi.release();
                    return pickBest(contours, workFrame, minCirc, x0, y0, roiOnly);
                }
                Imgproc.findContours(mask, contours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                return pickBest(contours, workFrame, minCirc, 0, 0, roiOnly);
            } finally {
                hierarchy.release();
                for (MatOfPoint c : contours) c.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "searchMask failed: " + e.getMessage());
            return null;
        } finally {
            mask.release();
        }
    }

    /**
     * 从轮廓中挑最佳羽毛球候选。
     *
     * 评分（v2.46）：circularity² × brightness × 尺寸先验 × 接近提示加成
     * - 圆形度平方：更严格偏向类圆斑点，排除长条光影/肢端；
     * - 尺寸先验：峰值在 area≈25px（480p 下典型羽毛球），过大轮廓被惩罚；
     * - 接近提示加成：靠近跟踪预测点的候选显著加权（ROI 模式天然满足）。
     */
    private Point pickBest(List<MatOfPoint> contours, Mat workFrame,
                           double minCirc, int offX, int offY, boolean roiMode) {
        double bestScore = -1;
        Point best = null;

        for (MatOfPoint cnt : contours) {
            double area = Imgproc.contourArea(cnt);
            // 480px 工作图：羽毛球面积 3~60，>120 基本是人体/器械，直接排除
            if (area < 3 || area > 120) continue;

            MatOfPoint2f cnt2f = new MatOfPoint2f(cnt.toArray());
            try {
                Point center = new Point(0, 0);
                float[] radius = new float[1];
                Imgproc.minEnclosingCircle(cnt2f, center, radius);

                double peri = Imgproc.arcLength(cnt2f, true);
                if (peri < 0.1) continue;
                double circularity = 4 * Math.PI * area / (peri * peri);
                if (circularity < minCirc) continue;

                int cx = (int) center.x, cy = (int) center.y;
                if (cx < 0 || cy < 0 || cx >= workFrame.cols() || cy >= workFrame.rows()) continue;
                double[] bgr = workFrame.get(cy, cx);
                if (bgr == null) continue;
                double brightness = (bgr[0] + bgr[1] + bgr[2]) / 3.0;
                if (brightness < 100) continue; // 羽毛球偏白

                Point cand = new Point(cx + offX, cy + offY);

                // 尺寸先验：理想面积 25px，偏离则衰减（0,1]
                double sizePrior = 1.0 / (1.0 + Math.abs(area - 25.0) / 25.0);

                // 接近提示加成
                double prox = 1.0;
                if (hint != null) {
                    double d = Math.hypot(cand.x - hint.x, cand.y - hint.y);
                    if (d < PROXIMITY_PX) prox = 2.5;
                }

                double score = circularity * circularity * brightness * sizePrior * prox;
                if (score > bestScore) {
                    bestScore = score;
                    best = cand;
                }
            } finally {
                cnt2f.release();
            }
        }
        return best;
    }

    /** 检测成功：更新轨迹窗口、速度估计与预测提示 */
    private void onDetected(Point p) {
        if (recent.size() >= 4) recent.pollLast();
        recent.addFirst(p);
        if (recent.size() >= 2) {
            Point a = recent.getFirst();   // 最新（ArrayDeque getFirst 可用）
            // 取前一个点算速度（用数组避免重复迭代）
            Point[] arr = recent.toArray(new Point[0]);
            Point b = arr.length >= 2 ? arr[1] : null;
            if (b != null) {
                double nvx = a.x - b.x, nvy = a.y - b.y;
                // 低通滤波，抑制单帧跳变
                velX = 0.6 * velX + 0.4 * nvx;
                velY = 0.6 * velY + 0.4 * nvy;
            }
        }
        // 预测提示 = 当前位置 + 估计速度（限制幅度，防止离球场后预测飞走）
        double step = 1.0;
        double px = p.x + velX * step, py = p.y + velY * step;
        double maxStep = 60;
        if (Math.hypot(px - p.x, py - p.y) > maxStep) {
            double k = maxStep / Math.hypot(velX, velY);
            px = p.x + velX * k;
            py = p.y + velY * k;
        }
        hint = new Point(px, py);
        missCount = 0;
    }

    /** 检测失败：短时保留提示（容错），超时清空跟踪 */
    private void onMissed() {
        missCount++;
        if (missCount > MAX_MISS) {
            recent.clear();
            hint = null;
            velX = velY = 0;
        }
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
