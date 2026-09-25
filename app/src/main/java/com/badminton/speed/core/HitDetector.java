package com.badminton.speed.core;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * 击球点检测：
 *   1) 用光流追踪球拍/持拍者（Lucas-Kanade）
 *   2) 同时追踪羽毛球（ShuttleDetector）
 *   3) 当羽毛球速度突然变化 + 接近球拍位置时，判断为击球点
 *   4) 击球类型由出拍后初始速度决定：
 *      > 450 km/h → 杀球  smashes
 *      > 300 km/h → 点杀  smashes
 *      > 200 km/h → 高远  clears
 *      > 100 km/h → 吊球  drops
 *      其他       → 放网  netshots
 */
public class HitDetector {

    private static final String TAG = "HitDetector";
    private static final double HIT_DIST_PX = 120; // 球拍附近像素阈值

    public static class HitEvent {
        public int frameIndex;
        public Point hitPoint;       // 击球点（像素）
        public double outSpeedKmH;   // 出拍速度
        public String hitType;
    }

    /**
     * 检测序列中的击球事件。输入：逐帧的羽毛球位置 + 帧间速度。
     * 简化版：找到速度突然达峰且前后有明显变化的位置。
     */
    public List<HitEvent> detect(List<Point> shuttleTrajectory, List<Double> speedsPxPerSec) {
        List<HitEvent> hits = new ArrayList<>();
        if (shuttleTrajectory.size() < 10) return hits;

        // 找速度极值点
        for (int i = 2; i < speedsPxPerSec.size() - 2; i++) {
            double sp = speedsPxPerSec.get(i);
            double prevSp = speedsPxPerSec.get(i - 1);
            double nextSp = speedsPxPerSec.get(i + 1);
            // 击球特征：击球前快速加速 → 击球后减速或匀速
            if (sp > prevSp * 1.2 && sp > nextSp * 1.1 && sp > 100) {
                HitEvent h = new HitEvent();
                h.frameIndex = i;
                h.hitPoint = shuttleTrajectory.get(i);
                h.outSpeedKmH = 0; // 需要 SpeedCalculator 填
                h.hitType = classifyHitType(sp);
                hits.add(h);
            }
        }
        return hits;
    }

    /**
     * 真正的光流追踪：返回特征点在当前帧的新位置。
     * 可用于球拍 / 持拍者位置估算。
     */
    public MatOfPoint2f trackOpticalFlow(Mat prevGray, Mat currGray, MatOfPoint2f prevPts) {
        if (prevPts == null || prevPts.empty()) return new MatOfPoint2f();
        MatOfPoint2f nextPts = new MatOfPoint2f();
        MatOfByte status = new MatOfByte();
        org.opencv.core.MatOfFloat err = new org.opencv.core.MatOfFloat();
        Size winSize = new Size(21, 21);
        Video.calcOpticalFlowPyrLK(prevGray, currGray, prevPts, nextPts, status, err, winSize, 3,
                new org.opencv.core.TermCriteria(
                        org.opencv.core.TermCriteria.EPS | org.opencv.core.TermCriteria.MAX_ITER, 30, 0.01),
                0, 0);
        return nextPts;
    }

    public static String classifyHitType(double speedPxPerSec) {
        // 先用像素速度近似（实际应该换算成 km/h）
        if (speedPxPerSec > 4500) return "杀球 Smash";
        if (speedPxPerSec > 3000) return "点杀 Flick";
        if (speedPxPerSec > 2000) return "高远 Clear";
        if (speedPxPerSec > 1000) return "吊球 Drop";
        return "放网 Netshot";
    }
}
