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
        /** 全视频运动员追踪：每条 {frameIdx, x, y, w, h}，结果页按播放时间索引画框 */
        public List<double[]> playerTrack;
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

        /** 人员检测完成，传回人员框（含击球人员索引） */
        default void onPlayersDetected(List<PlayerDetector.PlayerBox> players, int hitterIndex) {}

        /** 单个子步骤状态更新：0=未开始 1=进行中 2=通过 3=失败 */
        default void onSubStep(String moduleName, int subIndex, int status) {}
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

            // ===== 单遍视频遍历架构：全程只打开一次 VideoCapture =====
            // 在同一遍读取中完成：场地检测(前30帧) + 人员追踪(全视频每2帧) + 羽毛球检测(每3帧)。
            // 从根本上消除多次打开/关闭 VideoCapture 导致的 native SIGSEGV 闪退。
            saveRecoveryState(videoPath, "场地基准检测");
            if (cb != null) {
                cb.onStageLabel("正在检测场地线");
                cb.onModuleProgress("场地基准检测", 20, "检测中 20%");
                cb.onSubStep("场地基准检测", 0, 1);
            }
            PlayerDetector playerDet = new PlayerDetector();
            List<PlayerDetector.PlayerBox> allPlayers = new ArrayList<>();
            List<Point> trajectory = new ArrayList<>();
            List<Double> speedsPxSec = new ArrayList<>();

            int courtSample = Math.min(totalFrames, 30);
            int FRAME_SKIP = 3;

            CourtDetector.CourtResult court = null;
            Mat frame = new Mat();
            int frameIdx = 0;
            int processed = 0;
            boolean courtDone = false, shuttleAnnounced = false;
            List<double[]> playerTrack = new ArrayList<>(); // 每 2 帧记录运动员框，供结果页实时同步

            try {
            while (!cancelled && cap.read(frame)) {
                if (frame.empty()) break;
                frameIdx++;

                // 场地检测：前 30 帧
                if (!courtDone) {
                    if (frameIdx <= courtSample) {
                        try {
                            CourtDetector.CourtResult cr = courtDetector.detect(frame);
                            if (cr.ok && cr.homography != null && !cr.homography.empty()) court = cr;
                            if (cr.overlayFrame != null) cr.overlayFrame.release();
                        } catch (Throwable t) {
                            Log.w(TAG, "Court detect frame " + frameIdx + " failed: " + t.getMessage());
                        }
                        if (cb != null && frameIdx % 10 == 0) {
                            int pct = Math.min(95, frameIdx * 100 / courtSample);
                            cb.onModuleProgress("场地基准检测", pct, "检测中 " + pct + "%");
                            int step = Math.min(3, frameIdx * 4 / courtSample);
                            for (int s = 0; s <= step; s++) {
                                if (s < 3) cb.onSubStep("场地基准检测", s, 2);
                                else cb.onSubStep("场地基准检测", s, 1);
                            }
                        }
                        if (frameIdx >= courtSample) courtDone = true;
                    } else {
                        courtDone = true;
                    }
                }

                // 人员检测：全视频每 2 帧检测一次，记录运动员框时间线（随视频实时运动）
                if (frameIdx % 2 == 0 && !cancelled) {
                    try {
                        List<PlayerDetector.PlayerBox> boxes = playerDet.detect(frame);
                        if (boxes != null) {
                            for (PlayerDetector.PlayerBox pb : boxes) {
                                if (pb == null || pb.rect == null) continue;
                                playerTrack.add(new double[]{
                                        frameIdx, pb.rect.x, pb.rect.y, pb.rect.width, pb.rect.height});
                            }
                            allPlayers.addAll(boxes);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Player detect frame " + frameIdx + " failed: " + t.getMessage());
                    }
                }

                // 羽毛球检测：每 3 帧取 1 帧
                if (frameIdx % FRAME_SKIP == 0) {
                    Point shuttlePt = null;
                    try {
                        shuttlePt = shuttleDet.detect(frame);
                    } catch (Throwable t) {
                        Log.w(TAG, "Shuttle detect frame " + processed + " failed: " + t.getMessage());
                    }
                    trajectory.add(shuttlePt);
                    processed++;

                    // 场地完成后才上报羽毛球进度（保持 UI 从上到下顺序）
                    if (courtDone && cb != null && processed % 20 == 0) {
                        if (!shuttleAnnounced) {
                            shuttleAnnounced = true;
                            cb.onStageLabel("正在检测羽毛球");
                            cb.onModuleProgress("羽毛球检测", 10, "检测中 10%");
                            cb.onSubStep("羽毛球检测", 0, 1);
                        }
                        cb.onFrameProcessed(processed, totalFrames / FRAME_SKIP);
                        int pct = 10 + (processed * 80) / Math.max(1, totalFrames / FRAME_SKIP);
                        cb.onModuleProgress("羽毛球检测", Math.min(95, pct), "检测中 " + Math.min(95, pct) + "%");
                    }
                }

                // 每 30 帧回收一次 native 堆，保持内存稳定
                if (frameIdx % 30 == 0) System.gc();
            }
            } finally {
                // 单遍结束：无论成功失败都释放 native 资源，防止泄漏累积导致闪退
                frame.release();
                cap.release();
                try { shuttleDet.reset(); } catch (Throwable ignored) {}
                try { playerDet.reset(); } catch (Throwable ignored) {}
                System.gc();
            }
            res.playerTrack = playerTrack;

            // 场地结果上报
            res.court = court;
            if (cb != null) {
                for (int s = 0; s < 4; s++) cb.onSubStep("场地基准检测", s, court != null ? 2 : 3);
                cb.onModuleProgress("场地基准检测", court != null ? 100 : 30, court != null ? "通过 ✅" : "警告⚠");
                if (court != null && court.courtCorners != null) {
                    cb.onCourtDetected(court.courtCorners, court.homography);
                }
            }

            // 羽毛球结果上报
            if (cb != null) {
                for (int s = 0; s < 4; s++) cb.onSubStep("羽毛球检测", s, 2);
            }
            List<Point> cleanTraj = new ArrayList<>();
            for (Point p : trajectory) if (p != null) cleanTraj.add(p);
            trajectory = cleanTraj;
            for (int i = 1; i < trajectory.size(); i++) {
                Point a = trajectory.get(i - 1);
                Point b = trajectory.get(i);
                double dist = Math.hypot(b.x - a.x, b.y - a.y);
                speedsPxSec.add(dist * fps * FRAME_SKIP);
            }
            res.shuttleTrajectory = trajectory;
            if (cb != null) {
                cb.onModuleProgress("羽毛球检测", 100, "通过 ✅ (" + trajectory.size() + "点)");
                cb.onShuttleTrajectory(trajectory);
            }
            Log.i(TAG, "Shuttle done: " + trajectory.size() + " points");

            // 人员结果上报（在羽毛球之后，保持从上到下顺序）
            saveRecoveryState(videoPath, "人员检测");
            if (cb != null) {
                cb.onStageLabel("正在检测人员");
                cb.onModuleProgress("人员检测", 50, "检测中 50%");
                cb.onSubStep("人员检测", 0, 1);
            }
            List<PlayerDetector.PlayerBox> uniquePlayers = dedupPlayers(allPlayers);
            int hitterIndex = findHitter(uniquePlayers, trajectory);
            res.players = uniquePlayers;
            if (cb != null) {
                for (int s = 0; s < 4; s++) cb.onSubStep("人员检测", s, 2);
                cb.onModuleProgress("人员检测", 100,
                        uniquePlayers.isEmpty() ? "警告⚠" : "通过 ✅ (" + uniquePlayers.size() + "人)");
                cb.onPlayersDetected(uniquePlayers, hitterIndex);
            }
            Log.i(TAG, "Player done: " + uniquePlayers.size() + " players, hitter=" + hitterIndex);

            // ===== 阶段5: 击球点检测（纯 Java） =====
            saveRecoveryState(videoPath, "击球点检测");
            if (cb != null) {
                cb.onStageLabel("正在检测击球点");
                cb.onModuleProgress("击球点检测", 30, "检测中 30%");
                cb.onSubStep("击球点检测", 0, 1); // 速度极值分析 进行中
            }
            HitDetector hitDet = new HitDetector();
            if (cb != null) cb.onSubStep("击球点检测", 0, 2);
            if (cb != null) { cb.onModuleProgress("击球点检测", 70, "检测中 70%"); cb.onSubStep("击球点检测", 1, 1); }
            List<HitDetector.HitEvent> hits = hitDet.detect(trajectory, speedsPxSec);
            res.hits = hits;
            if (cb != null) {
                cb.onSubStep("击球点检测", 1, 2);
                cb.onModuleProgress("击球点检测", 100,
                        hits.isEmpty() ? "警告⚠ 未检测到击球" : "通过 ✅ (" + hits.size() + ")");
            }

            // ===== 阶段6: 球速计算 =====
            saveRecoveryState(videoPath, "计算球速");
            if (cb != null) {
                cb.onStageLabel("正在计算球速");
                cb.onModuleProgress("计算球速", 20, "计算中 20%");
                cb.onSubStep("计算球速", 0, 1); // 轨迹重建 进行中
            }
            Mat H = court != null ? court.homography : null;
            System.gc();
            List<Double> speedsKmH;
            try {
                speedsKmH = speedCalc.computeSpeed(trajectory, (int) fps, H);
            } catch (Throwable t) {
                Log.e(TAG, "computeSpeed failed, fallback to empty", t);
                speedsKmH = new ArrayList<>();
            }
            res.speedsKmH = speedsKmH;
            if (cb != null) { cb.onSubStep("计算球速", 0, 2); cb.onModuleProgress("计算球速", 50, "计算中 50%"); cb.onSubStep("计算球速", 1, 1); }

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
            if (cb != null) { cb.onSubStep("计算球速", 1, 2); cb.onModuleProgress("计算球速", 80, "计算中 80%"); cb.onSubStep("计算球速", 2, 1); }

            // 3D 轨迹重建
            try {
                res.trajectory3D = speedCalc.reconstruct3D(trajectory, H);
            } catch (Throwable t) {
                Log.e(TAG, "reconstruct3D failed", t);
                res.trajectory3D = new ArrayList<>();
            }
            res.success = true;
            if (cb != null) {
                cb.onSubStep("计算球速", 2, 2);
                cb.onModuleProgress("计算球速", 100, "通过 ✅");
                cb.onDone(res);
            }

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

    /** 人员框去重：按中心点合并重叠框（同一人在多帧中被多次检测） */
    private List<PlayerDetector.PlayerBox> dedupPlayers(List<PlayerDetector.PlayerBox> boxes) {
        List<PlayerDetector.PlayerBox> result = new ArrayList<>();
        if (boxes == null || boxes.isEmpty()) return result;
        for (PlayerDetector.PlayerBox b : boxes) {
            if (b == null || b.rect == null) continue;
            double cx = b.rect.x + b.rect.width / 2.0;
            double cy = b.rect.y + b.rect.height / 2.0;
            boolean dup = false;
            for (PlayerDetector.PlayerBox r : result) {
                double rx = r.rect.x + r.rect.width / 2.0;
                double ry = r.rect.y + r.rect.height / 2.0;
                // 中心点距离小于 100px（原始坐标）视为同一人
                if (Math.hypot(cx - rx, cy - ry) < 100) { dup = true; break; }
            }
            if (!dup) {
                b.id = result.size();
                result.add(b);
                // 最终最多保留 4 人
                if (result.size() >= 4) break;
            }
        }
        return result;
    }

    /** 识别击球人员：离羽毛球轨迹平均点最近的人员 */
    private int findHitter(List<PlayerDetector.PlayerBox> players, List<Point> trajectory) {
        if (players == null || players.isEmpty()) return -1;
        if (trajectory == null || trajectory.isEmpty()) return 0;
        // 轨迹平均点
        double ax = 0, ay = 0;
        for (Point p : trajectory) { ax += p.x; ay += p.y; }
        ax /= trajectory.size(); ay /= trajectory.size();
        // 找离轨迹平均点最近的人员
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) {
            PlayerDetector.PlayerBox b = players.get(i);
            double px = b.rect.x + b.rect.width / 2.0;
            double py = b.rect.y + b.rect.height / 2.0;
            double d = Math.hypot(px - ax, py - ay);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }
}
