package com.badminton.speed.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

public class SettingsFragment extends GenericFragment {
    public SettingsFragment() {
        super();
    }
    public static SettingsFragment newInstance() { return new SettingsFragment(); }
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = new Bundle();
        b.putString(ARG_SHOW, SHOW_SETTINGS);
        setArguments(b);
    }
}
