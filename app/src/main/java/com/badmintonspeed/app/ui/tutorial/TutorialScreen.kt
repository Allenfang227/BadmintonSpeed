package com.badmintonspeed.app.ui.tutorial

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface

/** 使用教程页：三步使用流程（v2.43：内容可纵向滚动，适配小屏） */
@Composable
fun TutorialScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 44.dp, vertical = 32.dp)
    ) {
        Text("使用教程", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("How to Use", color = OnSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))

        TutorialCard(1, "拍摄视频", "横屏拍摄一段包含完整杀球过程的视频，确保羽毛球清晰可见、光线充足。")
        Spacer(Modifier.height(16.dp))
        TutorialCard(2, "上传并标定", "点击「上传视频测速」→「进入」，选择视频后按顺序点击场地四角（左上→右上→右下→左下）。")
        Spacer(Modifier.height(16.dp))
        TutorialCard(3, "查看结果", "AI 自动识别羽毛球轨迹、计算每帧球速，还原 3D 飞行轨迹回放，支持帧步进与下载。")
    }
}

@Composable
private fun TutorialCard(step: Int, title: String, desc: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                "STEP $step",
                color = Primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(desc, color = OnSurfaceVariant, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
