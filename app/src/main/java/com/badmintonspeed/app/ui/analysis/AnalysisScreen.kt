package com.badmintonspeed.app.ui.analysis

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.badmintonspeed.app.ui.components.CourtOverlay
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Success
import kotlinx.coroutines.delay

/** v2.23 白色分析页配色（与白色首页一致） */
private val WCardBg = Color(0xFFF2F7F4)
private val WCardBorder = Color(0xFFDCE7E1)
private val WTitle = Color(0xFF10231A)
private val WDesc = Color(0xFF5E7267)
private val WTrack = Color(0xFFE0E7E3)

/**
 * 分析进度页（参考图2/图3，v2.23 白色版）：
 * 左侧实时视频预览（带检测框），右侧分模块步骤面板，底部预计时长+取消。
 */
@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val stage by vm.stage.collectAsState()
    val preview by vm.previewFrame.collectAsState()
    val court by vm.courtResult.collectAsState()

    val totalPct = stage?.totalPercent ?: 0f
    val showPerf by vm.showPerfOverlay.collectAsState()
    val perfInfo by vm.perfInfo.collectAsState()

    Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
        // ---- 顶部标题 + 超线程悬浮窗开关 ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "上传视频测速",
                color = Primary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            // v2.37 超线程实时悬浮窗开关
            Box(
                Modifier
                    .background(if (showPerf) Primary else Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                    .clickable { vm.togglePerfOverlay() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    if (showPerf) "⚡ 性能中" else "⚡ 性能",
                    color = if (showPerf) Color.White else Color(0xFF666666),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
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
                // v2.12：场地黄线常驻叠加（标定完成后一直显示，不闪）
                CourtOverlay(
                    court = court,
                    frameW = preview?.width ?: 1280,
                    frameH = preview?.height ?: 720,
                    modifier = Modifier.fillMaxSize()
                )
                // v2.37 超线程实时悬浮窗（左上角，可开关）
                if (showPerf && perfInfo.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            perfInfo,
                            color = Color(0xFF00FF88),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                // 倍速按钮（可点击切换，图2/图3 左下角 1.0x）
                val speeds = listOf("0.5x", "1.0x", "1.5x", "2.0x")
                var speedIdx by remember { mutableStateOf(1) }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(Color(0x66000000), RoundedCornerShape(6.dp))
                        .clickable { speedIdx = (speedIdx + 1) % speeds.size }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(speeds[speedIdx], color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                // 预处理提示（v2.23 新流程：转格式 -> 场地颜色 -> 场地检测 -> 重复帧 -> 正式检测）
                val preprocessText = when {
                    totalPct < 10f -> "正在转换视频格式 ${(totalPct / 10f * 100).toInt().coerceIn(0, 99)}%"
                    totalPct < 12f -> "正在识别场地颜色 ${((totalPct - 10f) / 2f * 100).toInt().coerceIn(0, 99)}%"
                    totalPct < 32f -> "正在检测场地 ${((totalPct - 12f) / 20f * 100).toInt().coerceIn(0, 99)}%"
                    totalPct < 35f -> "正在检测重复帧 ${((totalPct - 32f) / 3f * 100).toInt().coerceIn(0, 99)}%"
                    else -> null
                }
                preprocessText?.let {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                            .background(Color(0x99000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(it, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // 底部细进度条
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(WTrack)
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
                // 模块区可滚动（"右边的栏可拉"）
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
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
                }

                // ---- 底部状态行（固定，图2/图3）----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("正在进行分析", color = WTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { vm.cancelAnalysis() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                        modifier = Modifier.height(36.dp)                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                // v2.37 底部显示当前正在处理的具体内容（detail 优先，无 detail 时显示阶段·步骤）
                val curDetail = stage?.detail?.takeIf { it.isNotEmpty() }
                val curPhaseTitle = stage?.phase?.title ?: "准备中"
                val curStepName = stage?.let { s -> s.phase.steps.getOrNull(s.stepIndex) }
                Text(
                    curDetail ?: (if (curStepName != null) "当前：$curPhaseTitle · $curStepName" else "当前：$curPhaseTitle"),
                    color = WDesc,
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
        else -> WDesc
    }
    val cardBg = if (active || done || doneEarly) WCardBg else WCardBg.copy(alpha = 0.45f)

    Column(
        Modifier
            .fillMaxWidth()
            .background(cardBg, RoundedCornerShape(10.dp))
            .border(1.dp, WCardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(phase.title, color = WTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (active) {
            Text(
                "确保视频中羽毛球拍摄清晰，不要和白色背景融合",
                color = WDesc,
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
        else -> "○" to WDesc
    }
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = color, fontSize = 11.sp, modifier = Modifier.width(16.dp))
        Text(
            name,
            color = if (state == "pending") WDesc else WTitle,
            fontSize = 12.sp,
            fontWeight = if (state == "current") FontWeight.Bold else FontWeight.Normal
        )
    }
}
