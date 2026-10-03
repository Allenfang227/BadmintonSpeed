package com.badmintonspeed.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badmintonspeed.app.ui.about.AboutScreen
import com.badmintonspeed.app.ui.analysis.AnalysisScreen
import com.badmintonspeed.app.ui.components.liveShadow
import com.badmintonspeed.app.ui.history.HistoryScreen
import com.badmintonspeed.app.ui.history.RecordDetailScreen
import com.badmintonspeed.app.ui.home.HomeScreen
import com.badmintonspeed.app.ui.mine.MineScreen
import com.badmintonspeed.app.ui.result.ResultScreen
import com.badmintonspeed.app.ui.sensors.GravityShadowProvider
import com.badmintonspeed.app.ui.settings.SettingsScreen
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.DividerColor
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.SurfaceVariant
import com.badmintonspeed.app.ui.train.ModelFilesScreen
import com.badmintonspeed.app.ui.train.TrainModeScreen
import com.badmintonspeed.app.ui.tutorial.TutorialScreen

/**
 * v2.21：布局回退「图1 原版」——左侧竖向胶囊导航 + 右侧内容区；
 * 陀螺仪裸眼 3D（liveShadow）保留，作用于导航胶囊 / 卡片 / 按钮。
 */
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    GravityShadowProvider {
        AppRootContent(vm)
    }
}

@Composable
private fun AppRootContent(vm: MainViewModel) {
    val screen by vm.screen.collectAsState()
    val error by vm.error.collectAsState()
    val records by vm.records.collectAsState()
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onVideoPicked(it) } }

    LaunchedEffect(Unit) { vm.loadHistory() }

    BackHandler(enabled = true) {
        when (screen) {
            is Screen.Analyzing -> vm.cancelAnalysis()
            is Screen.Result -> vm.goTo(Screen.Home)
            is Screen.RecordDetail -> vm.goTo(Screen.History)
            is Screen.About -> vm.goTo(Screen.Mine)
            is Screen.Home -> {
                context.startActivity(
                    android.content.Intent(context, com.badmintonspeed.app.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            else -> vm.goTo(Screen.Home)
        }
    }

    // 全屏子页（无左侧导航）
    val fullScreen = screen is Screen.Analyzing || screen is Screen.Result ||
        screen is Screen.Calibrate || screen is Screen.RoiSelect ||
        screen is Screen.TrainMode || screen is Screen.ModelFiles ||
        screen is Screen.RecordDetail || screen is Screen.About
    if (fullScreen) {
        Box(Modifier.fillMaxSize().background(Background)) {
            when (val s = screen) {
                is Screen.Calibrate -> CalibrateScreen(vm)
                is Screen.RoiSelect -> RoiSelectScreen(
                    frame = vm.calibrationFrame.collectAsState().value,
                    onSubmit = { vm.submitRoi(it) },
                    onBack = { vm.goTo(Screen.Home) }
                )
                is Screen.TrainMode -> TrainModeScreen(
                    onOpenFiles = { vm.goTo(Screen.ModelFiles) },
                    onBack = { vm.goTo(Screen.Home) }
                )
                is Screen.ModelFiles -> ModelFilesScreen(onBack = { vm.goTo(Screen.TrainMode) })
                is Screen.Analyzing -> AnalysisScreen(vm)
                is Screen.Result -> ResultScreen(vm)
                is Screen.RecordDetail -> RecordDetailScreen(s.record, onBack = { vm.goTo(Screen.History) })
                is Screen.About -> AboutScreen(vm)
                else -> {}
            }
        }
        error?.let { ErrorDialog(it, vm::dismissError, vm::retryWithManualCalibration, vm::retryWithRoiSelect) }
        return
    }

    // ---- 主界面：左侧竖向导航 + 右侧内容（图1 原版布局） ----
    Box(Modifier.fillMaxSize().background(Background)) {
        Row(Modifier.fillMaxSize()) {
            // ===== 左侧导航 =====
            Column(
                Modifier
                    .width(196.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NavPill("测速模式", selected = screen is Screen.Home) { vm.goTo(Screen.Home) }
                Spacer(Modifier.height(16.dp))
                NavPill("历史记录", selected = screen is Screen.History) { vm.goTo(Screen.History) }
                Spacer(Modifier.height(16.dp))
                NavPill("画面设置", selected = screen is Screen.Settings) { vm.goTo(Screen.Settings) }
                Spacer(Modifier.height(16.dp))
                NavPill("使用教程", selected = screen is Screen.Tutorial) { vm.goTo(Screen.Tutorial) }
                Spacer(Modifier.height(16.dp))
                NavPill("我的", selected = screen is Screen.Mine) { vm.goTo(Screen.Mine) }
                Spacer(Modifier.height(16.dp))
                NavPill("关于", selected = false) { vm.goTo(Screen.About) }
                Spacer(Modifier.weight(1f))
                // 退出/注销：描边胶囊
                OutlinedButton(
                    onClick = { showExitDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .liveShadow(cornerRadius = 24.dp, strengthDp = 5.dp, alpha = 0.35f),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, Primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
                ) {
                    Text("退出/注销", fontSize = 16.sp)
                }
            }
            // 竖分隔线
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 22.dp)
                    .background(DividerColor)
            )
            // ===== 右侧内容区 =====
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (screen) {
                    is Screen.Home -> HomeScreen(
                        vm,
                        onStart = { pickVideo.launch(arrayOf("video/*")) },
                        onTrain = { vm.goTo(Screen.TrainMode) },
                        records
                    )
                    is Screen.History -> HistoryScreen(vm, records)
                    is Screen.Settings -> SettingsScreen(vm)
                    is Screen.Tutorial -> TutorialScreen()
                    is Screen.Mine -> MineScreen(vm)
                    else -> HomeScreen(
                        vm,
                        onStart = { pickVideo.launch(arrayOf("video/*")) },
                        onTrain = { vm.goTo(Screen.TrainMode) },
                        records
                    )
                }
            }
        }
    }

    error?.let { ErrorDialog(it, vm::dismissError, vm::retryWithManualCalibration, vm::retryWithRoiSelect) }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出/注销") },
            text = { Text("确定要退出杀球测速吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    context.startActivity(
                        android.content.Intent(context, com.badmintonspeed.app.MainActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("退出", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("取消", color = OnSurfaceVariant) }
            }
        )
    }
}

/** 左侧导航胶囊：选中亮绿实心（黑字），未选中深色（白字）；均带陀螺仪动态阴影 */
@Composable
private fun NavPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Primary else SurfaceVariant
    val fg = if (selected) Color(0xFF06120A) else Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .liveShadow(
                cornerRadius = 25.dp,
                strengthDp = if (selected) 7.dp else 4.dp,
                alpha = if (selected) 0.45f else 0.3f
            )
            .clip(RoundedCornerShape(25.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontSize = 18.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ErrorDialog(
    error: com.badmintonspeed.app.domain.AnalysisError,
    onDismiss: () -> Unit,
    onManualCalibrate: (() -> Unit)? = null,
    onRoiSelect: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示  ${error.code}", color = Error) },
        text = {
            Text(
                "${error.title}\n\n${error.detail}\n\n（判定阈值：${error.threshold}）",
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
            if (error.suggestManual) {
                TextButton(onClick = { onManualCalibrate?.invoke() }) { Text("手动标定", color = Error) }
                TextButton(onClick = { onRoiSelect?.invoke() }) { Text("ROI框选", color = Color(0xFF4FC3F7)) }
            }
        }
    )
}
