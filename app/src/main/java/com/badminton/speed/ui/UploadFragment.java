package com.badminton.speed.ui;
import androidx.core.view.GravityCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.badminton.speed.MainActivity;
import com.badminton.speed.R;
import com.badminton.speed.core.DetectPipeline;
import com.badminton.speed.core.PlayerDetector;
import com.badminton.speed.core.VideoPreValidator;
import com.badminton.speed.data.DetectResult;
import com.badminton.speed.data.HistoryDB;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 核心页面：上传视频 → 前置校验 → 开始测速 → 结果展示 → 保存历史。
 */
public class UploadFragment extends Fragment implements DetectPipeline.ProgressCallback {

    // UI
    private Button btnUpload, btnStartAnalyze, btnCancel;
    private FrameLayout leftCard;
    private LinearLayout uploadPlaceholder, bottomProgress;
    private FrameLayout processingView;
    private SurfaceView videoPreview;
    private OverlayView overlayView;
    private ProgressBar progressMain;
    private TextView tvProgressLabel, tvProgressPct, tvAnalyzing;
    private RecyclerView repoModules;
    private AlgoModuleAdapter adapter;

    // 视频播放
    private MediaPlayer mediaPlayer;
    private SurfaceHolder surfaceHolder;

    // 状态
    private String currentVideoPath;
    private VideoPreValidator.ValidationReport currentReport;
    private boolean isAnalyzing = false;
    private DetectPipeline pipeline;
    private DetectPipeline.PipelineResult lastResult;

    // 轨迹数据传递（避免 Bundle 序列化大对象）
    static List<double[]> sharedTrajectory3D;
    /** 全视频运动员框时间线 {frameIdx,x,y,w,h}，结果页播放视频时实时同步画框 */
    public static List<double[]> sharedPlayerTrack;
    /** 2D 羽毛球轨迹（视频帧像素坐标），结果页叠加显示 */
    public static List<org.opencv.core.Point> sharedTrajectory2D;
    /** 场地四角（视频帧像素坐标），结果页画黄色场地框 */
    public static org.opencv.core.Point[] sharedCourtCorners;
    /** 击球事件列表（每次击球的出拍速度/落地点/in/out），结果页展示 */
    public static List<com.badminton.speed.core.HitDetector.HitEvent> sharedHits;

    // ActivityResultLaunchers
    private final ActivityResultLauncher<String> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                copyToTempAndValidate(uri);
            });

    private final ActivityResultLauncher<String[]> requestPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (allPermGranted()) {
                    pickVideoLauncher.launch("video/*");
                } else {
                    Toast.makeText(requireContext(), "请授予存储权限", Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater infl, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = infl.inflate(R.layout.activity_upload, c, false);

        v.findViewById(R.id.btn_menu).setOnClickListener(view -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) act.getDrawer().openDrawer(GravityCompat.START);
        });

        btnUpload = v.findViewById(R.id.btn_upload);
        btnStartAnalyze = v.findViewById(R.id.btn_start_analyze);
        btnCancel = v.findViewById(R.id.btn_cancel);
        leftCard = v.findViewById(R.id.left_card);
        uploadPlaceholder = v.findViewById(R.id.upload_placeholder);
        processingView = v.findViewById(R.id.processing_view);
        videoPreview = v.findViewById(R.id.video_preview);
        overlayView = v.findViewById(R.id.overlay_view);
        bottomProgress = v.findViewById(R.id.bottom_progress);
        progressMain = v.findViewById(R.id.progress_main);
        tvProgressLabel = v.findViewById(R.id.tv_progress_label);
        tvProgressPct = v.findViewById(R.id.tv_progress_pct);
        tvAnalyzing = v.findViewById(R.id.tv_analyzing);
        repoModules = v.findViewById(R.id.repo_algo_modules);

        // 初始化 SurfaceView 播放
        surfaceHolder = videoPreview.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (mediaPlayer != null) mediaPlayer.setDisplay(holder);
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (mediaPlayer != null) { mediaPlayer.setDisplay(null); }
            }
        });

        btnUpload.setOnClickListener(view -> {
            if (allPermGranted()) pickVideoLauncher.launch("video/*");
            else requestPermLauncher.launch(permsToRequest());
        });

        btnStartAnalyze.setOnClickListener(view -> startAnalyzing());
        btnCancel.setOnClickListener(view -> cancelAnalyzing());

        // 初始化右侧算法模块
        List<AlgoModuleAdapter.ModuleItem> items = new ArrayList<>();
        items.add(makeItem("场地基准检测", "场地线清晰可见，无场地线的视频不能测速",
                new String[]{"Canny边缘检测", "霍夫直线变换", "RANSAC迭代拟合", "单应性矩阵计算"},
                "未检测", R.drawable.ic_module_court));
        items.add(makeItem("羽毛球检测", "确保视频中羽毛球拍摄清晰，不要和白色背景融合",
                new String[]{"背景差分", "轮廓检测", "圆形度筛选", "白色过滤"},
                "未检测", R.drawable.ic_module_shuttle));
        items.add(makeItem("人员检测", "调整拍摄角度，确保人员尽量不要互相遮挡",
                new String[]{"帧差计算", "运动区域提取", "轮廓筛选", "人员ID赋值"},
                "未检测", R.drawable.ic_module_player));
        items.add(makeItem("击球点检测", "尽量确保羽毛球始终在画面内，不要飞出画面",
                new String[]{"速度极值分析", "击球时刻识别"},
                "未检测", R.drawable.ic_module_hit));
        items.add(makeItem("计算球速", "切勿使用鱼眼镜头拍摄，画面畸变的视频无法测速",
                new String[]{"轨迹重建", "坐标转换", "速度计算"},
                "未检测", R.drawable.ic_module_speed));

        adapter = new AlgoModuleAdapter(items);
        repoModules.setLayoutManager(new LinearLayoutManager(requireContext()));
        repoModules.setAdapter(adapter);

        return v;
    }

    private AlgoModuleAdapter.ModuleItem makeItem(String title, String desc, String[] sub, String status, int iconRes) {
        AlgoModuleAdapter.ModuleItem m = new AlgoModuleAdapter.ModuleItem();
        m.title = title; m.desc = desc; m.subItems = sub; m.status = status; m.iconRes = iconRes;
        return m;
    }

    // ==================== 视频选择 + 校验 ====================

    private boolean allPermGranted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] permsToRequest() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private void copyToTempAndValidate(Uri uri) {
        new Thread(() -> {
            try {
                File tmp = new File(requireContext().getCacheDir(), "upload_video_" + System.currentTimeMillis() + ".mp4");
                try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
                     FileOutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
                currentVideoPath = tmp.getAbsolutePath();

                // 切换 UI：placeholder → processingView
                requireActivity().runOnUiThread(() -> {
                    uploadPlaceholder.setVisibility(View.GONE);
                    processingView.setVisibility(View.VISIBLE);
                    tvProgressLabel.setText("正在处理视频");
                    tvProgressPct.setText("0%");
                    progressMain.setProgress(10);
                });

                // 前置校验
                VideoPreValidator validator = new VideoPreValidator();
                VideoPreValidator.ValidationReport report = validator.validate(currentVideoPath);
                currentReport = report;

                // 更新 UI（显示元数据 + 校验结果）
                requireActivity().runOnUiThread(() -> updateUIAfterValidate(report));

            } catch (Throwable t) {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "处理视频失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void updateUIAfterValidate(VideoPreValidator.ValidationReport report) {
        // 设置叠加层的视频帧尺寸（用于坐标缩放映射）
        if (overlayView != null && report.width > 0 && report.height > 0) {
            overlayView.setFrameSize(report.width, report.height);
        }
        // 显示视频预览（第一帧）
        tryShowFirstFrame();

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("视频信息: %dx%d, %d FPS, %.1fs\n\n",
                report.width, report.height, report.fps, report.durationMs / 1000.0));

        if (!report.errors.isEmpty()) {
            msg.append("❌ 问题:\n");
            for (String e : report.errors) msg.append("   - ").append(e).append('\n');
            btnStartAnalyze.setEnabled(false);
        }
        if (!report.warnings.isEmpty()) {
            msg.append("⚠ 警告:\n");
            for (String w : report.warnings) msg.append("   - ").append(w).append('\n');
        }
        if (report.errors.isEmpty()) {
            msg.append("✅ 校验通过，可以开始测速");
            btnStartAnalyze.setEnabled(true);
            // 更新算法模块状态（全部5个模块变成可检测）
            for (int i = 0; i < adapter.items.size(); i++) adapter.updateStatus(adapter.items.get(i).title, 0, "就绪");
            progressMain.setProgress(100);
            tvProgressPct.setText("100%");
            tvProgressLabel.setText("前置校验完成");
        }

        // 弹窗提示校验结果
        new AlertDialog.Builder(requireContext())
                .setTitle("视频校验结果")
                .setMessage(msg.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    private void tryShowFirstFrame() {
        if (currentVideoPath == null) return;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(currentVideoPath);
            mediaPlayer.setDisplay(surfaceHolder);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                // 调整视频比例
                adjustVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> true);
        } catch (Throwable t) {
            android.util.Log.e("UploadFragment", "video play error", t);
        }
    }

    private void adjustVideoSize(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        ViewGroup.LayoutParams lp = videoPreview.getLayoutParams();
        FrameLayout parent = (FrameLayout) videoPreview.getParent();
        int pw = parent.getWidth(), ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;
        float scale = Math.min((float) pw / vw, (float) ph / vh);
        lp.width = (int) (vw * scale);
        lp.height = (int) (vh * scale);
        videoPreview.setLayoutParams(lp);
    }

    // ==================== 测速主流程 ====================

    private void startAnalyzing() {
        if (currentVideoPath == null) {
            Toast.makeText(requireContext(), "请先上传视频", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!com.badminton.speed.OpenCVInit.isLoaded()) {
            Toast.makeText(requireContext(), "OpenCV 未加载，请重启 APP", Toast.LENGTH_LONG).show();
            return;
        }

        isAnalyzing = true;
        btnStartAnalyze.setEnabled(false);
        uploadPlaceholder.setVisibility(View.GONE);
        processingView.setVisibility(View.VISIBLE);
        bottomProgress.setVisibility(View.VISIBLE);
        if (overlayView != null) overlayView.clearOverlay();

        pipeline = new DetectPipeline();
        pipeline.setContext(requireContext());
        new Thread(() -> {
            DetectPipeline.PipelineResult result = pipeline.run(currentVideoPath, this);
            requireActivity().runOnUiThread(() -> {
                isAnalyzing = false;
                bottomProgress.setVisibility(View.GONE);
                btnStartAnalyze.setEnabled(true);
                if (result.success) {
                    showResultAndSave(result);
                } else {
                    // 检测失败，显示错误但保留视频路径，允许重试
                    String msg = result.errorMessage != null ? result.errorMessage : "检测失败，请重试";
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                    processingView.setVisibility(View.GONE);
                    uploadPlaceholder.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void cancelAnalyzing() {
        if (pipeline != null) pipeline.cancel();
        isAnalyzing = false;
        bottomProgress.setVisibility(View.GONE);
        btnStartAnalyze.setEnabled(true);
    }

    private void showResultAndSave(DetectPipeline.PipelineResult result) {
        if (!isAdded()) return;

        lastResult = result;
        DetectResult dr = new DetectResult();
        dr.videoPath = currentVideoPath;
        dr.fps = currentReport != null ? currentReport.fps : 30;
        if (result.validation != null) {
            dr.width = result.validation.width;
            dr.height = result.validation.height;
            dr.courtOK = result.court != null;
            dr.shuttleOK = result.shuttleTrajectory != null && !result.shuttleTrajectory.isEmpty();
            dr.hitOK = result.hits != null && !result.hits.isEmpty();
            dr.speedOK = result.summary != null;
        }
        if (result.summary != null) {
            dr.maxSpeed = result.summary.maxSpeed;
            dr.avgSpeed = result.summary.avgSpeed;
            dr.hitType = result.summary.hitType;
            dr.inOut = result.summary.inOut;
            dr.shotSpeed = String.format("%.0f km/h (出拍)", result.summary.maxSpeed);
            dr.realtimeSpeed = result.summary.maxSpeed > 0 ? "进行中…" : "-";
        }
        try {
            if (result.success) {
                HistoryDB db = new HistoryDB(requireContext());
                dr.id = db.save(dr);
            }
        } catch (Exception e) {
            android.util.Log.w("UploadFragment", "DB save failed: " + e.getMessage());
        }

        try {
            // 跳转结果页
            ResultsFragment rf = new ResultsFragment();
            Bundle b = new Bundle();
            b.putDouble("maxSpeed", dr.maxSpeed);
            b.putDouble("avgSpeed", dr.avgSpeed);
            b.putString("hitType", dr.hitType);
            b.putString("inOut", dr.inOut);
            b.putString("shotSpeed", dr.shotSpeed);
            b.putString("videoPath", currentVideoPath);
            b.putBoolean("success", result.success);
            b.putString("error", result.errorMessage);
            b.putDouble("fps", dr.fps > 0 ? dr.fps : 30);
            rf.setArguments(b);

            // 传递轨迹 + 实时追踪数据
            sharedTrajectory3D = result.trajectory3D;
            sharedPlayerTrack = result.playerTrack;
            sharedTrajectory2D = result.shuttleTrajectory;
            sharedCourtCorners = result.court != null ? result.court.courtCorners : null;
            sharedHits = result.hits;

            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_container, rf)
                    .addToBackStack(null)
                    .commitAllowingStateLoss();
        } catch (Exception e) {
            android.util.Log.e("UploadFragment", "Fragment transaction failed", e);
            Toast.makeText(requireContext(), "结果页加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== Pipeline 回调 ====================

    @Override
    public void onModuleProgress(String moduleName, int pct, String status) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            try {
                progressMain.setProgress(Math.min(100, Math.max(progressMain.getProgress(), pct)));
                tvProgressPct.setText(pct + "%");
                tvProgressLabel.setText("正在 " + moduleName);
                if (adapter != null) adapter.updateStatus(moduleName, pct, status);
            } catch (Throwable t) {
                android.util.Log.w("UploadFragment", "onModuleProgress UI update failed: " + t.getMessage());
            }
        });
    }

    @Override
    public void onStageLabel(String label) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            try { tvProgressLabel.setText(label); } catch (Throwable ignored) {}
        });
    }

    @Override
    public void onFrameProcessed(int frameIndex, int totalFrames) {
        if (!isAdded()) return;
        if (frameIndex % Math.max(1, totalFrames / 20) != 0) return;
        int pct = 10 + (frameIndex * 80) / Math.max(1, totalFrames);
        requireActivity().runOnUiThread(() -> {
            try {
                progressMain.setProgress(Math.min(100, pct));
                tvProgressPct.setText(pct + "%");
            } catch (Throwable ignored) {}
        });
    }

    @Override
    public void onDone(DetectPipeline.PipelineResult result) { }

    @Override
    public void onError(String msg) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            try {
                Toast.makeText(requireContext(), "测速错误: " + msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        });
    }

    // ===== 实时叠加回调 =====

    @Override
    public void onCourtDetected(org.opencv.core.Point[] corners, org.opencv.core.Mat homography) {
        if (!isAdded() || overlayView == null) return;
        requireActivity().runOnUiThread(() -> {
            try {
                overlayView.setCourt(corners, homography);
            } catch (Throwable t) {
                android.util.Log.w("UploadFragment", "onCourtDetected failed: " + t.getMessage());
            }
        });
    }

    @Override
    public void onShuttleTrajectory(List<org.opencv.core.Point> trajectory) {
        if (!isAdded() || overlayView == null) return;
        requireActivity().runOnUiThread(() -> {
            try {
                overlayView.setTrajectory(trajectory);
            } catch (Throwable t) {
                android.util.Log.w("UploadFragment", "onShuttleTrajectory failed: " + t.getMessage());
            }
        });
    }

    @Override
    public void onPlayersDetected(List<PlayerDetector.PlayerBox> players, int hitterIndex) {
        if (!isAdded() || overlayView == null) return;
        requireActivity().runOnUiThread(() -> {
            try {
                List<android.graphics.RectF> boxes = new java.util.ArrayList<>();
                if (players != null) {
                    for (PlayerDetector.PlayerBox pb : players) {
                        if (pb != null && pb.rect != null) {
                            boxes.add(new android.graphics.RectF(
                                    pb.rect.x, pb.rect.y,
                                    pb.rect.x + pb.rect.width, pb.rect.y + pb.rect.height));
                        }
                    }
                }
                overlayView.setPlayers(boxes, hitterIndex);
            } catch (Throwable t) {
                android.util.Log.w("UploadFragment", "onPlayersDetected failed: " + t.getMessage());
            }
        });
    }

    @Override
    public void onSubStep(String moduleName, int subIndex, int status) {
        if (!isAdded() || adapter == null) return;
        requireActivity().runOnUiThread(() -> {
            try {
                adapter.updateSubStatus(moduleName, subIndex, status);
            } catch (Throwable t) {
                android.util.Log.w("UploadFragment", "onSubStep failed: " + t.getMessage());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 检查上次是否有闪退报告，有则弹窗展示
        String crash = com.badminton.speed.CrashHandler.getLastCrash(requireContext());
        if (crash != null) {
            com.badminton.speed.CrashHandler.clearLastCrash(requireContext());
            try {
                new AlertDialog.Builder(requireContext())
                        .setTitle("上次运行异常退出")
                        .setMessage("检测到崩溃日志：\n\n" + crash)
                        .setPositiveButton("知道了", null)
                        .show();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Throwable ignore) {}
            mediaPlayer = null;
        }
    }
}
