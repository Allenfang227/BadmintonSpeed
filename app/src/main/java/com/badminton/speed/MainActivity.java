package com.badminton.speed;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.badminton.speed.ui.AboutFragment;
import com.badminton.speed.ui.HistoryFragment;
import com.badminton.speed.ui.MineFragment;
import com.badminton.speed.ui.SettingsFragment;
import com.badminton.speed.ui.SpeedModeFragment;
import com.badminton.speed.ui.TutorialFragment;
import com.google.android.material.navigation.NavigationView;

/**
 * 主入口：DrawerLayout + FrameLayout 宿主，通过 Fragment 切换不同页面。
 * 侧边栏菜单点击 → 切换 Fragment。
 */
public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private DrawerLayout drawer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化 OpenCV（关键，必须在 setContentView 前完成以确保 native lib 加载）
        boolean ok = com.badminton.speed.OpenCVInit.ensureLoaded();

        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawer_layout);
        NavigationView nav = findViewById(R.id.nav_view);
        if (nav != null) nav.setNavigationItemSelectedListener(this);

        if (savedInstanceState == null) {
            loadFragment(new SpeedModeFragment());
        }
    }

    private void loadFragment(Fragment f) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.main_container, f);
        ft.addToBackStack(null);
        ft.commit();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        Fragment target = null;
        if (id == R.id.nav_speed_mode) target = new SpeedModeFragment();
        else if (id == R.id.nav_history) target = new HistoryFragment();
        else if (id == R.id.nav_settings) target = new SettingsFragment();
        else if (id == R.id.nav_tutorial) target = new TutorialFragment();
        else if (id == R.id.nav_mine) target = new MineFragment();
        else if (id == R.id.nav_about) target = new AboutFragment();
        else if (id == R.id.nav_logout) {
            finish();
            return true;
        }
        if (target != null) loadFragment(target);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    public DrawerLayout getDrawer() { return drawer; }
}
