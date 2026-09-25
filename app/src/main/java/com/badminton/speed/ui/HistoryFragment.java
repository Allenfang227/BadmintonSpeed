package com.badminton.speed.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

public class HistoryFragment extends GenericFragment {
    public HistoryFragment() {
        super();
    }
    public static HistoryFragment newInstance() { return new HistoryFragment(); }
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = new Bundle();
        b.putString(ARG_SHOW, SHOW_HISTORY);
        setArguments(b);
    }
}
