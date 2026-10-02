package com.badmintonspeed.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.badmintonspeed.app.ui.AppRoot
import com.badmintonspeed.app.ui.theme.BadmintonSpeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 横屏沉浸式（参考图 2-9：全横屏布局）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContent {
            BadmintonSpeedTheme {
                AppRoot()
            }
        }
    }
}
