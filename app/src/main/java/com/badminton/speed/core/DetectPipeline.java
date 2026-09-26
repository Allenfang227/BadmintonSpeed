package com.badminton.speed.core;
import org.opencv.videoio.Videoio;

import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.videoio.VideoCapture;

import java.util.ArrayList;
import java.util.List;

/**
 * 检测流水线：把 5 个模块串起来跑完整视频。
 *   1) VideoPreValidator 前置校验
 *   2) CourtDetector 场地基准
 *   3) 主循环逐帧：ShuttleDetector + PlayerDetector
 *   4) HitDetector 击球点
 *   5) SpeedCalculator 速度 + 3D 重建
 *
 * 所有计算在本地进行，不联网。
 */
public class DetectPipeline {

    private static final String TAG = "DetectPipeline";

    public static class ModuleStatus {
        public String name;
        public String desc;
        public String[] subItems;
        public int progress;     // 0-100
        public String status;    // 未检测 / 检测中 xx% / 通过 / 未通过 / 警告
        public boolean done;
        public boolean passed;
    }

    public static class PipelineResult {
        public VideoPreValidator.ValidationReport validation;
        public CourtDetector.CourtResult court;
        public List<Point> shuttleTrajectory;
        public List<Double> speedsKmH;
        public List<HitDetector.HitEvent> hits;
        public SpeedCalculator.Summary summary;
        public List<double[]> trajectory3D;  // 3D 重建轨迹（米制坐标）
        public boolean success = false;
        public String errorMessage;
    }

    public interface ProgressCallback {
        void onModuleProgress(String moduleName, int pct, String status);
        void onStageLabel(String label);
        void onFrameProcessed(int frameIndex, int totalFrames);
        void onDone(PipelineResult result);
        void onError(String msg);
    }

    private volatile boolean cancelled = false;

    public void cancel() { cancelled = true; }

    /**
     * 跑完整流水线。
     */
    public PipelineResult run(String videoPath, ProgressCallback cb) {
        PipelineResult res = new PipelineResult();
        VideoPreValidator validator = new VideoPreValidator();
        CourtDetector courtDetector = new CourtDetector();
        ShuttleDetector shuttleDet = new ShuttleDetector();
        PlayerDetector playerDet = new PlayerDetector();
        SpeedCalculator speedCalc = new SpeedCalculator();

        try {
            // ===== 前置校验 =====
            if (cb != null) { cb.onStageLabel("正在处理视频"); cb.onModuleProgress("前置校验", 5, "检测中 5%"); }
            VideoPreValidator.ValidationReport report = validator.validate(videoPath);
            res.validation = report;

            if (!report.ok) {
                res.errorMessage = report.errors.get(0);
                if (cb != null) cb.onError(res.errorMessage);
                return res;
            }

            // ===== 打开视频 =====
            VideoCapture cap = new VideoCapture();
            if (!cap.open(videoPath)) {
                res.errorMessage = "无法打开视频文件";
                if (cb != null) cb.onError(res.errorMessage);
                return res;
            }
            // FPS 和帧数优先用 validator 估算结果（Android 上 CAP_PROP 不可靠）
            double fps = report.fps > 0 ? report.fps : 30;
            int totalFrames = (int) Math.round(fps * (report.durationMs > 0 ? report.durationMs / 1000.0 : 10));
            if (totalFrames <= 0) totalFrames = 300;

            // ===== 场地检测（顺序读前 120 帧，不用 seek，Android 上 seek 不可靠）=====
            if (cb != null) { cb.onStageLabel("正在检测场地线"); cb.onModuleProgress("场地基准检测", 20, "检测中 20%"); }
            CourtDetector.CourtResult court = null;
            Mat frame = new Mat();
            int sampleFrames = Math.min(totalFrames, 120);
            for (int i = 0; i < sampleFrames; i++) {
                if (cancelled) break;
                if (!cap.read(frame) || frame.empty()) break;
                CourtDetector.CourtResult cr = courtDetector.detect(frame);
                if (cr.ok && cr.homography != null && !cr.homography.empty()) {
                    court = cr;
                    if (cb != null) cb.onModuleProgress("场地基准检测", Math.min(100, (i + 1) * 100 / sampleFrames), "检测中");
                }
                if (cr.overlayFrame != null) cr.overlayFrame.release();
            }
            frame.release();
            cap.release(); // 关闭，重新打开以确保从第一帧开始

            res.court = court;
            if (cb != null) cb.onModuleProgress("场地基准检测", court != null ? 100 : 30, court != null ? "通过 ✅" : "警告⚠");

            // ===== 羽毛球 + 人员 + 击球点 逐帧处理（重新打开视频）=====
            if (cb != null) { cb.onStageLabel("正在检测羽毛球"); }
            VideoCapture cap2 = new VideoCapture();
            cap2.open(videoPath);
            List<Point> trajectory = new ArrayList<>();
            List<Double> speedsPxSec = new ArrayList<>();

            Mat frame2 = new Mat();
            int processed = 0;
            int frameStep = Math.max(1, (int) Math.round(fps / 30.0)); // 如果视频 > 30fps，抽帧
            int frameStepCounter = 0;

            while (!cancelled && cap2.read(frame2)) {
                if (frame2.empty()) break;
                frameStepCounter++;
                if (frameStepCounter % frameStep != 0) {
                    frame2.release();
                    continue;
                }

                // 羽毛球检测
                Point shuttlePt = null;
                try {
                    shuttlePt = shuttleDet.detect(frame2);
                } catch (Throwable t) {
                    Log.w(TAG, "Shuttle detect frame " + processed + " failed: " + t.getMessage());
                }
                trajectory.add(shuttlePt); // 可能是 null，后续处理会跳过

                // 人员检测（每 10 帧一次，省算力）
                if (processed % 10 == 0 && cb != null) {
                    try { playerDet.detect(frame2); } catch (Throwable ignored) {}
                }

                // 释放当前帧内存（关键！不释放会导致 OOM 闪退）
                frame2.release();

                if (cb != null && processed % 5 == 0) {
                    cb.onFrameProcessed(processed, totalFrames);
                    int pct = 20 + (processed * 60) / Math.max(1, totalFrames);
                    cb.onModuleProgress("羽毛球检测", Math.min(100, pct), "检测中");
                }
                processed++;
            }
            cap2.release();
            Log.i(TAG, "Processed " + processed + " frames, trajectory size=" + trajectory.size());

            // 计算帧间像素速度
            for (int i = 1; i < trajectory.size(); i++) {
                Point a = trajectory.get(i - 1);
                Point b = trajectory.get(i);
                if (a == null || b == null) { speedsPxSec.add(0.0); continue; }
                double dist = Math.hypot(b.x - a.x, b.y - a.y);
                speedsPxSec.add(dist * fps);
            }

            res.shuttleTrajectory = trajectory;
            if (cb != null) cb.onModuleProgress("羽毛球检测", 100, "通过 ✅");

            // ===== 击球点检测 =====
            if (cb != null) { cb.onStageLabel("正在检测击球点"); cb.onModuleProgress("击球点检测", 50, "检测中"); }
            HitDetector hitDet = new HitDetector();
            List<HitDetector.HitEvent> hits = hitDet.detect(trajectory, speedsPxSec);
            res.hits = hits;
            if (cb != null) cb.onModuleProgress("击球点检测", 100, hits.isEmpty() ? "警告⚠" : "通过 ✅");

            // ===== 球速计算 =====
            if (cb != null) { cb.onStageLabel("正在计算球速"); cb.onModuleProgress("计算球速", 30, "计算中"); }
            Mat H = court != null ? court.homography : null;
            List<Double> speedsKmH = speedCalc.computeSpeed(trajectory, (int) fps, H);
            res.speedsKmH = speedsKmH;
            if (cb != null) cb.onModuleProgress("计算球速", 80, "计算中");

            // 最终击球类型、界内界外判定 —— 找最后一个非 null 的点
            Point finalPt = null;
            for (int i = trajectory.size() - 1; i >= 0; i--) {
                if (trajectory.get(i) != null) { finalPt = trajectory.get(i); break; }
            }
            res.summary = speedCalc.summarize(speedsKmH, finalPt, H);
            // 3D 轨迹重建
            res.trajectory3D = speedCalc.reconstruct3D(trajectory, H);
            res.success = true;
            if (cb != null) cb.onModuleProgress("计算球速", 100, "通过 ✅");

        } catch (Throwable t) {
            Log.e(TAG, "Pipeline failed", t);
            res.errorMessage = t.getMessage();
            if (cb != null) cb.onError(t.getMessage());
        }
        return res;
    }
}
