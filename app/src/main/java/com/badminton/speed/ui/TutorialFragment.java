package com.badminton.speed.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;

public class TutorialFragment extends GenericFragment {
    public TutorialFragment() {
        super();
    }
    public static TutorialFragment newInstance() { return new TutorialFragment(); }
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle b = new Bundle();
        b.putString(ARG_SHOW, SHOW_TUTORIAL);
        setArguments(b);
    }
}
