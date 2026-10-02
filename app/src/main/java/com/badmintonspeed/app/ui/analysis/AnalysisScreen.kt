package com.badmintonspeed.app.ui.analysis

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.domain.AnalysisPhase
import com.badmintonspeed.app.domain.StageUpdate
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Success
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceVariant
import kotlinx.coroutines.delay

/**
 * 分析进度页（参考图2/图3）：
 * 左侧实时视频预览（带检测框），右侧分模块步骤面板，底部预计时长+取消。
 */
@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val stage by vm.stage.collectAsState()
    val preview by vm.previewFrame.collectAsState()
    val startMs by vm.analysisStartMs.collectAsState()

    // 每 1 秒刷新一次剩余时长估算
    val nowState = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs) {
        while (true) {
            nowState.value = System.currentTimeMillis()
            delay(1000)
        }
    }

    val totalPct = stage?.totalPercent ?: 0f
    val elapsedMs = (nowState.value - startMs).coerceAtLeast(0L)
    val remainSec = if (totalPct >= 2f) {
        val speed = elapsedMs / totalPct // ms per percent
        (speed * (100f - totalPct) / 1000f).toLong()
    } else 0L

    Column(Modifier.fillMaxSize().background(Background).padding(16.dp)) {
        // ---- 顶部标题 ----
        Text(
            "上传视频测速",
            color = Primary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxSize()) {
            // ================= 左侧：实时视频预览 =================
            Box(
                Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .background(Color.Black)
            ) {
                preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } ?: Box(Modifier.fillMaxSize())
                // 倍速标识（图2/图3 左下角 1.0x）
                Text(
                    "1.0x",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                )
                // 底部细进度条
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0xFF334155))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(totalPct / 100f)
                            .height(4.dp)
                            .background(Primary)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // ================= 右侧：分模块步骤面板 =================
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // 场地基准检测（图2 展示的检测中模块）
                ModuleCard(
                    phase = AnalysisPhase.COURT,
                    stage = stage,
                    highlight = stage?.phase == AnalysisPhase.COURT
                )
                Spacer(Modifier.height(8.dp))
                ModuleCard(
                    phase = AnalysisPhase.SHUTTLE,
                    stage = stage,
                    highlight = stage?.phase == AnalysisPhase.SHUTTLE
                )
                Spacer(Modifier.height(8.dp))
                ModuleCard(
                    phase = AnalysisPhase.PLAYER,
                    stage = stage,
                    highlight = stage?.phase == AnalysisPhase.PLAYER
                )
                Spacer(Modifier.height(8.dp))
                ModuleCard(
                    phase = AnalysisPhase.HIT,
                    stage = stage,
                    highlight = stage?.phase == AnalysisPhase.HIT
                )

                Spacer(Modifier.weight(1f))

                // ---- 底部状态行（图2/图3）----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("正在进行分析", color = OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { vm.cancelAnalysis() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "预计时长约 ${fmtDuration(remainSec)}，您可退出应用，程序会继续",
                    color = OnSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** 分模块卡片（图2/图3 右侧面板） */
@Composable
private fun ModuleCard(phase: AnalysisPhase, stage: StageUpdate?, highlight: Boolean) {
    val active = stage?.phase == phase && !stage.done
    val done = stage?.phase == phase && stage.done
    // 更早完成的阶段
    val doneEarly = phase.ordinal < (stage?.phase?.ordinal ?: 0)

    val statusText = when {
        done || doneEarly -> "已完成"
        active -> "检测中·${(stage?.phasePercent ?: 0f).toInt()}%"
        else -> "待启动"
    }
    val statusColor = when {
        done || doneEarly -> Success
        active -> Primary
        else -> OnSurfaceVariant
    }
    val cardBg = if (active || done || doneEarly) Surface else Surface.copy(alpha = 0.45f)

    Column(
        Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(phase.title, color = OnBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (active) {
            Text(
                "确保视频中羽毛球拍摄清晰，不要和白色背景融合",
                color = OnSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        phase.steps.forEachIndexed { idx, name ->
            val stepState = when {
                done || doneEarly || idx < (stage?.stepIndex ?: -1) -> "done"
                active && idx == (stage?.stepIndex ?: -1) -> "current"
                else -> "pending"
            }
            StepRow(name, stepState)
        }
    }
}

@Composable
private fun StepRow(name: String, state: String) {
    val (icon, color) = when (state) {
        "done" -> "✓" to Success
        "current" -> "●" to Primary
        else -> "○" to OnSurfaceVariant
    }
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = color, fontSize = 11.sp, modifier = Modifier.width(16.dp))
        Text(
            name,
            color = if (state == "pending") OnSurfaceVariant else OnBackground,
            fontSize = 12.sp,
            fontWeight = if (state == "current") FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun fmtDuration(sec: Long): String {
    if (sec <= 0) return "1分钟"
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}
