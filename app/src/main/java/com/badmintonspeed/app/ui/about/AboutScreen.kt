package com.badmintonspeed.app.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.R
import com.badmintonspeed.app.analysis.NpuSupport
import com.badmintonspeed.app.analysis.OrtSessions
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceVariant

/** 关于页（图5）：图标 + 简介 + 版本 + 软件更新/社交媒体/去评分/商务合作 */
@Composable
fun AboutScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 图标 + 名称
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(92.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text("杀球测速", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "基于AI计算机视觉技术，全程自动计算球速，重建3D飞行轨迹，并为飞行轨迹添加炫酷光效，让每一次高远、杀球、吊球都成为视觉焦点。",
            color = OnSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text("VERSION 2.33", color = OnSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        // v2.32 算力状态：超线程多核 + NPU（用户强调麒麟9000S 是手机界首个支持超线程的 SoC）
        Text(
            "算力加速：CPU ×${OrtSessions.intraThreads} 线程（SMT 超线程感知） + " +
                if (NpuSupport.working) "NPU 已启用" else "NPU 已自动回退",
            color = Color(0xFF4ADE80),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(28.dp))

        // 功能模块
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AboutCard(
                title = "软件更新",
                lines = listOf("当前版本 2.0", "已是最新版本"),
                modifier = Modifier.weight(1f)
            )
            AboutCard(
                title = "社交媒体",
                lines = listOf("抖音，快手，小红书，哔哩哔哩", "同名“杀球测速”"),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AboutCard(
                title = "去评分",
                lines = listOf("在应用商店为我们打分鼓励"),
                modifier = Modifier.weight(1f)
            )
            AboutCard(
                title = "商务合作",
                lines = listOf("广告植入，合作推广"),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.weight(1f))
        Text("Copyright © 2026 BadmintonSpeed. All Rights Reserved.", color = OnSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun AboutCard(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        modifier = modifier
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            lines.forEach { l ->
                Text(l, color = OnSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}
