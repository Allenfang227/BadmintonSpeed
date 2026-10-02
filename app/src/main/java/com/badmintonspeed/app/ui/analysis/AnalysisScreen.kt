package com.badmintonspeed.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Success

private val pipelineStages = listOf(
    "提取视频帧",
    "计算场地透视",
    "检测羽毛球轨迹",
    "计算球速与击球"
)

@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val progress by vm.progress.collectAsState()
    val stage by vm.stage.collectAsState()

    // 当前阶段序号
    val stageIndex = pipelineStages.indexOfFirst { stage.contains(it) || it.contains(stage) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("正在分析", style = MaterialTheme.typography.headlineMedium, color = OnBackground, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("全程在手机本地完成，视频不会上传", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        LinearProgressIndicator(
            progress = progress / 100f,
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = Primary,
            trackColor = androidx.compose.ui.graphics.Color(0xFF334155)
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stage, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text("${progress.toInt()}%", color = OnBackground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth()) {
            pipelineStages.forEachIndexed { i, name ->
                val status = when {
                    stageIndex < 0 || i < stageIndex -> "done"
                    i == stageIndex -> "current"
                    else -> "pending"
                }
                StageRow(name, status)
            }
        }

        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = { vm.cancelAnalysis() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
        ) {
            Text("取消分析")
        }
    }
}

@Composable
private fun StageRow(name: String, status: String) {
    val (icon, color) = when (status) {
        "done" -> "✓" to Success
        "current" -> "●" to Primary
        else -> "○" to OnSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = color, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
        Text(
            name,
            color = if (status == "pending") OnSurfaceVariant else OnBackground,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (status == "current") FontWeight.Bold else FontWeight.Normal
        )
    }
}
