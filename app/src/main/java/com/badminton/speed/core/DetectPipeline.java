package com.badminton.speed.core;
import org.opencv.videoio.Videoio;

import android.content.Context;
import android.content.SharedPreferences;
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
 *   3) ShuttleDetector 羽毛球检测
 *   4) PlayerDetector 人员检测
 *   5) HitDetector 击球点检测
 *   6) SpeedCalculator 速度 + 3D 重建
 *
 * 所有计算在本地进行，不联网。
 */
public class DetectPipeline {

    private static final String TAG = "DetectPipeline";
    private static final String PREFS = "badminton_crash_recovery";

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
        public List<PlayerDetector.PlayerBox> players;
        public SpeedCalculator.Summary summary;
        public List<double[]> trajectory3D;
        public boolean success = false;
        public String errorMessage;
        public String crashStage; // 崩溃时记录阶段
    }

    public interface ProgressCallback {
        void onModuleProgress(String moduleName, int pct, String status);
        void onStageLabel(String label);
        void onFrameProcessed(int frameIndex, int totalFrames);
        void onDone(PipelineResult result);
        void onError(String msg);

        /** 场地检测完成，传回场地四角（像素坐标）+ 单应矩阵，用于实时叠加绘制 */
        default void onCourtDetected(Point[] corners, Mat homography) {}

        /** 羽毛球轨迹更新（每处理一批帧回调一次，用于实时绘制） */
        default void onShuttleTrajectory(List<Point> trajectory) {}

        /** 人员检测完成，传回人员框 */
        default void onPlayersDetected(List<PlayerDetector.PlayerBox> players) {}
    }

    private volatile boolean cancelled = false;
    private Context appContext;

    public void cancel() { cancelled = true; }

    public void setContext(Context ctx) { this.appContext = ctx; }

    /** 保存崩溃恢复状态 */
    private void saveRecoveryState(String videoPath, String stage) {
        if (appContext == null) return;
        try {
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putString("videoPath", videoPath)
                    .putString("stage", stage)
                    .putLong("timestamp", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    /** 清除崩溃恢复状态 */
    public void clearRecoveryState() {
        if (appContext == null) return;
        try {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Exception ignored) {}
    }

    /** 检查是否有未完成的检测 */
    public static String[] checkRecovery(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String path = sp.getString("videoPath", null);
            String stage = sp.getString("stage", null);
            long ts = sp.getLong("timestamp", 0);
            if (path != null && stage != null) {
                return new String[]{path, stage, String.valueOf(ts)};
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 跑完整流水线。
     */
    public PipelineResult run(String videoPath, ProgressCallback cb) {
        PipelineResult res = new PipelineResult();
        VideoPreValidator validator = new VideoPreValidator();
        CourtDetector courtDetector = new CourtDetector();
        ShuttleDetector shuttleDet = new ShuttleDetector();
        SpeedCalculator speedCalc = new SpeedCalculator();

        try {
            // ===== 阶段1: 前置校验 =====
            saveRecoveryState(videoPath, "前置校验");
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
            double fps = report.fps > 0 ? report.fps : 30;
            int totalFrames = (int) Math.round(fps * (report.durationMs > 0 ? report.durationMs / 1000.0 : 10));
            if (totalFrames <= 0) totalFrames = 300;

            // ===== 阶段2: 场地检测（只读前 30 帧，快速） =====
            saveRecoveryState(videoPath, "场地基准检测");
            if (cb != null) { cb.onStageLabel("正在检测场地线"); cb.onModuleProgress("场地基准检测", 20, "检测中 20%"); }
            CourtDetector.CourtResult court = null;
            Mat frame = new Mat();
            int courtSample = Math.min(totalFrames, 30); // 从120减到30，大幅加速
            for (int i = 0; i < courtSample; i++) {
                if (cancelled) break;
                if (!cap.read(frame) || frame.empty()) break;
                CourtDetector.CourtResult cr = courtDetector.detect(frame);
                if (cr.ok && cr.homography != null && !cr.homography.empty()) {
                    court = cr;
                    if (cb != null) cb.onModuleProgress("场地基准检测",
                            Math.min(100, (i + 1) * 100 / courtSample), "检测中 " + ((i+1)*100/courtSample) + "%");
                }
                if (cr.overlayFrame != null) cr.overlayFrame.release();
            }
            frame.release();
            cap.release();

            res.court = court;
            if (cb != null) {
                cb.onModuleProgress("场地基准检测", court != null ? 100 : 30, court != null ? "通过 ✅" : "警告⚠");
                // 场地标定完成，实时把黄色场地线叠加到左栏视频
                if (court != null && court.courtCorners != null) {
                    cb.onCourtDetected(court.courtCorners, court.homography);
                }
            }

            // ===== 阶段3+4: 羽毛球 + 人员检测（同一遍视频，只打开一次） =====
            saveRecoveryState(videoPath, "羽毛球+人员检测");
            if (cb != null) { cb.onStageLabel("正在检测羽毛球和人员"); cb.onModuleProgress("羽毛球检测", 10, "检测中 10%"); cb.onModuleProgress("人员检测", 0, "等待中"); }
            VideoCapture cap2 = new VideoCapture();
            cap2.open(videoPath);
            List<Point> trajectory = new ArrayList<>();
            List<Double> speedsPxSec = new ArrayList<>();

            // 人员检测器（帧差法，不需要 HOG，不会 native 崩溃）
            PlayerDetector playerDet = new PlayerDetector();
            List<PlayerDetector.PlayerBox> allPlayers = new ArrayList<>();
            int playerSampleCount = Math.min(30, totalFrames); // 前30帧做人员检测
            boolean playerDone = false;

            Mat frame2 = new Mat();
            int processed = 0;
            int FRAME_SKIP = 3; // 每3帧取1帧做羽毛球检测
            int frameIdx = 0;
            int playerDoneCount = 0;

            while (!cancelled && cap2.read(frame2)) {
                if (frame2.empty()) break;
                frameIdx++;

                // 人员检测：前 playerSampleCount 帧连续处理（帧差法需要连续帧）
                if (!playerDone && frameIdx <= playerSampleCount) {
                    try {
                        List<PlayerDetector.PlayerBox> boxes = playerDet.detect(frame2);
                        if (boxes != null && !boxes.isEmpty()) {
                            allPlayers.addAll(boxes);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Player detect frame " + frameIdx + " failed: " + t.getMessage());
                    }
                    playerDoneCount++;
                    if (playerDoneCount >= playerSampleCount) {
                        playerDone = true;
                        playerDet.reset();
                        if (cb != null) cb.onModuleProgress("人员检测", 100,
                                allPlayers.isEmpty() ? "警告⚠" : "通过 ✅ (" + allPlayers.size() + ")");
                    } else if (cb != null && playerDoneCount % 5 == 0) {
                        int pct = playerDoneCount * 100 / playerSampleCount;
                        cb.onModuleProgress("人员检测", Math.min(100, pct), "检测中 " + Math.min(100, pct) + "%");
                    }
                }

                // 羽毛球检测：每 FRAME_SKIP 帧处理一次
                if (frameIdx % FRAME_SKIP == 0) {
                    Point shuttlePt = null;
                    try {
                        shuttlePt = shuttleDet.detect(frame2);
                    } catch (Throwable t) {
                        Log.w(TAG, "Shuttle detect frame " + processed + " failed: " + t.getMessage());
                    }
                    trajectory.add(shuttlePt);

                    if (cb != null && processed % 10 == 0) {
                        cb.onFrameProcessed(processed, totalFrames / FRAME_SKIP);
                        int pct = 10 + (processed * 70) / Math.max(1, totalFrames / FRAME_SKIP);
                        cb.onModuleProgress("羽毛球检测", Math.min(99, pct), "检测中 " + Math.min(99, pct) + "%");
                    }
                    processed++;
                }

                // 释放当前帧
                frame2.release();
            }
            cap2.release();
            // 关键：循环结束后立即释放检测器内部的 native Mat（帧差法保留的上一帧灰度图），
            // 否则 native 堆累积到阶段5/6 时任何分配都可能触发 SIGSEGV 闪退。
            try { shuttleDet.reset(); } catch (Throwable ignored) {}
            try { playerDet.reset(); } catch (Throwable ignored) {}
            frame2.release();
            System.gc();
            Log.i(TAG, "Processed " + processed + " shuttle frames, " + allPlayers.size() + " player boxes, trajectory=" + trajectory.size());

            // 计算帧间像素速度（过滤掉 null 点，避免后续遍历 native 调用过多）
            List<Point> cleanTraj = new ArrayList<>();
            for (Point p : trajectory) if (p != null) cleanTraj.add(p);
            trajectory = cleanTraj;
            for (int i = 1; i < trajectory.size(); i++) {
                Point a = trajectory.get(i - 1);
                Point b = trajectory.get(i);
                double dist = Math.hypot(b.x - a.x, b.y - a.y);
                speedsPxSec.add(dist * fps * FRAME_SKIP); // 补偿抽帧
            }

            res.shuttleTrajectory = trajectory;
            res.players = allPlayers;
            if (cb != null) {
                cb.onModuleProgress("羽毛球检测", 100, "通过 ✅");
                if (!playerDone) cb.onModuleProgress("人员检测", 100, allPlayers.isEmpty() ? "警告⚠" : "通过 ✅");
                // 实时回传轨迹和人员框，叠加到视频
                cb.onShuttleTrajectory(trajectory);
                cb.onPlayersDetected(allPlayers);
            }

            // ===== 阶段5: 击球点检测（纯 Java，单次计算，避免高频 UI 更新） =====
            saveRecoveryState(videoPath, "击球点检测");
            if (cb != null) { cb.onStageLabel("正在检测击球点"); cb.onModuleProgress("击球点检测", 50, "检测中 50%"); }
            HitDetector hitDet = new HitDetector();
            List<HitDetector.HitEvent> hits = hitDet.detect(trajectory, speedsPxSec);
            res.hits = hits;
            if (cb != null) cb.onModuleProgress("击球点检测", 100,
                    hits.isEmpty() ? "警告⚠ 未检测到击球" : "通过 ✅ (" + hits.size() + ")");

            // ===== 阶段6: 球速计算 =====
            saveRecoveryState(videoPath, "计算球速");
            if (cb != null) { cb.onStageLabel("正在计算球速"); cb.onModuleProgress("计算球速", 30, "计算中 30%"); }
            Mat H = court != null ? court.homography : null;
            // 进入密集 native 调用前再次回收 native 堆，避免累积导致 SIGSEGV
            System.gc();
            List<Double> speedsKmH;
            try {
                speedsKmH = speedCalc.computeSpeed(trajectory, (int) fps, H);
            } catch (Throwable t) {
                Log.e(TAG, "computeSpeed failed, fallback to empty", t);
                speedsKmH = new ArrayList<>();
            }
            res.speedsKmH = speedsKmH;
            if (cb != null) cb.onModuleProgress("计算球速", 60, "计算中 60%");

            // 最终击球类型、界内界外判定
            Point finalPt = null;
            for (int i = trajectory.size() - 1; i >= 0; i--) {
                if (trajectory.get(i) != null) { finalPt = trajectory.get(i); break; }
            }
            try {
                res.summary = speedCalc.summarize(speedsKmH, finalPt, H);
            } catch (Throwable t) {
                Log.e(TAG, "summarize failed", t);
                res.summary = new SpeedCalculator.Summary();
            }
            if (cb != null) cb.onModuleProgress("计算球速", 80, "计算中 80%");

            // 3D 轨迹重建
            try {
                res.trajectory3D = speedCalc.reconstruct3D(trajectory, H);
            } catch (Throwable t) {
                Log.e(TAG, "reconstruct3D failed", t);
                res.trajectory3D = new ArrayList<>();
            }
            res.success = true;
            if (cb != null) { cb.onModuleProgress("计算球速", 100, "通过 ✅"); cb.onDone(res); }

            // 成功完成，清除崩溃恢复状态
            clearRecoveryState();

        } catch (Throwable t) {
            Log.e(TAG, "Pipeline failed at stage", t);
            res.errorMessage = t.getMessage();
            res.crashStage = "检测中断";
            if (cb != null) cb.onError(t.getMessage());
        }
        return res;
    }
}
