package com.badminton.speed.core;
import org.opencv.core.MatOfKeyPoint;

import android.util.Log;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.Features2d;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 场地基准检测：
 *   Canny 边缘 → 霍夫直线 → 过滤出场地线 → RANSAC 拟合场地四边形 → findHomography 单应矩阵。
 *
 * 标准羽毛球场单打尺寸：13.4m × 5.18m；双打：13.4m × 6.1m。
 * 场地内部白线是测速的空间基准。
 */
public class CourtDetector {

    private static final String TAG = "CourtDetector";

    /** 输出：场地单应矩阵（图像 → 标准场地坐标），场地 4 个角点，以及绘制好的叠加帧。 */
    public static class CourtResult {
        public boolean ok = false;
        public Mat homography;                  // 3x3 单应矩阵
        public Mat homographyInv;               // 逆矩阵
        public Point[] courtCorners;            // 场地四角（图像坐标）
        public double pxPerMeter;               // 像素/米 比例（用于粗略速度计算）
        public Mat overlayFrame;                // 绘制了黄色场地网格的帧
    }

    /**
     * 在一帧上检测场地。
     */
    public CourtResult detect(Mat frame) {
        CourtResult r = new CourtResult();
        if (frame == null || frame.empty()) return r;

        // 缩放帧加速处理
        Mat workFrame = new Mat();
        double scale = 1.0;
        int targetW = 480;
        if (frame.cols() > targetW) {
            scale = (double) targetW / frame.cols();
            Imgproc.resize(frame, workFrame, new Size(targetW, frame.rows() * scale));
        } else {
            workFrame = frame; // 直接用
        }

        Mat gray = new Mat();
        Mat edges = new Mat();
        Mat lines = new Mat();
        Mat hsv = new Mat();
        Mat whiteMask = new Mat();

        try {
            // Step0: 白色线过滤——只保留场地白线，避免识别绿色/蓝色地面
            Imgproc.cvtColor(workFrame, hsv, Imgproc.COLOR_BGR2HSV);
            // 白色：放宽阈值，场地线可能偏黄/灰
            Core.inRange(hsv, new Scalar(0, 0, 120), new Scalar(180, 80, 255), whiteMask);
            // 形态学开运算去噪点
            Mat k1 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(whiteMask, whiteMask, Imgproc.MORPH_OPEN, k1);
            k1.release();

            Imgproc.cvtColor(workFrame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
            Imgproc.Canny(gray, edges, 50, 150, 3);
            // 与白色掩码相与，只保留白线上的边缘
            Core.bitwise_and(edges, whiteMask, edges);

            // Step2: 霍夫直线检测
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180,
                    60, 80, 15);

            // 回退机制：白色过滤检测不到线，就回退到不过滤的全图边缘检测
            if (lines.empty() || lines.rows() < 4) {
                Log.i(TAG, "White mask found " + (lines.empty() ? 0 : lines.rows()) + " lines, fallback to full edge");
                Imgproc.Canny(gray, edges, 50, 150, 3);
                Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180,
                        60, 60, 20);
            }

            if (lines.empty()) return r;

            // Step2.5: 按方向分类直线
            List<double[]> hLines = new ArrayList<>();
            List<double[]> vLines = new ArrayList<>();

            for (int i = 0; i < lines.rows(); i++) {
                double[] d = lines.get(i, 0);
                if (d == null || d.length < 4) continue;
                double x1 = d[0], y1 = d[1], x2 = d[2], y2 = d[3];
                double angle = Math.abs(Math.atan2(y2 - y1, x2 - x1) * 180 / Math.PI);
                double len = Math.hypot(x2 - x1, y2 - y1);
                if (len < 60) continue;
                if (angle < 30 || angle > 150) {
                    hLines.add(d);
                } else if (angle > 60 && angle < 120) {
                    vLines.add(d);
                } else {
                    continue; // 斜线丢弃，避免噪声污染
                }
            }

            // Step3: 取 4 条边界完整线段（真实直线，非取点）
            double[] topSeg = pickBoundary(hLines, true, true);
            double[] bottomSeg = pickBoundary(hLines, true, false);
            double[] leftSeg = pickBoundary(vLines, false, true);
            double[] rightSeg = pickBoundary(vLines, false, false);

            Point tl = lineIntersection(leftSeg, topSeg);
            Point tr = lineIntersection(rightSeg, topSeg);
            Point bl = lineIntersection(leftSeg, bottomSeg);
            Point br = lineIntersection(rightSeg, bottomSeg);

            if (tl == null || tr == null || bl == null || br == null) {
                Log.i(TAG, "Court corner intersection failed");
                return r;
            }

            // 转换回原始坐标
            Point[] corners = new Point[]{
                    new Point(tl.x / scale, tl.y / scale),
                    new Point(tr.x / scale, tr.y / scale),
                    new Point(br.x / scale, br.y / scale),
                    new Point(bl.x / scale, bl.y / scale)
            };
            r.courtCorners = corners;

            // 合理性校验：角点必须在画面内附近，场地面积占比合理
            double maxDim = Math.max(workFrame.cols(), workFrame.rows());
            double courtW = Math.hypot(tr.x - tl.x, tr.y - tl.y);
            double courtH = Math.hypot(bl.x - tl.x, bl.y - tl.y);
            double courtArea = courtW * courtH;
            double frameArea = workFrame.cols() * workFrame.rows();
            double areaRatio = courtArea / frameArea;
            double aspect = courtH / Math.max(1, courtW);
            if (areaRatio < 0.015 || areaRatio > 0.98 || aspect < 0.4 || aspect > 7.0) {
                Log.w(TAG, "Court rejected: areaRatio=" + areaRatio + " aspect=" + aspect);
                return r;
            }
            for (Point c : corners) {
                if (c.x < -maxDim || c.y < -maxDim || c.x > maxDim * 2 || c.y > maxDim * 2) {
                    Log.w(TAG, "Corner out of range: " + c);
                    return r;
                }
            }

            // Step4: 4 点对应 → 直接解单应矩阵（确定性，不依赖 RANSAC 随机采样）
            MatOfPoint2f src = new MatOfPoint2f(corners);
            Point[] dstPts = {new Point(0,0), new Point(5.18,0), new Point(5.18,13.4), new Point(0,13.4)};
            MatOfPoint2f dst = new MatOfPoint2f(dstPts);
            Mat H;
            try {
                H = Imgproc.getPerspectiveTransform(src, dst);
            } catch (Exception e) {
                Log.e(TAG, "getPerspectiveTransform failed: " + e.getMessage());
                src.release(); dst.release();
                return r;
            }
            src.release(); dst.release();
            if (H == null || H.empty() || H.rows() != 3 || H.cols() != 3) {
                return r;
            }

            r.homography = H;
            Mat Hinv = new Mat();
            Core.invert(H, Hinv);
            if (Hinv.empty()) { H.release(); return r; }
            r.homographyInv = Hinv;
            r.pxPerMeter = 5.18 / Math.max(1, courtW);

            // 不 clone 全尺寸 overlay 帧（每帧 clone 大 Mat 是 native OOM 闪退源），
            // 黄色场地线由 OverlayView 用 corners + H 实时绘制。
            r.overlayFrame = null;
            r.ok = true;

        } catch (Exception e) {
            Log.e(TAG, "detect failed", e);
        } finally {
            if (workFrame != frame) workFrame.release();
            gray.release();
            edges.release();
            lines.release();
            hsv.release();
            whiteMask.release();
        }
        return r;
    }

    /**
     * 用 RANSAC 匹配多个 ORB 关键点，更鲁棒的做法（可选，当前主要用霍夫直线法）。
     */
    public CourtResult detectORB(Mat frame, Mat referenceCourt) {
        CourtResult r = new CourtResult();
        ORB orb = ORB.create(2000);
        MatOfKeyPoint kp1 = new MatOfKeyPoint();
        Mat desc1 = new Mat();
        MatOfKeyPoint kp2 = new MatOfKeyPoint();
        Mat desc2 = new Mat();

        orb.detectAndCompute(frame, new Mat(), kp1, desc1);
        orb.detectAndCompute(referenceCourt, new Mat(), kp2, desc2);

        if (desc1.empty() || desc2.empty()) return r;

        DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
        MatOfDMatch matches = new MatOfDMatch();
        matcher.match(desc1, desc2, matches);

        List<DMatch> list = matches.toList();
        Collections.sort(list, Comparator.comparingDouble(m -> m.distance));
        List<DMatch> good = list.subList(0, Math.min(100, list.size()));

        Point[] pts1 = new Point[good.size()];
        Point[] pts2 = new Point[good.size()];
        for (int i = 0; i < good.size(); i++) {
            pts1[i] = kp1.toList().get(good.get(i).queryIdx).pt;
            pts2[i] = kp2.toList().get(good.get(i).trainIdx).pt;
        }
        MatOfPoint2f src = new MatOfPoint2f(pts1);
        MatOfPoint2f dst = new MatOfPoint2f(pts2);
        Mat H = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 5.0);
        if (H == null || H.empty()) return r;
        r.homography = H;
        r.ok = true;
        return r;
    }

    // === 辅助 ===

    /** 返回完整线段 {x1,y1,x2,y2}，按极值坐标筛选（min=true 取最小坐标端）。 */
    private double[] pickBoundary(List<double[]> lines, boolean horizontal, boolean min) {
        if (lines.isEmpty()) return null;
        double[] best = lines.get(0);
        double bestV = horizontal ? (best[1] + best[3]) / 2.0 : (best[0] + best[2]) / 2.0;
        for (int i = 1; i < lines.size(); i++) {
            double[] d = lines.get(i);
            double v = horizontal ? (d[1] + d[3]) / 2.0 : (d[0] + d[2]) / 2.0;
            if (min ? v < bestV : v > bestV) { bestV = v; best = d; }
        }
        return best;
    }

    /** 两条无限直线的交点（真实几何求交，非取中点）。返回 null 表示平行。 */
    private Point lineIntersection(double[] l1, double[] l2) {
        if (l1 == null || l2 == null) return null;
        double x1 = l1[0], y1 = l1[1], x2 = l1[2], y2 = l1[3];
        double x3 = l2[0], y3 = l2[1], x4 = l2[2], y4 = l2[3];
        double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(d) < 1e-6) return null;
        double px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / d;
        double py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / d;
        if (Double.isNaN(px) || Double.isNaN(py) || Double.isInfinite(px) || Double.isInfinite(py)) return null;
        return new Point(px, py);
    }

    /**
     * 把标准场地坐标网格（米）通过逆单应矩阵投到图像上，用黄色绘制。
     */
    private Mat drawCourtOverlay(Mat frame, Mat H) {
        if (H == null || H.empty() || H.rows() != 3 || H.cols() != 3) return frame;
        Mat Hinv = new Mat();
        Core.invert(H, Hinv);
        if (Hinv.empty() || Hinv.rows() != 3 || Hinv.cols() != 3) return frame;
        Mat out = frame.clone();

        // 网格：横向 1m 一格（约 6 格），纵向 1m 一格（约 14 格）
        for (double y = 0; y <= 13.4; y += 1.0) {
            Point a = warpPoint(Hinv, new Point(0, y));
            Point b = warpPoint(Hinv, new Point(5.18, y));
            if (a != null && b != null)
                Imgproc.line(out, a, b, new org.opencv.core.Scalar(0, 255, 255), 2); // 黄色
        }
        for (double x = 0; x <= 5.18; x += 1.0) {
            Point a = warpPoint(Hinv, new Point(x, 0));
            Point b = warpPoint(Hinv, new Point(x, 13.4));
            if (a != null && b != null)
                Imgproc.line(out, a, b, new org.opencv.core.Scalar(0, 255, 255), 2);
        }
        // 外框加粗
        drawCourtBorder(out, Hinv);
        return out;
    }

    private void drawCourtBorder(Mat frame, Mat Hinv) {
        Point tl = warpPoint(Hinv, new Point(0, 0));
        Point tr = warpPoint(Hinv, new Point(5.18, 0));
        Point br = warpPoint(Hinv, new Point(5.18, 13.4));
        Point bl = warpPoint(Hinv, new Point(0, 13.4));
        if (tl == null || tr == null || br == null || bl == null) return;
        Imgproc.line(frame, tl, tr, new org.opencv.core.Scalar(0, 255, 255), 3);
        Imgproc.line(frame, tr, br, new org.opencv.core.Scalar(0, 255, 255), 3);
        Imgproc.line(frame, br, bl, new org.opencv.core.Scalar(0, 255, 255), 3);
        Imgproc.line(frame, bl, tl, new org.opencv.core.Scalar(0, 255, 255), 3);
    }

    private Point warpPoint(Mat H, Point p) {
        if (H == null || H.empty() || H.rows() != 3 || H.cols() != 3) return null;
        Mat src = null;
        Mat dst = null;
        try {
            src = new Mat(1, 1, 6); // CV_64FC2
            src.put(0, 0, p.x, p.y);
            dst = new Mat();
            Core.perspectiveTransform(src, dst, H);
            if (dst.empty()) return null;
            double[] v = dst.get(0, 0);
            if (v == null || v.length < 2) return null;
            return new Point(v[0], v[1]);
        } catch (Exception e) {
            Log.w(TAG, "warpPoint failed: " + e.getMessage());
            return null;
        } finally {
            if (src != null) src.release();
            if (dst != null) dst.release();
        }
    }
}
