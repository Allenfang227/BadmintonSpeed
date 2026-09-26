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

        Mat gray = new Mat();
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);

        // Step1: Canny 边缘检测
        Mat edges = new Mat();
        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
        Imgproc.Canny(gray, edges, 50, 150, 3);

        // Step2: 霍夫直线检测
        Mat lines = new Mat();
        Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180,
                80,    // 阈值
                60,    // 最短线段
                20);   // 间隙

        if (lines.empty()) return r;

        // Step3: 过滤直线 —— 场地线通常是横/竖方向，且较长
        List<double[]> hLines = new ArrayList<>(); // 水平
        List<double[]> vLines = new ArrayList<>(); // 垂直

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
            }
        }

        // 取最外侧的水平/垂直线作为场地边界
        Point top = pickBoundary(hLines, true, true);       // 最上水平线
        Point bottom = pickBoundary(hLines, true, false);    // 最下水平线
        Point left = pickBoundary(vLines, false, true);     // 最左垂直线
        Point right = pickBoundary(vLines, false, false);   // 最右垂直线

        if (top == null || bottom == null || left == null || right == null) {
            Log.i(TAG, "Cannot detect all 4 court boundaries");
            return r;
        }

        // 场地四角：由最左/最右垂直线 × 最上/最下水平线的交点得到
        Point tl = intersection(left, top);
        Point tr = intersection(right, top);
        Point bl = intersection(left, bottom);
        Point br = intersection(right, bottom);

        if (tl == null || tr == null || bl == null || br == null) return r;

        Point[] corners = new Point[]{tl, tr, br, bl}; // 按左上→右上→右下→左下顺序
        r.courtCorners = corners;

        // Step4: RANSAC + findHomography —— 图像坐标 → 标准场地坐标（米）
        // 标准单打场地四角（米）：左上 (0,0)，右上 (5.18,0)，右下 (5.18,13.4)，左下 (0,13.4)
        MatOfPoint2f src = new MatOfPoint2f(corners);
        Point[] dstPts = {new Point(0,0), new Point(5.18,0), new Point(5.18,13.4), new Point(0,13.4)};
        MatOfPoint2f dst = new MatOfPoint2f(dstPts);

        Mat H = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0);
        if (H == null || H.empty()) return r;

        r.homography = H;
        Mat Hinv = new Mat();
        Core.invert(H, Hinv);
        r.homographyInv = Hinv;
        r.pxPerMeter = 5.18 / Math.max(1, Math.hypot(tr.x - tl.x, tr.y - tl.y));

        r.ok = true;

        // Step5: 绘制叠加帧（黄色场地网格）
        r.overlayFrame = drawCourtOverlay(frame.clone(), H);
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

    private Point pickBoundary(List<double[]> lines, boolean horizontal, boolean min) {
        if (lines.isEmpty()) return null;
        Collections.sort(lines, (a, b) -> {
            double v1 = horizontal ? (a[1] + a[3]) / 2.0 : (a[0] + a[2]) / 2.0;
            double v2 = horizontal ? (b[1] + b[3]) / 2.0 : (b[0] + b[2]) / 2.0;
            return Double.compare(v1, v2);
        });
        return horizontal
                ? new Point(lines.get(min ? 0 : lines.size() - 1)[0],
                           (lines.get(min ? 0 : lines.size() - 1)[1] +
                            lines.get(min ? 0 : lines.size() - 1)[3]) / 2.0)
                : new Point((lines.get(min ? 0 : lines.size() - 1)[0] +
                             lines.get(min ? 0 : lines.size() - 1)[2]) / 2.0,
                           lines.get(min ? 0 : lines.size() - 1)[1]);
    }

    private Point intersection(Point p1, Point p2) {
        // 简化：取两条线段的平均中心点
        if (p1 == null || p2 == null) return null;
        return new Point((p1.x + p2.x) / 2, (p1.y + p2.y) / 2);
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
        try {
            Mat src = new Mat(1, 1, 6); // CV_64FC2
            src.put(0, 0, p.x, p.y);
            Mat dst = new Mat();
            Core.perspectiveTransform(src, dst, H);
            if (dst.empty()) return null;
            double[] v = dst.get(0, 0);
            if (v == null || v.length < 2) return null;
            return new Point(v[0], v[1]);
        } catch (Exception e) {
            Log.w(TAG, "warpPoint failed: " + e.getMessage());
            return null;
        }
    }
}
