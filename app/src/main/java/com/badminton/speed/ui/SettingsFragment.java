package com.badminton.speed.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;

import com.badminton.speed.MainActivity;
import com.badminton.speed.R;

/**
 * 画面设置页：虚拟场地开关 + 数据1/数据2 按钮组。
 * 所有设置保存到 SharedPreferences，结果页读取后生效。
 */
public class SettingsFragment extends Fragment {

    private static final String PREFS = "settings";

    // 数据项标识
    private static final String SHOT = "shot";
    private static final String REALTIME = "realtime";
    private static final String HIT = "hit";
    private static final String INOUT = "inout";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater infl, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = infl.inflate(R.layout.fragment_settings, c, false);

        // 菜单按钮
        v.findViewById(R.id.btn_menu).setOnClickListener(view -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) act.getDrawer().openDrawer(GravityCompat.START);
        });

        SharedPreferences sp = requireContext().getSharedPreferences(PREFS, 0);

        // ===== 虚拟场地开关 =====
        Switch swVirtual = v.findViewById(R.id.sw_virtual_court);
        swVirtual.setChecked(sp.getBoolean("virtual_court", true));
        swVirtual.setOnCheckedChangeListener((b, checked) ->
                sp.edit().putBoolean("virtual_court", checked).apply());

        // ===== 数据1 按钮组（单选：同一组只能选一个） =====
        setupDataGroup(sp, v, "d1",
                R.id.btn_d1_shot, SHOT,
                R.id.btn_d1_realtime, REALTIME,
                R.id.btn_d1_hit, HIT,
                R.id.btn_d1_inout, INOUT);

        // ===== 数据2 按钮组 =====
        setupDataGroup(sp, v, "d2",
                R.id.btn_d2_shot, SHOT,
                R.id.btn_d2_realtime, REALTIME,
                R.id.btn_d2_hit, HIT,
                R.id.btn_d2_inout, INOUT);

        return v;
    }

    /**
     * 设置一组数据按钮（单选行为）。
     */
    private void setupDataGroup(SharedPreferences sp, View v, String group,
                                int shotId, String shotKey,
                                int realtimeId, String realtimeKey,
                                int hitId, String hitKey,
                                int inoutId, String inoutKey) {

        Button btnShot = v.findViewById(shotId);
        Button btnRealtime = v.findViewById(realtimeId);
        Button btnHit = v.findViewById(hitId);
        Button btnInout = v.findViewById(inoutId);

        String current = sp.getString(group, group.equals("d1") ? INOUT : REALTIME);

        // 初始化选中状态
        updateSelected(btnShot, SHOT.equals(current));
        updateSelected(btnRealtime, REALTIME.equals(current));
        updateSelected(btnHit, HIT.equals(current));
        updateSelected(btnInout, INOUT.equals(current));

        View.OnClickListener listener = view -> {
            String selected = (String) view.getTag();
            sp.edit().putString(group, selected).apply();

            updateSelected(btnShot, SHOT.equals(selected));
            updateSelected(btnRealtime, REALTIME.equals(selected));
            updateSelected(btnHit, HIT.equals(selected));
            updateSelected(btnInout, INOUT.equals(selected));
        };

        btnShot.setTag(shotKey);
        btnRealtime.setTag(realtimeKey);
        btnHit.setTag(hitKey);
        btnInout.setTag(inoutKey);

        btnShot.setOnClickListener(listener);
        btnRealtime.setOnClickListener(listener);
        btnHit.setOnClickListener(listener);
        btnInout.setOnClickListener(listener);
    }

    private void updateSelected(Button btn, boolean selected) {
        btn.setSelected(selected);
    }
}
