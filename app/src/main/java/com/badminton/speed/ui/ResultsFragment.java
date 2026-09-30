package com.badminton.speed.ui;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.badminton.speed.R;

import java.io.File;
import java.util.List;

/**
 * 测速结果页：视频播放器 + 底部控制条 + 右上角可拖可缩放 3D 面板。
 */
public class ResultsFragment extends Fragment implements SurfaceHolder.Callback {

    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
    private String videoPath;
    private boolean isPlaying = false;

    private FrameLayout panel3d;
    private TrajectoryView trajectoryView;
    private TextView tvInout, tvLiveSpeed, tvPlayIcon;
    private SeekBar seekBar;
    private OverlayView overlayView;

    // 实时追踪数据（来自 UploadFragment 静态共享）
    private List<double[]> playerTrack;
    private List<org.opencv.core.Point> sceneTrajectory;
    private org.opencv.core.Point[] courtCorners;
    private double fps = 30;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    // 拖动 3D 面板
    private float panelDx, panelDy;
    // 缩放 3D 面板
    private float panelScale = 1.0f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_results, c, false);

        videoPath = getArguments() != null ? getArguments().getString("videoPath") : null;
        double maxSpeed = getArguments() != null ? getArguments().getDouble("maxSpeed", 0) : 0;
        String inOut = getArguments() != null ? getArguments().getString("inOut", "未知") : null;
        fps = getArguments() != null ? getArguments().getDouble("fps", 30) : 30;
        if (fps <= 0) fps = 30;

        // 实时叠加层
        overlayView = v.findViewById(R.id.overlay_view);
        playerTrack = UploadFragment.sharedPlayerTrack;
        sceneTrajectory = UploadFragment.sharedTrajectory2D;
        courtCorners = UploadFragment.sharedCourtCorners;
        if (overlayView != null && courtCorners != null) {
            overlayView.setCourt(courtCorners, null);
        }
        if (overlayView != null && sceneTrajectory != null && !sceneTrajectory.isEmpty()) {
            overlayView.setTrajectory(sceneTrajectory);
        }

        // 视频播放
        surfaceView = v.findViewById(R.id.video_view);
        surfaceView.getHolder().addCallback(this);

        // 返回按钮
        v.findViewById(R.id.btn_back).setOnClickListener(view ->
                requireActivity().getSupportFragmentManager().popBackStack());

        // 3D 面板
        panel3d = v.findViewById(R.id.panel_3d);
        trajectoryView = v.findViewById(R.id.trajectory_3d);
        tvInout = v.findViewById(R.id.tv_inout);
        tvLiveSpeed = v.findViewById(R.id.tv_live_speed);

        tvInout.setText(inOut != null && inOut.contains("界内") ? "IN" : (inOut != null && inOut.contains("界外") ? "OUT" : "—"));
        tvLiveSpeed.setText(maxSpeed > 0 ? (int) maxSpeed + "" : "—");

        // 3D 轨迹
        List<double[]> traj = UploadFragment.sharedTrajectory3D;
        if (trajectoryView != null && traj != null && !traj.isEmpty()) {
            trajectoryView.setTrajectory(traj);
        }

        // 拖动 3D 面板
        panel3d.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    panelDx = event.getRawX() - view.getX();
                    panelDy = event.getRawY() - view.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.setX(event.getRawX() - panelDx);
                    view.setY(event.getRawY() - panelDy);
                    return true;
            }
            return false;
        });

        // 长按缩放 3D 面板
        panel3d.setOnLongClickListener(view -> {
            panelScale = panelScale > 1.0f ? 1.0f : 1.3f;
            view.setScaleX(panelScale);
            view.setScaleY(panelScale);
            return true;
        });

        // 底部控制
        seekBar = v.findViewById(R.id.seek_bar);
        tvPlayIcon = v.findViewById(R.id.tv_play_icon);
        LinearLayout btnPlay = v.findViewById(R.id.btn_play);
        LinearLayout btnPrev = v.findViewById(R.id.btn_prev_frame);
        LinearLayout btnNext = v.findViewById(R.id.btn_next_frame);
        LinearLayout btnTrajFx = v.findViewById(R.id.btn_traj_fx);
        LinearLayout btnDownload = v.findViewById(R.id.btn_download);

        btnPlay.setOnClickListener(view -> togglePlay());
        btnPrev.setOnClickListener(view -> seekFrame(-1));
        btnNext.setOnClickListener(view -> seekFrame(1));
        btnTrajFx.setOnClickListener(view -> {
            if (trajectoryView != null) {
                trajectoryView.setVisibility(
                        trajectoryView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        btnDownload.setOnClickListener(view -> {
            android.widget.Toast.makeText(getContext(), "已保存到相册", android.widget.Toast.LENGTH_SHORT).show();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    try { mediaPlayer.seekTo(p); syncOverlay(p); } catch (Exception ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        return v;
    }

    private void togglePlay() {
        if (mediaPlayer == null) return;
        try {
            if (isPlaying) {
                mediaPlayer.pause();
                tvPlayIcon.setText("▶");
            } else {
                mediaPlayer.start();
                tvPlayIcon.setText("⏸");
            }
            isPlaying = !isPlaying;
        } catch (Exception ignored) {}
    }

    private void seekFrame(int dir) {
        if (mediaPlayer == null) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            int fps = 30;
            int frameMs = 1000 / fps;
            mediaPlayer.seekTo(Math.max(0, pos + dir * frameMs));
        } catch (Exception ignored) {}
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (videoPath == null) return;
        try {
            mediaPlayer = new MediaPlayer();
            // 兼容 content:// 和 file:// 以及普通路径
            Uri uri;
            if (videoPath.startsWith("content://") || videoPath.startsWith("file://")) {
                uri = Uri.parse(videoPath);
            } else {
                uri = Uri.fromFile(new File(videoPath));
            }
            mediaPlayer.setDataSource(requireContext(), uri);
            mediaPlayer.setDisplay(holder);
            // 等比缩放（letterbox），与 OverlayView 的坐标换算保持一致
            try { mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); } catch (Exception ignored) {}
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    if (seekBar != null) seekBar.setMax(mp.getDuration());
                    if (overlayView != null) {
                        overlayView.setFrameSize(mp.getVideoWidth(), mp.getVideoHeight());
                        syncOverlay(0);
                    }
                    mp.start();
                    isPlaying = true;
                    if (tvPlayIcon != null) tvPlayIcon.setText("⏸");
                    startProgressLoop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                if (tvPlayIcon != null) tvPlayIcon.setText("▶");
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 用 Handler 在主线程更新进度条 + 运动员框实时同步（避免子线程操作 UI 闪退） */
    private void startProgressLoop() {
        stopProgressLoop();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying && seekBar != null) {
                    try {
                        int cur = mediaPlayer.getCurrentPosition();
                        seekBar.setProgress(cur);
                        syncOverlay(cur);
                    } catch (Exception ignored) {}
                    uiHandler.postDelayed(this, 100);
                }
            }
        };
        uiHandler.postDelayed(progressRunnable, 100);
    }

    /** 按播放位置（毫秒）找到最近的追踪帧，把该帧的运动员框画到叠加层上 → 框随视频实时运动 */
    private void syncOverlay(int posMs) {
        if (overlayView == null || playerTrack == null || playerTrack.isEmpty()) return;
        double targetFrame = posMs / 1000.0 * fps;

        // 找最近帧
        double nearest = Double.MAX_VALUE;
        for (double[] e : playerTrack) {
            double d = Math.abs(e[0] - targetFrame);
            if (d < nearest) nearest = d;
        }
        if (nearest > 4) return; // 超过 4 帧没有追踪数据就不画，避免乱跳

        List<android.graphics.RectF> boxes = new java.util.ArrayList<>();
        for (double[] e : playerTrack) {
            if (Math.abs(e[0] - targetFrame) <= 2) { // 相邻帧的框一起画，平滑过渡
                boxes.add(new android.graphics.RectF(
                        (float) e[1], (float) e[2],
                        (float) (e[1] + e[3]), (float) (e[2] + e[4])));
            }
        }
        overlayView.setPlayers(boxes, -1);
    }

    private void stopProgressLoop() {
        if (progressRunnable != null) {
            uiHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int h2) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        releasePlayer();
    }

    private void releasePlayer() {
        stopProgressLoop();
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        releasePlayer();
    }
}

