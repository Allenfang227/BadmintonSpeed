package com.badmintonspeed.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.badmintonspeed.app.data.CourtModelRepo
import com.badmintonspeed.app.ui.AppRoot
import com.badmintonspeed.app.ui.theme.BadmintonSpeedTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 横屏沉浸式（参考图 2-9：全横屏布局）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // v2.21：启动即从公共目录 Download/BadmintonSpeed 恢复训练模型/样本/标注
        // （卸载重装后 filesDir 被清空，这里保证原训练成果不丢；训练可叠加）
        CoroutineScope(Dispatchers.IO).launch {
            CourtModelRepo.syncFromPublic(applicationContext)
        }
        setContent {
            BadmintonSpeedTheme {
                AppRoot()
            }
        }
    }
}
