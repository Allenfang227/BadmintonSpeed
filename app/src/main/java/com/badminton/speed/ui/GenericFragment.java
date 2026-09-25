package com.badminton.speed.ui;
import androidx.core.view.GravityCompat;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.badminton.speed.MainActivity;
import com.badminton.speed.R;
import com.badminton.speed.data.HistoryDB;

/**
 * 统一壳：根据 show 参数显示不同内容。
 */
public class GenericFragment extends Fragment {

    public static final String ARG_SHOW = "arg_show";
    public static final String SHOW_HISTORY = "history";
    public static final String SHOW_SETTINGS = "settings";
    public static final String SHOW_TUTORIAL = "tutorial";
    public static final String SHOW_MINE = "mine";
    public static final String SHOW_ABOUT = "about";

    private RecyclerView repoHistory;
    private LinearLayout settingsList, aboutContent, historyEmpty, historyTools;
    private ScrollView tutorialContent;
    private TextView pageTitle;

    public static GenericFragment of(String show) {
        GenericFragment f = new GenericFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SHOW, show);
        f.setArguments(b);
        return f;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater infl, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = infl.inflate(R.layout.activity_generic, c, false);

        v.findViewById(R.id.btn_menu).setOnClickListener(view -> {
            MainActivity act = (MainActivity) getActivity();
            if (act != null) act.getDrawer().openDrawer(GravityCompat.START);
        });

        pageTitle = v.findViewById(R.id.page_title);
        repoHistory = v.findViewById(R.id.repo_history);
        settingsList = v.findViewById(R.id.settings_list);
        aboutContent = v.findViewById(R.id.about_content);
        historyEmpty = v.findViewById(R.id.history_empty);
        historyTools = v.findViewById(R.id.history_tools);
        tutorialContent = v.findViewById(R.id.tutorial_content);

        String show = getArguments().getString(ARG_SHOW, SHOW_HISTORY);
        setupContent(show, v);

        return v;
    }

    private void setupContent(String show, View v) {
        switch (show) {
            case SHOW_HISTORY:
                pageTitle.setText(R.string.history_title);
                repoHistory.setVisibility(View.VISIBLE);
                historyEmpty.setVisibility(View.GONE);
                historyTools.setVisibility(View.VISIBLE);
                loadHistory(v);
                break;
            case SHOW_SETTINGS:
                pageTitle.setText(R.string.settings_title);
                settingsList.setVisibility(View.VISIBLE);
                setupSettings(v);
                break;
            case SHOW_TUTORIAL:
                pageTitle.setText(R.string.tutorial_title);
                tutorialContent.setVisibility(View.VISIBLE);
                break;
            case SHOW_ABOUT:
                pageTitle.setText(R.string.about_title);
                aboutContent.setVisibility(View.VISIBLE);
                break;
            case SHOW_MINE:
                pageTitle.setText("我的");
                aboutContent.setVisibility(View.VISIBLE);
                // 复用 about 的布局，改下内容
                aboutContent.setVisibility(View.VISIBLE);
                TextView big = null;
                if (big == null) {
                    for (int i = 0; i < aboutContent.getChildCount(); i++) {
                        android.view.View child = aboutContent.getChildAt(i);
                        if (child instanceof TextView) {
                            ((TextView) child).setText("我的账号");
                            break;
                        }
                    }
                }
                break;
            default:
                setupContent(SHOW_HISTORY, v);
        }
    }

    private void loadHistory(View v) {
        HistoryDB db = new HistoryDB(requireContext());
        java.util.List<com.badminton.speed.data.DetectResult> list = db.listAll();
        if (list.isEmpty()) {
            repoHistory.setVisibility(View.GONE);
            historyEmpty.setVisibility(View.VISIBLE);
            return;
        }
        HistoryAdapter adapter = new HistoryAdapter(list);
        repoHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        repoHistory.setAdapter(adapter);

        // 日期筛选 Spinner
        Spinner spinner = v.findViewById(R.id.spinner_dates);
        if (spinner != null) {
            ArrayAdapter<String> sa = new ArrayAdapter<>(requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"全部日期"});
            spinner.setAdapter(sa);
        }
    }

    private void setupSettings(View v) {
        SharedPreferences sp = requireContext().getSharedPreferences("settings", 0);
        Switch sw1 = v.findViewById(R.id.sw_virtual_court);
        Switch sw2 = v.findViewById(R.id.sw_show_shot_speed);
        Switch sw3 = v.findViewById(R.id.sw_show_realtime);
        Switch sw4 = v.findViewById(R.id.sw_show_hit_type);
        Switch sw5 = v.findViewById(R.id.sw_show_in_out);

        sw1.setChecked(sp.getBoolean("virtual_court", true));
        sw2.setChecked(sp.getBoolean("show_shot_speed", true));
        sw3.setChecked(sp.getBoolean("show_realtime", true));
        sw4.setChecked(sp.getBoolean("show_hit_type", true));
        sw5.setChecked(sp.getBoolean("show_in_out", true));

        SharedPreferences.Editor ed = sp.edit();
        sw1.setOnCheckedChangeListener((b, checked) -> ed.putBoolean("virtual_court", checked).apply());
        sw2.setOnCheckedChangeListener((b, checked) -> ed.putBoolean("show_shot_speed", checked).apply());
        sw3.setOnCheckedChangeListener((b, checked) -> ed.putBoolean("show_realtime", checked).apply());
        sw4.setOnCheckedChangeListener((b, checked) -> ed.putBoolean("show_hit_type", checked).apply());
        sw5.setOnCheckedChangeListener((b, checked) -> ed.putBoolean("show_in_out", checked).apply());
    }
}
