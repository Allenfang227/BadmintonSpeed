package com.badmintonspeed.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * v2.39 场地标定模式选择页（手机竖屏可上下滚动适配）：
 * - 手工标注：用户手动点4个角点，标完直接套模板，跳过AI检测和颜色校验
 * - AI自动标注：AI自动识别场地线+颜色校验，无需人工干预
 */
@Composable
fun CalibrateModeScreen(vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // 顶部返回 + 标题
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(Primary, RoundedCornerShape(12.dp))
                    .clickable { vm.goTo(Screen.Home) },
                contentAlignment = Alignment.Center
            ) {
                Text("←", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "选择场地标定方式",
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "标定后直接套 BWF 标准模板（13.40×6.10m）",
            color = OnSurfaceVariant,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(18.dp))

        // 手工标注卡片
        ModeCard(
            title = "手工标注",
            desc = "手动点4个场地角点，标完直接套模板不再AI调整，精度最高，推荐首次使用",
            iconText = "✋",
            onClick = { vm.onSelectCalibrateMode("manual") }
        )

        Spacer(Modifier.height(14.dp))

        // AI自动标注卡片
        ModeCard(
            title = "AI 自动标注",
            desc = "AI自动识别场地线+颜色校验，无需人工干预一键开始，适合场地清晰光线良好的视频",
            iconText = "🤖",
            onClick = { vm.onSelectCalibrateMode("ai") }
        )
        Spacer(Modifier.height(20.dp))
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
            .background(Surface, RoundedCornerShape(18.dp))
            .border(1.5.dp, Primary.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Primary.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(iconText, fontSize = 26.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    desc,
                    color = OnSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
