package com.badmintonspeed.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.ui.Screen
import com.badmintonspeed.app.ui.components.RecordRow
import com.badmintonspeed.app.ui.formatSpeed
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurface
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.SpeedColors
import com.badmintonspeed.app.ui.theme.SurfaceVariant

@Composable
fun HomeScreen(
    vm: com.badmintonspeed.app.ui.MainViewModel,
    onStart: () -> Unit,
    records: List<AnalysisRecord>
) {
    val unit = vm.settings.speedUnit
    val best = records.maxOfOrNull { it.maxSpeedKmh } ?: 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("杀球测速", style = MaterialTheme.typography.headlineLarge, color = OnBackground, fontWeight = FontWeight.Bold)
        Text("羽毛球杀球速度 AI 分析 · 全本地计算 · 隐私安全", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("开始测速", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("累计测速", records.size.toString(), Modifier.weight(1f))
            StatCard("最高球速", if (best > 0) formatSpeed(best, unit) else "--", Modifier.weight(1f), highlight = best > 0)
        }
        Spacer(Modifier.height(24.dp))

        Text("最近记录", style = MaterialTheme.typography.titleMedium, color = OnSurface, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (records.isEmpty()) {
            Text("暂无记录，完成一次测速后会自动保存", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        } else {
            records.take(3).forEach { r ->
                RecordRow(
                    record = r,
                    unit = unit,
                    onClick = { vm.goTo(Screen.RecordDetail(r)) },
                    onDelete = { vm.deleteRecord(r.id) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))

        UsageGuide()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                color = if (highlight) SpeedColors.forSpeed(value.toFloatOrNull() ?: 0f, 400f) else OnSurface,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun UsageGuide() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF16233B))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("如何使用", style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            listOf(
                "① 横屏拍摄一段包含完整杀球过程的视频（球需清晰可见）",
                "② 点击「开始测速」选择视频",
                "③ 按顺序点击画面中的场地四角（左上→右上→右下→左下）",
                "④ 自动分析：检测球路 → 透视换算 → 计算球速"
            ).forEach { s ->
                Text(s, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
