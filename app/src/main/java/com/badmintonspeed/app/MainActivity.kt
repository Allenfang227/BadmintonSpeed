package com.badmintonspeed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.badmintonspeed.app.ui.AppRoot
import com.badmintonspeed.app.ui.theme.BadmintonSpeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BadmintonSpeedTheme {
                AppRoot()
            }
        }
    }
}
