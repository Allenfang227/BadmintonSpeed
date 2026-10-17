package com.badminton.speed.core;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;

import java.io.File;
import java.util.List;

/**
 * 视频导出器：把「原视频 + 分析叠加层（场地框 / 羽毛球轨迹 / 运动员框 / 测速信息）」
 * 重新编码为一个可保存/分享的 MP4。
 *
 * 全程 OpenCV（VideoCapture 读 → Imgproc 画 → VideoWriter 写），输出 BGR，码流 mp4v（失败回退 avc1）。
 * 产物保存到应用外置影音目录 BadmintonSpeed/ 并做媒体扫描，出现在系统相册。
 *
 * 注意：export() 为耗时操作，调用方需放到子线程执行；回调由调用方切主线程。
 */
public class VideoExporter {
    private static final String TAG = "VideoExporter";

    public static class ExportData {
        public String videoPath;
        public Point[] courtCorners;          // 场地四角（视频像素坐标）
        public List<Point> trajectory;        // 羽毛球 2D 轨迹（视频像素坐标）
        public List<double[]> playerTrack;    // {frameIdx,x,y,w,h}（视频像素坐标）
        public List<HitDetector.HitEvent> hits;
        public double fps = 30;
        public int shuttleSkip = 4;           // 轨迹采样步长（帧）
        public double maxSpeed = 0;
        public String inOut = "未知";
    }

    /** 在调用方线程执行；成功返回文件绝对路径，失败抛异常。 */
    public static String export(Context ctx, ExportData d) throws Exception {
        if (ctx == null || d == null || d.videoPath == null) {
            throw new IllegalArgumentException("导出参数不完整");
        }
        VideoCapture cap = new VideoCapture();
        if (!cap.open(d.videoPath)) throw new Exception("无法打开源视频");

        double fps = cap.get(Videoio.CAP_PROP_FPS);
        if (fps <= 0 || Double.isNaN(fps) || Double.isInfinite(fps)) fps = d.fps > 0 ? d.fps : 30;
        int w = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int h = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        if (w <= 0 || h <= 0) { cap.release(); throw new Exception("无法读取视频尺寸"); }

        File outDir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BadmintonSpeed");
        if (!outDir.exists() && !outDir.mkdirs()) {
            cap.release(); throw new Exception("无法创建输出目录");
        }
        File outFile = new File(outDir, "badminton_" + System.currentTimeMillis() + ".mp4");

        VideoWriter writer = new VideoWriter(outFile.getAbsolutePath(),
                VideoWriter.fourcc('m', 'p', '4', 'v'), fps, new Size(w, h));
        if (!writer.isOpened()) {
            writer = new VideoWriter(outFile.getAbsolutePath(),
                    VideoWriter.fourcc('a', 'v', 'c', '1'), fps, new Size(w, h));
        }
        if (!writer.isOpened()) { cap.release(); throw new Exception("无法创建视频编码器"); }

        // BGR 配色，与应用叠加层一致
        final Scalar COURT = new Scalar(0, 215, 255);   // 黄
        final Scalar TRAJ  = new Scalar(0, 255, 65);    // 绿
        final Scalar BALL  = new Scalar(0, 255, 128);
        final Scalar PLAYER= new Scalar(0, 200, 255);   // 橙黄
        final Scalar WHITE = new Scalar(255, 255, 255);
        final Scalar BARBG = new Scalar(20, 20, 20);

        Mat frame = new Mat();
        int f = 0;
        try {
            while (cap.read(frame)) {
                if (frame.empty()) break;
                f++;
                drawOverlay(frame, d, f, COURT, TRAJ, BALL, PLAYER, WHITE, BARBG);
                writer.write(frame);
            }
        } finally {
            frame.release();
            cap.release();
            writer.release();
        }
        if (f == 0) {
            if (outFile.exists()) outFile.delete();
            throw new Exception("视频无有效帧");
        }

        // 媒体扫描 → 出现在系统相册/影音
        try {
            MediaScannerConnection.scanFile(ctx.getApplicationContext(),
                    new String[]{outFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        } catch (Throwable ignore) {}

        Log.i(TAG, "exported " + f + " frames -> " + outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    /** 轨迹点 j 对应的源视频帧号估计（检测每 shuttleSkip 帧一次，首点约在第 shuttleSkip 帧） */
    private static int estFrame(int j, int skip) {
        return (j + 1) * Math.max(1, skip);
    }

    private static void drawOverlay(Mat frame, ExportData d, int curFrame,
                                    Scalar courtC, Scalar trajC, Scalar ballC,
                                    Scalar playerC, Scalar white, Scalar barBg) {
        int w = frame.cols(), h = frame.rows();

        // 顶部信息条（Imgproc.putText 仅支持 ASCII）
        String last = (d.inOut != null && d.inOut.contains("界内")) ? "IN"
                : (d.inOut != null && d.inOut.contains("界外")) ? "OUT" : "-";
        int hitCnt = d.hits != null ? d.hits.size() : 0;
        String info = String.format("MAX %d km/h   HITS %d   LAST %s", (int) d.maxSpeed, hitCnt, last);
        Imgproc.rectangle(frame, new Point(0, 0), new Point(w, 42), barBg, Imgproc.FILLED);
        Imgproc.putText(frame, info, new Point(12, 29),
                0 /* FONT_HERSHEY_SIMPLEX */, 0.75, white, 2, Imgproc.LINE_AA);

        // 场地四角框（黄）
        if (d.courtCorners != null && d.courtCorners.length >= 4) {
            for (int i = 0; i < 4; i++) {
                Point a = d.courtCorners[i];
                Point b = d.courtCorners[(i + 1) % 4];
                if (a != null && b != null) {
                    Imgproc.line(frame, a, b, courtC, 2, Imgproc.LINE_AA);
                }
            }
        }

        // 羽毛球轨迹（随播放进度逐步显示，绿）
        if (d.trajectory != null && d.trajectory.size() > 1) {
            Point prev = null;
            Point cur = null;
            for (int j = 0; j < d.trajectory.size(); j++) {
                Point p = d.trajectory.get(j);
                if (p == null) { prev = null; continue; }
                if (estFrame(j, d.shuttleSkip) <= curFrame) {
                    if (prev != null) Imgproc.line(frame, prev, p, trajC, 3, Imgproc.LINE_AA);
                    prev = p;
                    cur = p;
                } else {
                    break;
                }
            }
            // 当前球位置（亮点）
            if (cur != null) {
                Imgproc.circle(frame, cur, 7, ballC, -1, Imgproc.LINE_AA);
                Imgproc.circle(frame, cur, 11, ballC, 2, Imgproc.LINE_AA);
            }
        }

        // 运动员框（按帧同步，橙黄）
        if (d.playerTrack != null && !d.playerTrack.isEmpty()) {
            for (double[] e : d.playerTrack) {
                if (e == null || e.length < 5) continue;
                if (Math.abs(e[0] - curFrame) <= 2) {
                    Imgproc.rectangle(frame,
                            new Point(e[1], e[2]),
                            new Point(e[1] + e[3], e[2] + e[4]),
                            playerC, 2, Imgproc.LINE_AA);
                }
            }
        }
    }
}
