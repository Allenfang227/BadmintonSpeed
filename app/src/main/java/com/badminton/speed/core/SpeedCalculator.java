package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 球速计算 + 3D 轨迹重建：
 *   1) 连续帧追踪羽毛球 → 每帧像素位置序列
 *   2) 单应矩阵把图像坐标映射到标准场地坐标（米）
 *   3) 用 30 FPS 视频，每帧时间 = 1/30 秒
 *   4) 速度 = 位移 / 时间 → m/s → km/h
 *   5) 3D 重建：如果已知场地是平的（z≈0 近似），用单应矩阵足够；
 *      更精确可结合 solvePnP 估计相机位姿后做 3D 三角化。
 *   6) 界内界外判定：羽毛球场地白线上（由单应矩阵的已知边界）
 */
public class SpeedCalculator {

    private static final String TAG = "SpeedCalculator";

    /**
     * 计算球速（含移动平均平滑，参考文档 SpeedCalculator）。
     * @param trajectoryPx 每步羽毛球像素坐标（要有至少 2 个点）
     * @param fps 视频帧率
     * @param frameStep 相邻轨迹点间隔的帧数（如每 4 帧采 1 点则传 4）
     * @param courtH 场地单应矩阵（图像 → 标准场地坐标，单位：米），可为 null
     * @return 每步速度列表 km/h（已平滑）
     */
    public List<Double> computeSpeed(List<Point> trajectoryPx, int fps, int frameStep, Mat courtH) {
        List<Double> raw = new ArrayList<>();
        if (trajectoryPx == null || trajectoryPx.size() < 2 || fps <= 0) return raw;
        if (frameStep < 1) frameStep = 1;

        double dt = (double) frameStep / fps; // 相邻轨迹点的真实时间间隔（秒）
        Point prevMeters = null;

        for (int i = 0; i < trajectoryPx.size(); i++) {
            Point px = trajectoryPx.get(i);
            if (px == null) { raw.add(0.0); prevMeters = null; continue; }
            Point meters = courtH != null ? warpPoint(courtH, px) : null;

            if (meters == null && courtH == null) {
                // 退化：用像素速度，仅做相对比较
                if (prevMeters != null) {
                    double distPx = Math.hypot(px.x - prevMeters.x, px.y - prevMeters.y);
                    raw.add(distPx / dt / 3.6); // px/s 近似
                } else {
                    raw.add(0.0);
                }
            } else if (meters != null && prevMeters != null) {
                double distM = Math.hypot(meters.x - prevMeters.x, meters.y - prevMeters.y);
                double mPerSec = distM / dt;
                raw.add(mPerSec * 3.6);
            } else {
                raw.add(0.0);
            }
            prevMeters = (meters != null) ? meters : px;
        }

        // 移动平均平滑（窗口 5），减少抖动
        return movingAverage(raw, 5);
    }

    /** 移动平均平滑，保留原长度（两端用可用窗口）。 */
    private List<Double> movingAverage(List<Double> raw, int win) {
        List<Double> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            double sum = 0; int n = 0;
            for (int j = Math.max(0, i - win / 2); j <= Math.min(raw.size() - 1, i + win / 2); j++) {
                sum += raw.get(j); n++;
            }
            out.add(n > 0 ? sum / n : 0.0);
        }
        return out;
    }

    /**
     * 统计结果：最高 / 平均 / 击球类型 / 界内界外。
     */
    public Summary summarize(List<Double> speeds, Point finalShuttlePos, Mat courtH) {
        Summary s = new Summary();
        if (speeds == null || speeds.isEmpty()) return s;

        double max = 0, sum = 0, count = 0;
        for (Double v : speeds) {
            if (v > max) max = v;
            if (v > 5) { // 过滤噪声
                sum += v;
                count++;
            }
        }
        s.maxSpeed = max;
        s.avgSpeed = count > 0 ? sum / count : 0;
        s.hitType = HitDetector.classifyHitType(max);

        // 界内界外判定
        s.inOut = judgeInOut(finalShuttlePos, courtH);
        return s;
    }

    public static class Summary {
        public double maxSpeed;
        public double avgSpeed;
        public String hitType;
        public String inOut;
    }

    /**
     * 判断球落点（或当前位置）是在界内还是界外。
     * 标准双打场地：x ∈ [0, 6.10]，y ∈ [0, 13.40]（米）
     */
    public String judgeInOut(Point shuttlePx, Mat courtH) {
        if (shuttlePx == null || courtH == null) return "未知";
        Point m = warpPoint(courtH, shuttlePx);
        if (m == null) return "未知";
        if (m.x >= -0.1 && m.x <= 6.10 + 0.1 && m.y >= -0.1 && m.y <= 13.40 + 0.1) {
            return "界内 ✅";
        }
        return "界外 ❌";
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

    /**
     * 3D 轨迹重建（简化版：假设所有点 z=0，场地平面上的轨迹）。
     * 真正 3D 重建需要 solvePnP 估计相机位姿，然后三角化。
     * 这里返回的是 2D 米制轨迹，z 固定为 0。
     */
    public List<double[]> reconstruct3D(List<Point> trajectoryPx, Mat courtH) {
        List<double[]> out = new ArrayList<>();
        if (courtH == null) return out;
        for (Point p : trajectoryPx) {
            if (p == null) continue;
            Point m = warpPoint(courtH, p);
            if (m == null) continue;
            out.add(new double[]{m.x, m.y, 0}); // z=0 平面
        }
        return out;
    }
}
