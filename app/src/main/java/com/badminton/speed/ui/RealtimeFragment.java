package com.badminton.speed.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.badminton.speed.R;
import com.badminton.speed.core.CourtDetector;
import com.badminton.speed.core.PlayerDetector;
import com.badminton.speed.core.ShuttleDetector;
import com.badminton.speed.core.SpeedCalculator;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 实时测速（v2.46）：CameraX 预览 + 逐帧分析。
 *
 * 数据流：CameraX ImageAnalysis（后台线程，KEEP_ONLY_LATSTE）
 *   → ImageProxy 转 BGR Mat → 旋转校正 → 降采样 480px
 *   → CourtDetector（前若干帧 + 定期重试）/ ShuttleDetector（每帧）/ PlayerDetector（每4帧）
 *   → 滚动窗口单应测速 + 速度历史峰值计数
 *   → 限流 100ms 推到主线程：OverlayView 叠加 + 速度读数。
 *
 * 线程纪律：OpenCV Mat 全部只在分析线程创建/使用；跨线程只传不可变快照
 * （Point[] 角点、clone 的 H、List 副本），避免同一 native 对象被两线程并发访问。
 */
public class RealtimeFragment extends Fragment {

    private static final String TAG = "RealtimeFragment";
    private static final int ANALYSIS_W = 480;   // 分析分辨率宽度
    private static final double ANALYSIS_FPS = 30.0;
    private static final int TRAJECTORY_SHOW = 40;   // 叠加层显示的轨迹点数
    private static final int SPEED_WINDOW = 12;      // 测速滚动窗口（点）
    private static final double MAX_PLAUSIBLE_KMH = 600.0;
    private static final long UI_PUSH_INTERVAL_MS = 100;

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvSpeedValue, tvSpeedMax, tvHitCount, tvStatus, tvHint;

    private ExecutorService analysisExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    // ==== 检测器（仅分析线程访问）====
    private ShuttleDetector shuttleDet;
    private PlayerDetector playerDet;
    private CourtDetector courtDet;
    private SpeedCalculator speedCalc;

    // ==== 分析线程独占状态 ====
    private volatile int frameCount = 0;
    private volatile int frameW = ANALYSIS_W;        // 当前分析帧尺寸（旋转校正后）
    private volatile int frameH = 640;
    private int lastDetectFrame = -1;     // 上次检出羽毛球的帧号
    private long lastCourtAttempt = 0;
    private Mat courtH = null;            // 分析坐标单应矩阵（原始，仅分析线程用）
    private final List<Point> speedWindow = new ArrayList<>(); // 滚动窗口（分析坐标）
    private final List<Point> displayTrail = new ArrayList<>(); // 叠加层显示轨迹（分析坐标）
    private final List<Double> speedHistory = new ArrayList<>(); // 全量速度历史（计数峰值用）

    // ==== 跨线程快照（volatile，发布后只读）====
    private volatile Point[] courtCornersSnap = null;
    private volatile Mat courtHClone = null;
    private volatile List<Point> trajSnap = new ArrayList<>();
    private volatile List<RectF> playerSnap = new ArrayList<>();
    private volatile double curSpeed = 0;
    private volatile double maxSpeed = 0;
    private volatile int hitCount = 0;
    private volatile boolean courtReady = false;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!isAdded()) return;
                if (granted) startCamera();
                else {
                    Toast.makeText(requireContext(), "需要相机权限才能实时测速", Toast.LENGTH_SHORT).show();
                    requireActivity().getSupportFragmentManager().popBackStack();
                }
            });

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_realtime, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        previewView = view.findViewById(R.id.preview_view);
        overlayView = view.findViewById(R.id.overlay_view);
        tvSpeedValue = view.findViewById(R.id.tv_speed_value);
        tvSpeedMax = view.findViewById(R.id.tv_speed_max);
        tvHitCount = view.findViewById(R.id.tv_hit_count);
        tvStatus = view.findViewById(R.id.tv_status);
        tvHint = view.findViewById(R.id.tv_hint);

        view.findViewById(R.id.btn_close).setOnClickListener(v -> closePage());
        view.findViewById(R.id.btn_finish).setOnClickListener(v -> showResult());

        analysisExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        if (!isAdded()) return;
        shuttleDet = new ShuttleDetector();
        playerDet = new PlayerDetector();
        courtDet = new CourtDetector();
        speedCalc = new SpeedCalculator();

        ProcessCameraProvider.getInstance(requireContext()).addListener(() -> {
            // 视图可能已在初始化期间销毁：无视图/无执行器时直接放弃
            if (!isAdded() || previewView == null || analysisExecutor == null) return;
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(requireContext()).get();
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetResolution(new Size(640, ANALYSIS_W)) // 4:3，与预览比例一致
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                running = true;
            } catch (Exception e) {
                if (isAdded()) {
                    Toast.makeText(requireContext(), "相机启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    requireActivity().getSupportFragmentManager().popBackStack();
                }
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    // ==== 逐帧分析（后台线程）====

    private void analyzeFrame(@NonNull ImageProxy image) {
        if (!running) {
            image.close();
            return;
        }
        Mat bgr = null;
        Mat work = null;
        try {
            bgr = imageToBgr(image);
            if (bgr == null || bgr.empty()) return;

            // 旋转校正：与预览显示方向一致
            int rot = image.getImageInfo().getRotationDegrees();
            if (rot != 0) {
                Mat rotated = new Mat();
                if (rot == 90) Core.rotate(bgr, rotated, Core.ROTATE_90_CLOCKWISE);
                else if (rot == 180) Core.rotate(bgr, rotated, Core.ROTATE_180);
                else if (rot == 270) Core.rotate(bgr, rotated, Core.ROTATE_90_COUNTERCLOCKWISE);
                else { bgr.copyTo(rotated); }
                bgr.release();
                bgr = rotated;
            }

            // 降采样到分析分辨率
            work = downscale(bgr, ANALYSIS_W);
            frameCount++;
            frameW = work.cols();
            frameH = work.rows();

            // 1) 场地检测：前 12 帧密集尝试，之后每 90 帧重试直到成功
            if (frameCount <= 12 || (courtH == null && frameCount - lastCourtAttempt > 90)) {
                lastCourtAttempt = frameCount;
                try {
                    CourtDetector.CourtResult cr = courtDet.detect(work);
                    if (cr.ok && cr.homography != null && !cr.homography.empty()) {
                        if (courtH != null) courtH.release();
                        courtH = cr.homography;                       // 原始 H：仅分析线程使用
                        if (courtHClone != null) courtHClone.release();
                        courtHClone = courtH.clone();                 // 克隆给 UI 画网格，避免并发访问
                        courtCornersSnap = cr.courtCorners;
                        courtReady = true;
                    }
                    if (cr.homographyInv != null) cr.homographyInv.release();
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "court detect failed: " + t.getMessage());
                }
            }

            // 2) 羽毛球检测（每帧）
            Point pt = null;
            try {
                pt = shuttleDet.detect(work);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "shuttle detect failed: " + t.getMessage());
            }

            // 3) 人员检测（每 4 帧）
            if (frameCount % 4 == 0) {
                try {
                    List<RectF> boxes = new ArrayList<>();
                    List<PlayerDetector.PlayerBox> pbs = playerDet.detect(work);
                    if (pbs != null) {
                        for (PlayerDetector.PlayerBox pb : pbs) {
                            if (pb == null || pb.rect == null) continue;
                            boxes.add(new RectF(pb.rect.x, pb.rect.y,
                                    pb.rect.x + pb.rect.width, pb.rect.y + pb.rect.height));
                        }
                    }
                    playerSnap = boxes;
                } catch (Throwable ignored) {}
            }

            // 4) 实时测速：滚动窗口 + 全量历史
            double vNow = 0;
            if (pt != null) {
                speedWindow.add(pt);
                while (speedWindow.size() > SPEED_WINDOW) speedWindow.remove(0);
                displayTrail.add(pt);
                while (displayTrail.size() > TRAJECTORY_SHOW) displayTrail.remove(0);

                int gap = lastDetectFrame >= 0 ? frameCount - lastDetectFrame : 1;
                lastDetectFrame = frameCount;
                if (courtH != null && speedWindow.size() >= 2) {
                    Point a = speedCalc.warpPointPublic(courtH, speedWindow.get(speedWindow.size() - 2));
                    Point b = speedCalc.warpPointPublic(courtH, speedWindow.get(speedWindow.size() - 1));
                    if (a != null && b != null) {
                        double dt = Math.max(1, gap) / ANALYSIS_FPS;
                        double v = Math.hypot(b.x - a.x, b.y - a.y) / dt * 3.6;
                        if (v <= MAX_PLAUSIBLE_KMH) vNow = v;
                    }
                }
            }
            speedHistory.add(vNow);
            if (speedHistory.size() > 3600) speedHistory.remove(0); // 约 2 分钟，防无限增长
            curSpeed = vNow;
            if (vNow > maxSpeed) maxSpeed = vNow;

            // 发布轨迹快照（新 List，UI 线程只读，避免并发修改）
            trajSnap = new ArrayList<>(displayTrail);

            // 5) 峰值计数 = 击球次数（每 10 帧重算，O(n) 可忽略）
            if (frameCount % 10 == 0) {
                hitCount = countPeaks(speedHistory);
            }

            // 6) 限流推 UI
            mainHandler.removeCallbacks(uiPush);
            mainHandler.postDelayed(uiPush, UI_PUSH_INTERVAL_MS);

        } catch (Throwable t) {
            android.util.Log.e(TAG, "analyzeFrame failed", t);
        } finally {
            if (work != null) work.release();
            if (bgr != null) bgr.release();
            image.close();
        }
    }

    private final Runnable uiPush = this::pushUi;

    private void pushUi() {
        if (!isAdded() || !running) return;
        overlayView.setFrameSize(frameW, frameH);
        overlayView.setCourt(courtCornersSnap, courtHClone);
        overlayView.setTrajectory(trajSnap);
        overlayView.setPlayers(playerSnap, -1);

        tvSpeedValue.setText(String.valueOf((int) curSpeed));
        tvSpeedMax.setText(getString(R.string.realtime_speed_max) + " " + (int) maxSpeed + " km/h");
        tvHitCount.setText(getString(R.string.realtime_hits) + " " + hitCount);

        if (courtReady) {
            tvStatus.setText(R.string.realtime_status_ready);
            tvHint.setText(R.string.realtime_hint);
        } else if (frameCount > 60) {
            tvStatus.setText(R.string.realtime_status_court);
            tvHint.setText(R.string.realtime_no_court);
        }
    }

    /** 速度历史峰值计数（局部极大 + 最小幅值 + 邻近去重），对应击球次数。 */
    private static int countPeaks(List<Double> speeds) {
        int n = speeds.size();
        if (n < 6) return 0;
        int count = 0;
        int lastPeak = -10;
        double minPeak = 40.0; // km/h
        for (int i = 2; i < n - 2; i++) {
            double sp = speeds.get(i);
            if (sp < minPeak) continue;
            if (sp >= speeds.get(i - 1) && sp >= speeds.get(i + 1)
                    && sp > speeds.get(i - 2) && sp > speeds.get(i + 2)) {
                if (i - lastPeak >= 3) {
                    count++;
                    lastPeak = i;
                }
            }
        }
        return count;
    }

    // ==== 图像工具 ====

    /** ImageProxy(YUV_420_888) → BGR Mat。失败返回 null（调用方判空）。 */
    private static Mat imageToBgr(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            int w = image.getWidth(), h = image.getHeight();
            ByteBuffer yBuf = planes[0].getBuffer();
            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();

            int yRowStride = planes[0].getRowStride();
            int yPixStride = planes[0].getPixelStride();
            int uRowStride = planes[1].getRowStride();
            int uPixStride = planes[1].getPixelStride();
            int vRowStride = planes[2].getRowStride();
            int vPixStride = planes[2].getPixelStride();

            byte[] nv21 = new byte[w * h * 3 / 2];

            // Y 平面（含 rowStride  padding 时逐行拷）
            if (yRowStride == w && yPixStride == 1) {
                yBuf.get(nv21, 0, w * h);
            } else {
                int pos = 0;
                for (int row = 0; row < h; row++) {
                    int base = row * yRowStride;
                    for (int col = 0; col < w; col++) {
                        nv21[pos++] = yBuf.get(base + col * yPixStride);
                    }
                }
            }

            // VU 交错（NV21 序：先 V 后 U）
            int uvPos = w * h;
            for (int row = 0; row < h / 2; row++) {
                int vBase = row * vRowStride;
                int uBase = row * uRowStride;
                for (int col = 0; col < w / 2; col++) {
                    nv21[uvPos++] = vBuf.get(vBase + col * vPixStride);
                    nv21[uvPos++] = uBuf.get(uBase + col * uPixStride);
                }
            }

            Mat yuv = new Mat(h + h / 2, w, CvType.CV_8UC1);
            yuv.put(0, 0, nv21);
            Mat rgba = new Mat();
            Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21);
            yuv.release();
            Mat bgr = new Mat();
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            rgba.release();
            return bgr;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "imageToBgr failed", t);
            return null;
        }
    }

    /** 等比降采样到目标宽度（只缩不放）。 */
    private static Mat downscale(Mat src, int targetW) {
        if (src.cols() <= targetW) return src.clone();
        double s = (double) targetW / src.cols();
        Mat out = new Mat();
        Imgproc.resize(src, out, new org.opencv.core.Size(targetW, Math.max(1, Math.round(src.rows() * s))));
        return out;
    }

    // ==== 结果与生命周期 ====

    private void showResult() {
        String msg;
        if (maxSpeed > 0) {
            msg = "最高球速：" + (int) maxSpeed + " km/h\n击球次数：" + hitCount + " 次";
        } else {
            msg = getString(R.string.realtime_no_data);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.realtime_result_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void closePage() {
        running = false;
        mainHandler.removeCallbacks(uiPush);
        try {
            ProcessCameraProvider.getInstance(requireContext()).get().unbindAll();
        } catch (Exception ignored) {}
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    @Override
    public void onDestroyView() {
        running = false;
        mainHandler.removeCallbacks(uiPush);
        if (overlayView != null) overlayView.clearOverlay();
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            analysisExecutor = null;
        }
        // 释放分析线程持有的 native 资源（UI 线程执行，无并发绘制）
        if (courtH != null) { courtH.release(); courtH = null; }
        if (courtHClone != null) { courtHClone.release(); courtHClone = null; }
        if (shuttleDet != null) { shuttleDet.reset(); shuttleDet = null; }
        if (playerDet != null) { playerDet.reset(); playerDet = null; }
        super.onDestroyView();
    }
}
