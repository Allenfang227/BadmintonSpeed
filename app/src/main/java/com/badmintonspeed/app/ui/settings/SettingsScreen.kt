package com.badmintonspeed.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintonspeed.app.domain.PerformanceMode
import com.badmintonspeed.app.domain.SpeedUnit
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurface
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Surface

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings = vm.settings
    var threshold by remember { mutableFloatStateOf(settings.brightThreshold.toFloat()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = OnBackground, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SettingCard {
            Text("速度单位", style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                SpeedUnit.values().forEach { u ->
                    FilterChip(
                        selected = settings.speedUnit == u,
                        onClick = { vm.setSpeedUnit(u) },
                        label = { Text(u.displayName) }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SettingCard {
            Text("性能模式（分析帧率）", style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            PerformanceMode.values().forEach { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.performanceMode == m,
                        onClick = { vm.setPerformanceMode(m) },
                        label = { Text(m.displayName) }
                    )
                    Text("${m.analysisFps} fps", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (m == PerformanceMode.BALANCED) {
                    Text("帧率越高越准但越耗时（建议 30fps）", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SettingCard {
            Text("检测亮度阈值", style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("针对白色羽毛球的亮度判定线：场馆灯光亮、球路清晰时可调高；球较暗或曝光不足时调低", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { vm.setBrightThreshold(threshold.toInt()) },
                valueRange = 160f..230f,
                steps = 13
            )
            Text("当前：${threshold.toInt()}", color = OnBackground, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))

        SettingCard {
            Text("关于", style = MaterialTheme.typography.titleSmall, color = OnSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("杀球测速 v1.0.0", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text("羽毛球杀球速度 AI 视频分析工具：视频本地分析，不上传任何数据。", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = Surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            content()
        }
    }
}
