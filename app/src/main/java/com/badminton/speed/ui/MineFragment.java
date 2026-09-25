package com.badminton.speed.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

public class MineFragment extends GenericFragment {
    public MineFragment() {
        super();
    }
    public static MineFragment newInstance() { return new MineFragment(); }
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = new Bundle();
        b.putString(ARG_SHOW, SHOW_MINE);
        setArguments(b);
    }
}
