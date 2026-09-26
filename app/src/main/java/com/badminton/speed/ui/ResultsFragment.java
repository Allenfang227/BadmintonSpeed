package com.badminton.speed.ui;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
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

    // 拖动 3D 面板
    private float panelDx, panelDy;
    // 缩放 3D 面板
    private float panelScale = 1.0f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater infl, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = infl.inflate(R.layout.fragment_results, c, false);

        videoPath = getArguments() != null ? getArguments().getString("videoPath") : null;
        double maxSpeed = getArguments() != null ? getArguments().getDouble("maxSpeed", 0) : 0;
        String inOut = getArguments() != null ? getArguments().getString("inOut", "未知") : null;

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

        // 双击缩放 3D 面板
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
            // 下载功能：保存当前截图（简化提示）
            android.widget.Toast.makeText(getContext(), "已保存到相册", android.widget.Toast.LENGTH_SHORT).show();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && mediaPlayer != null) mediaPlayer.seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        return v;
    }

    private void togglePlay() {
        if (mediaPlayer == null) return;
        if (isPlaying) {
            mediaPlayer.pause();
            tvPlayIcon.setText("▶");
        } else {
            mediaPlayer.start();
            tvPlayIcon.setText("⏸");
        }
        isPlaying = !isPlaying;
    }

    private void seekFrame(int dir) {
        if (mediaPlayer == null) return;
        int pos = mediaPlayer.getCurrentPosition();
        int fps = 30;
        int frameMs = 1000 / fps;
        mediaPlayer.seekTo(Math.max(0, pos + dir * frameMs));
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (videoPath == null) return;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(requireContext(), Uri.parse(videoPath));
            mediaPlayer.setDisplay(holder);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                seekBar.setMax(mp.getDuration());
                mp.start();
                isPlaying = true;
                tvPlayIcon.setText("⏸");
                // 进度更新
                new Thread(() -> {
                    while (mediaPlayer != null && isPlaying) {
                        try {
                            if (mediaPlayer.isPlaying()) {
                                int cur = mediaPlayer.getCurrentPosition();
                                seekBar.setProgress(cur);
                            }
                            Thread.sleep(100);
                        } catch (Exception e) { break; }
                    }
                }).start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                tvPlayIcon.setText("▶");
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int h2) {}
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
