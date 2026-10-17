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
 *   3) 速度 = 位移 / 时间 → m/s → km/h
 *   4) 3D 重建：地面平面近似（z≈0），更精确可结合 solvePnP。
 *
 * v2.45 算法优化：
 *   - 修复 null 点填 0、米/像素单位混用导致的伪高速度 bug
 *   - 物理上限过滤（羽毛球世界纪录 ~493 km/h，>600 视为异常剔除）
 *   - 平滑改为「窗口内有效值平均」，避免 0 值把真实峰值拉低
 */
public class SpeedCalculator {

    private static final String TAG = "SpeedCalculator";
    /** 羽毛球物理速度上限 km/h（超出视为检测跳变，剔除） */
    private static final double MAX_PLAUSIBLE_KMH = 600.0;

    /**
     * 计算球速（含稳健平滑）。
     * @param trajectoryPx 每步羽毛球像素坐标（至少 2 个点；pipeline 已过滤 null）
     * @param fps 视频帧率
     * @param frameStep 相邻轨迹点间隔的帧数（如每 4 帧采 1 点则传 4）
     * @param courtH 场地单应矩阵（图像 → 标准场地坐标，单位：米），可为 null
     * @return 每步速度列表 km/h（与 trajectoryPx 等长、按索引对齐）
     */
    public List<Double> computeSpeed(List<Point> trajectoryPx, int fps, int frameStep, Mat courtH) {
        List<Double> out = new ArrayList<>();
        if (trajectoryPx == null || trajectoryPx.size() < 2 || fps <= 0) return out;
        if (frameStep < 1) frameStep = 1;

        double dt = (double) frameStep / fps; // 相邻轨迹点的真实时间间隔（秒）
        int n = trajectoryPx.size();
        double[] raw = new double[n];

        Point prevPx = null;   // 无标定时的上一点（像素）
        Point prevMeter = null; // 有标定时的上一点（米）

        for (int i = 0; i < n; i++) {
            Point px = trajectoryPx.get(i);
            double v = 0;
            if (px == null) {
                // 断点：不计速度，且断开连续性，避免跨缺口算出伪速度
                prevPx = null;
                prevMeter = null;
                raw[i] = 0;
                continue;
            }
            if (courtH == null) {
                // 无标定退化：像素速度（px/s），仅作相对参考
                if (prevPx != null) {
                    v = Math.hypot(px.x - prevPx.x, px.y - prevPx.y) / dt;
                }
                prevPx = px;
            } else {
                Point m = warpPoint(courtH, px);
                if (m != null && prevMeter != null) {
                    v = Math.hypot(m.x - prevMeter.x, m.y - prevMeter.y) / dt * 3.6; // km/h
                }
                // 单位严格一致：warp 失败即断开，绝不拿像素点去和米制点相减
                prevMeter = m;
            }
            if (v > MAX_PLAUSIBLE_KMH) v = 0; // 离群过滤
            raw[i] = v;
        }

        return smoothValid(raw);
    }

    /**
     * 稳健平滑：对每个位置，取窗口内「有效(>0)」值的平均；窗口内无有效值则填 0。
     * 保留与原序列等长、按索引对齐（HitDetector 依赖此对齐做峰值检测）。
     */
    private List<Double> smoothValid(double[] raw, int win) {
        List<Double> out = new ArrayList<>(raw.length);
        int half = win / 2;
        for (int i = 0; i < raw.length; i++) {
            double sum = 0;
            int c = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(raw.length - 1, i + half); j++) {
                if (raw[j] > 0) { sum += raw[j]; c++; }
            }
            out.add(c > 0 ? sum / c : 0.0);
        }
        return out;
    }

    private List<Double> smoothValid(double[] raw) {
        return smoothValid(raw, 5);
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
        public double maxSpeed;        // 最高杀球速度 km/h
        public double avgSpeed;        // 平均杀球速度 km/h
        public String hitType;         // 最快速的击球类型
        public String inOut;           // 最后一次落地的界内/界外
        public int totalHits;          // 总击球数
        public int inCount;            // 界内次数
        public int outCount;           // 界外次数
    }

    /** 基于击球列表的汇总统计：最高/平均杀球速度、界内界外计数、总击球数。 */
    public Summary summarizeWithHits(List<Double> speeds, Point finalShuttlePos, Mat courtH,
                                     List<HitDetector.HitEvent> hits) {
        Summary s = new Summary();
        if (hits != null && !hits.isEmpty()) {
            double max = 0, sum = 0;
            String topType = "未知";
            int inCnt = 0, outCnt = 0;
            for (HitDetector.HitEvent h : hits) {
                if (h.outSpeedKmH > max) {
                    max = h.outSpeedKmH;
                    topType = h.hitType;
                }
                sum += h.outSpeedKmH;
                if (h.inOut != null && h.inOut.contains("界内")) inCnt++;
                else if (h.inOut != null && h.inOut.contains("界外")) outCnt++;
            }
            s.maxSpeed = max;
            s.avgSpeed = sum / hits.size();
            s.hitType = topType;
            s.totalHits = hits.size();
            s.inCount = inCnt;
            s.outCount = outCnt;
            // 最后一次落地的 in/out
            HitDetector.HitEvent last = hits.get(hits.size() - 1);
            s.inOut = last.inOut;
        } else {
            // 回退：用瞬时速度统计
            s.maxSpeed = 0; s.avgSpeed = 0;
            if (speeds != null) {
                for (Double v : speeds) if (v > s.maxSpeed) s.maxSpeed = v;
            }
            s.hitType = HitDetector.classifyHitType(s.maxSpeed);
            s.inOut = judgeInOut(finalShuttlePos, courtH);
            s.totalHits = 0;
        }
        return s;
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

    /** 透视变换公开接口（供 HitDetector 等外部调用）。 */
    public Point warpPointPublic(Mat H, Point p) {
        return warpPoint(H, p);
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
