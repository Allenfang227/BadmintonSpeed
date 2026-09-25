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
