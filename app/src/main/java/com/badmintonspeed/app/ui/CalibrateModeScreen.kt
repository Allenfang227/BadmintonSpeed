package com.badmintonspeed.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.OnSurface
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceVariant

/**
 * v2.36 场地标定模式选择页：
 * - 手工标注：用户手动点4个角点，标完直接套模板，跳过AI检测和颜色校验
 * - AI自动标注：AI自动识别场地线+颜色校验，无需人工干预
 */
@Composable
fun CalibrateModeScreen(vm: MainViewModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部返回
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(Primary, RoundedCornerShape(12.dp))
                        .clickable { vm.goTo(Screen.Home) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    "选择场地标定方式",
                    color = OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(40.dp))

            Text(
                "标定后直接套 BWF 标准模板（13.40×6.10m）",
                color = OnSurfaceVariant,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(32.dp))

            // 手工标注卡片
            ModeCard(
                title = "手工标注",
                desc = "手动点4个场地角点\n标完直接套模板，不再AI调整\n精度最高，推荐首次使用",
                iconText = "✋",
                onClick = { vm.onSelectCalibrateMode("manual") }
            )

            Spacer(Modifier.height(20.dp))

            // AI自动标注卡片
            ModeCard(
                title = "AI 自动标注",
                desc = "AI 自动识别场地线+颜色校验\n无需人工干预，一键开始\n适合场地清晰、光线良好的视频",
                iconText = "🤖",
                onClick = { vm.onSelectCalibrateMode("ai") }
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    desc: String,
    iconText: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(20.dp))
            .border(1.5.dp, Primary.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 图标
            Box(
                Modifier
                    .size(64.dp)
                    .background(Primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(iconText, fontSize = 30.sp)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(
                    title,
                    color = OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    desc,
                    color = OnSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
