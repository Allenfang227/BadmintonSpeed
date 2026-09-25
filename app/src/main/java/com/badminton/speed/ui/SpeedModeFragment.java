package com.badminton.speed.ui;
import androidx.core.view.GravityCompat;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.badminton.speed.MainActivity;
import com.badminton.speed.R;
import com.google.android.material.button.MaterialButton;

/**
 * 测速模式选择：实时测速（开发中） / 上传视频测速（可用）。
 */
public class SpeedModeFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater infl, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = infl.inflate(R.layout.activity_speed_mode, c, false);

        v.findViewById(R.id.btn_menu).setOnClickListener(view -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) act.getDrawer().openDrawer(GravityCompat.START);
        });

        // 实时测速：开发中，不可点
        v.findViewById(R.id.btn_realtime).setOnClickListener(view -> {
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("提示")
                    .setMessage("实时测速正在开发中，敬请期待。\n\n请使用「上传视频测速」体验完整功能。")
                    .setPositiveButton("好的", null)
                    .show();
        });

        // 上传视频：跳转到 UploadFragment
        v.findViewById(R.id.btn_upload_enter).setOnClickListener(view -> {
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_container, new UploadFragment())
                    .addToBackStack(null)
                    .commit();
        });

        return v;
    }
}
