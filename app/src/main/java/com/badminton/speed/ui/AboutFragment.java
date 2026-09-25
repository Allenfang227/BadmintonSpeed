package com.badminton.speed.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

public class AboutFragment extends GenericFragment {
    public AboutFragment() {
        super();
    }
    public static AboutFragment newInstance() { return new AboutFragment(); }
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = new Bundle();
        b.putString(ARG_SHOW, SHOW_ABOUT);
        setArguments(b);
    }
}
