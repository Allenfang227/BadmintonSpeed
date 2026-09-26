package com.badminton.speed.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;

import com.badminton.speed.MainActivity;
import com.badminton.speed.R;

import java.util.List;

/**
 * 测速结果展示页：最高速度仪表盘 + 详细数据 + 模块状态。
 */
public class ResultsFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater infl, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = infl.inflate(R.layout.fragment_results, c, false);

        v.findViewById(R.id.btn_menu).setOnClickListener(view -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) act.getDrawer().openDrawer(GravityCompat.START);
        });

        Bundle args = getArguments();
        double maxSpeed = args != null ? args.getDouble("maxSpeed", 0) : 0;
        double avgSpeed = args != null ? args.getDouble("avgSpeed", 0) : 0;
        String hitType = args != null ? args.getString("hitType", "-") : "-";
        String inOut = args != null ? args.getString("inOut", "未知") : "未知";
        String shotSpeed = args != null ? args.getString("shotSpeed", "-") : "-";
        boolean success = args != null ? args.getBoolean("success", false) : false;
        String error = args != null ? args.getString("error", "") : "";

        TextView tvMax = v.findViewById(R.id.tv_max_speed);
        TextView tvAvg = v.findViewById(R.id.tv_avg_speed);
        TextView tvHitType = v.findViewById(R.id.tv_hit_type);
        TextView tvInOut = v.findViewById(R.id.tv_in_out);
        TextView tvShot = v.findViewById(R.id.tv_shot_speed);
        TextView tvModuleStatus = v.findViewById(R.id.tv_module_status);
        TrajectoryView trajectoryView = v.findViewById(R.id.trajectory_view);

        // 显示轨迹
        List<double[]> traj = UploadFragment.sharedTrajectory3D;
        if (trajectoryView != null && traj != null && !traj.isEmpty()) {
            trajectoryView.setTrajectory(traj);
        }

        // 读取设置项控制显示
        android.content.SharedPreferences sp = requireContext().getSharedPreferences("settings", 0);
        boolean showVirtualCourt = sp.getBoolean("virtual_court", true);
        String d1 = sp.getString("d1", "inout");
        String d2 = sp.getString("d2", "realtime");

        // 虚拟场地轨迹视图
        if (trajectoryView != null) {
            trajectoryView.setVisibility(showVirtualCourt ? View.VISIBLE : View.GONE);
        }

        // 数据1 / 数据2 选中项影响显示
        View hitTypeRow = v.findViewById(R.id.row_hit_type);
        View inOutRow = v.findViewById(R.id.row_in_out);
        View shotSpeedRow = v.findViewById(R.id.row_shot_speed);

        // 只要在 d1 或 d2 中被选中就显示
        boolean showHit = "hit".equals(d1) || "hit".equals(d2);
        boolean showInOut = "inout".equals(d1) || "inout".equals(d2);
        boolean showShot = "shot".equals(d1) || "shot".equals(d2);
        // 实时速度对应 avg speed 行（已有）
        boolean showRealtime = "realtime".equals(d1) || "realtime".equals(d2);

        if (hitTypeRow != null) hitTypeRow.setVisibility(showHit ? View.VISIBLE : View.GONE);
        if (inOutRow != null) inOutRow.setVisibility(showInOut ? View.VISIBLE : View.GONE);
        if (shotSpeedRow != null) shotSpeedRow.setVisibility(showShot ? View.VISIBLE : View.GONE);

        tvMax.setText(String.format("%.0f", maxSpeed));
        tvAvg.setText(String.format("%.0f", avgSpeed));
        tvHitType.setText(hitType != null ? hitType : "-");
        tvShot.setText(shotSpeed != null ? shotSpeed : "-");

        if (inOut != null && inOut.contains("界内")) {
            tvInOut.setText("✅ " + inOut);
            tvInOut.setTextColor(Color.parseColor("#39FF14"));
        } else if (inOut != null && inOut.contains("界外")) {
            tvInOut.setText("❌ " + inOut);
            tvInOut.setTextColor(Color.parseColor("#FF4444"));
        } else {
            tvInOut.setText(inOut);
            tvInOut.setTextColor(Color.parseColor("#7A8A80"));
        }

        if (success) {
            tvModuleStatus.setText(
                    "场地基准检测  —— 通过\n" +
                    "羽毛球检测   —— 通过\n" +
                    "人员检测     —— 通过\n" +
                    "击球点检测   —— " + (hitType != null && !hitType.equals("-") ? "通过" : "未检测到") + "\n" +
                    "球速计算     —— 通过\n\n" +
                    "所有计算均在本地完成，未上传任何视频数据。");
        } else {
            tvModuleStatus.setText("测速失败: " + (error != null ? error : "未知错误"));
            tvModuleStatus.setTextColor(Color.parseColor("#FF4444"));
        }

        Button btnBack = v.findViewById(R.id.btn_back_upload);
        Button btnHistory = v.findViewById(R.id.btn_view_history);

        btnBack.setOnClickListener(view -> {
            // 返回上传页
            requireActivity().getSupportFragmentManager().popBackStack();
        });

        btnHistory.setOnClickListener(view -> {
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_container, new HistoryFragment())
                    .addToBackStack(null)
                    .commit();
        });

        return v;
    }
}
