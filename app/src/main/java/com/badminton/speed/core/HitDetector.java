package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 击球检测 + 分段测速（按文档 2.5 / 2.6 多球速统计）。
 *
 * 核心思路：羽毛球每个回合 = 多次"击球 → 飞行 → 落地/过网"。
 * 检测速度峰值 = 击球点；两击球点之间的轨迹段 = 一次击球的飞行段。
 * 每次击球输出：
 *   - 出拍速度（击球后球离开球拍的最高速）
 *   - 落地点（击球后球的最终位置）
 *   - 界内/界外（落地点是否在场地内）
 *   - 击球类型（杀球/高远/吊球/放网）
 */
public class HitDetector {

    private static final String TAG = "HitDetector";

    /** 一次击球的完整结果 */
    public static class HitEvent {
        public int hitFrameIndex;      // 击球帧索引
        public Point hitPoint;         // 击球点（像素）
        public double outSpeedKmH;     // 出拍速度 km/h（杀球球速）
        public double avgSpeedKmH;     // 平均飞行速度 km/h（击球球速）
        public Point landingPoint;     // 落地点（像素）
        public String inOut;           // "界内 ✅" / "界外 ❌" / "未知"
        public String hitType;         // 杀球/高远/吊球/放网
    }

    /**
     * 检测击球并对每段测速。
     * @param trajectoryPx  逐采样点的羽毛球像素坐标
     * @param speedsKmH     逐采样点的瞬时速度 km/h（与 trajectoryPx 对应）
     * @param courtH        场地单应矩阵（判 in/out 用），可为 null
     * @param fps           视频帧率
     * @param frameStep     相邻采样点间隔的帧数
     * @return 每次击球的结果列表
     */
    public List<HitEvent> detect(
            List<Point> trajectoryPx,
            List<Double> speedsKmH,
            org.opencv.core.Mat courtH,
            int fps,
            int frameStep) {
        List<HitEvent> hits = new ArrayList<>();
        if (trajectoryPx == null || trajectoryPx.size() < 6
                || speedsKmH == null || speedsKmH.size() < 6) {
            return hits;
        }
        int n = Math.min(trajectoryPx.size(), speedsKmH.size());

        // Step1: 找所有速度峰值 = 击球点
        // 峰值定义：比前后都高，且超过最小阈值（噪声过滤）
        List<Integer> peakIdx = new ArrayList<>();
        double minPeak = 30; // km/h，低于此值不算击球
        for (int i = 2; i < n - 2; i++) {
            double sp = speedsKmH.get(i);
            if (sp < minPeak) continue;
            // 比左右邻居都大
            if (sp >= speedsKmH.get(i - 1) && sp >= speedsKmH.get(i + 1)
                    && sp > speedsKmH.get(i - 2) && sp > speedsKmH.get(i + 2)) {
                peakIdx.add(i);
            }
        }

        // 去重：同一击球可能有连续几帧高速，保留峰值最大的一帧
        List<Integer> deduped = new ArrayList<>();
        for (int p : peakIdx) {
            if (!deduped.isEmpty()) {
                int last = deduped.get(deduped.size() - 1);
                if (p - last < 3) {
                    // 3 帧内的峰值只保留速度最大的
                    if (speedsKmH.get(p) > speedsKmH.get(last)) {
                        deduped.remove(deduped.size() - 1);
                        deduped.add(p);
                    }
                    continue;
                }
            }
            deduped.add(p);
        }

        // Step2: 每个击球点 → 下一个击球点之间 = 飞行段
        SpeedCalculator speedCalc = new SpeedCalculator();
        for (int k = 0; k < deduped.size(); k++) {
            int hitIdx = deduped.get(k);
            int segEnd = (k + 1 < deduped.size()) ? deduped.get(k + 1) : n - 1;

            HitEvent h = new HitEvent();
            h.hitFrameIndex = hitIdx;
            h.hitPoint = trajectoryPx.get(hitIdx);

            // 出拍速度 = 击球点及之后几帧的最大速度（球刚离开球拍时最快）
            double maxSp = 0;
            int maxSpIdx = hitIdx;
            for (int i = hitIdx; i <= Math.min(hitIdx + 4, segEnd); i++) {
                if (speedsKmH.get(i) > maxSp) {
                    maxSp = speedsKmH.get(i);
                    maxSpIdx = i;
                }
            }
            h.outSpeedKmH = maxSp;

            // 击球类型（按出拍速度分类）
            h.hitType = classifyHitType(maxSp);

            // 落地点 = 该段最后一个有效点
            Point landing = null;
            for (int i = segEnd; i > hitIdx; i--) {
                if (trajectoryPx.get(i) != null) {
                    landing = trajectoryPx.get(i);
                    break;
                }
            }
            h.landingPoint = landing;

            // 平均飞行速度 = 该段位移 / 时间
            if (landing != null && h.hitPoint != null) {
                // 用单应矩阵算米制距离；没有则用像素近似
                Point hitM = courtH != null ? speedCalc.warpPointPublic(courtH, h.hitPoint) : null;
                Point landM = courtH != null ? speedCalc.warpPointPublic(courtH, landing) : null;
                if (hitM != null && landM != null) {
                    double distM = Math.hypot(landM.x - hitM.x, landM.y - hitM.y);
                    int frames = segEnd - hitIdx;
                    double dt = (double) frames * frameStep / Math.max(1, fps);
                    h.avgSpeedKmH = dt > 0 ? (distM / dt) * 3.6 : 0;
                } else {
                    h.avgSpeedKmH = 0;
                }
            }

            // 界内界外判定
            if (landing != null && courtH != null) {
                h.inOut = speedCalc.judgeInOut(landing, courtH);
            } else {
                h.inOut = "未知";
            }

            hits.add(h);
        }

        Log.i(TAG, "Detected " + hits.size() + " hits");
        return hits;
    }

    /** 击球类型分类（按出拍速度 km/h） */
    public static String classifyHitType(double speedKmH) {
        if (speedKmH > 300) return "杀球 Smash";
        if (speedKmH > 200) return "高远 Clear";
        if (speedKmH > 120) return "吊球 Drop";
        return "放网 Netshot";
    }
}
