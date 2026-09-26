package com.badminton.speed.core;
import org.opencv.videoio.Videoio;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频前置校验：禁止鱼眼/广角，推荐30FPS，检测补帧/抽帧。
 * 第一阶段用 Android MediaMetadataRetriever 拿元数据，
 * 第二阶段用 OpenCV 采样帧做视觉检测。
 */
public class VideoPreValidator {

    private static final String TAG = "VideoPreValidator";

    public static class ValidationReport {
        public int fps;
        public int width, height;
        public long durationMs;
        public boolean hasWarp;          // 广角/鱼眼
        public boolean hasRepeatFrames;  // 补帧抽帧
        public boolean ok;
        public List<String> warnings = new ArrayList<>();
        public List<String> errors = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("视频信息: %dx%d, %.1f FPS, %.1fs\n",
                    width, height, fps <= 0 ? 0 : fps / 1.0, durationMs / 1000.0));
            if (!errors.isEmpty()) {
                sb.append("❌ 错误:\n");
                for (String e : errors) sb.append("   - ").append(e).append('\n');
            }
            if (!warnings.isEmpty()) {
                sb.append("⚠ 警告:\n");
                for (String w : warnings) sb.append("   - ").append(w).append('\n');
            }
            return sb.toString();
        }
    }

    public ValidationReport validate(String videoPath) {
        ValidationReport r = new ValidationReport();
        File f = new File(videoPath);
        if (!f.exists()) {
            r.errors.add("文件不存在: " + videoPath);
            return r;
        }

        // ===== 阶段1: Android 原生拿元数据 =====
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(videoPath);
            String fpsStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (fpsStr != null) r.fps = Math.round(Float.parseFloat(fpsStr));
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) r.durationMs = Long.parseLong(durStr);
            String wStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (wStr != null) r.width = Integer.parseInt(wStr);
            if (hStr != null) r.height = Integer.parseInt(hStr);
        } catch (Throwable t) {
            r.warnings.add("元数据获取失败: " + t.getMessage());
        } finally {
            try { mmr.release(); } catch (Throwable ignore) {}
        }

        // FPS 校验
        if (r.fps <= 0) {
            r.warnings.add("无法读取视频 FPS");
        } else if (r.fps < 25) {
            r.errors.add("视频帧率过低 (" + r.fps + " FPS)，推荐 30 FPS");
        } else if (r.fps > 65) {
            // 过高可能是补帧
            r.warnings.add("视频帧率过高 (" + r.fps + " FPS)，可能是补帧后的视频");
        } else if (r.fps >= 55 && r.fps <= 65) {
            // 60fps 正常，但不是 30fps 的倍数可能有抽帧
            r.warnings.add("视频帧率 " + r.fps + " FPS，推荐 30 FPS");
        }

        // ===== 阶段2: OpenCV 采样帧做视觉校验 =====
        if (!org.opencv.core.Core.getBuildInformation().isEmpty()
                || org.opencv.core.Mat.class != null) {
            tryOpenCVChecks(videoPath, r);
        }

        r.ok = r.errors.isEmpty();
        return r;
    }

    private void tryOpenCVChecks(String videoPath, ValidationReport r) {
        VideoCapture cap = new VideoCapture();
        if (!cap.open(videoPath)) {
            r.warnings.add("OpenCV 无法打开视频文件做帧级校验");
            return;
        }

        Mat frame = new Mat();
        int total = (int) cap.get(Videoio.CAP_PROP_FRAME_COUNT);
        if (total <= 0) {
            r.warnings.add("无法读取视频帧数，跳过量检");
            cap.release();
            return;
        }
        int sampleCount = Math.min(total, 30);
        int interval = Math.max(1, total / sampleCount);
        Mat prevGray = null;
        int repeatCount = 0;

        for (int i = 0; i < sampleCount; i++) {
            cap.set(Videoio.CAP_PROP_POS_FRAMES, i * interval);
            if (!cap.read(frame) || frame.empty()) break;

            Mat gray = new Mat();
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

            // 检测鱼眼/广角：边缘是否有明显弯曲
            if (i == sampleCount / 2) {
                if (hasLensWarp(frame)) {
                    r.hasWarp = true;
                    r.errors.add("检测到画面存在鱼眼/广角畸变，无法测速");
                }
            }

            // 检测重复帧（补帧/抽帧）
            if (prevGray != null) {
                double diff = Core.norm(gray, prevGray, Core.NORM_L2);
                // 归一化
                double n = gray.rows() * gray.cols();
                double perPixel = diff / Math.max(n, 1);
                if (perPixel < 0.005) {
                    repeatCount++;
                }
            }
            prevGray = gray;
        }

        if (repeatCount > sampleCount * 0.3) {
            r.hasRepeatFrames = true;
            r.warnings.add("检测到大量重复帧（可能被补帧/抽帧），测速精度会下降");
        }

        cap.release();
    }

    /**
     * 用霍夫直线检测边缘直线，如果直线有明显抛物线弯曲则认为有畸变。
     */
    private boolean hasLensWarp(Mat frame) {
        Mat gray = new Mat();
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, 50, 150, 3);

        org.opencv.core.Mat lines = new Mat();
        Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180, 80, 30, 10);
        if (lines.empty()) return false;

        int curvedCount = 0;
        for (int i = 0; i < lines.rows(); i++) {
            double[] data = lines.get(i, 0);
            if (data == null || data.length < 4) continue;
            double x1 = data[0], y1 = data[1], x2 = data[2], y2 = data[3];
            // 计算实际直线 vs 边缘点上的曲率
            // 简化：计算边缘图在该线段附近的平均像素差 vs 直线方程期望
            // 这里用端点距离 vs 线段上的平均像素偏差
            double len = Math.hypot(x2 - x1, y2 - y1);
            if (len < 50) continue; // 短线段忽略

            // 取线段中点附近的边缘强度
            double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
            if (mx < 0 || my < 0 || mx >= edges.cols() || my >= edges.rows()) continue;
            int midEdge = (int) edges.get((int) my, (int) mx)[0];
            // 如果中间没有边缘，但两端有，可能是弯曲
            if (midEdge < 20 && len > 100) {
                curvedCount++;
            }
        }
        // 如果超过一半的长线段中间断了，视为有畸变
        return curvedCount > lines.rows() / 3;
    }
}
