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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badmintonspeed.app.ui.analysis.AnalysisScreen
import com.badmintonspeed.app.ui.history.HistoryScreen
import com.badmintonspeed.app.ui.history.RecordDetailScreen
import com.badmintonspeed.app.ui.home.HomeScreen
import com.badmintonspeed.app.ui.train.TrainModeScreen
import com.badmintonspeed.app.ui.train.ModelFilesScreen
import com.badmintonspeed.app.ui.result.ResultScreen
import com.badmintonspeed.app.ui.settings.SettingsScreen
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.DividerColor
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceVariant

/** 侧边导航项（图2/3/4/5 左侧导航栏） */
private data class NavItem(val label: String, val screen: Screen)

private val navItems = listOf(
    NavItem("测速模式", Screen.Home),
    NavItem("历史记录", Screen.History),
    NavItem("画面设置", Screen.Settings),
    NavItem("使用教程", Screen.Tutorial),
    NavItem("我的", Screen.Mine),
    NavItem("关于", Screen.About)
)

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
    val error by vm.error.collectAsState()
    val records by vm.records.collectAsState()
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }
    var showAnalyzeMenu by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onVideoPicked(it) } }

    LaunchedEffect(Unit) { vm.loadHistory() }

    BackHandler(enabled = true) {
        when (screen) {
            is Screen.Analyzing -> showAnalyzeMenu = true
            is Screen.Result -> vm.exitResult()
            is Screen.RecordDetail -> vm.goTo(Screen.History)
            else -> if (screen is Screen.Home) {
                // 已退出确认逻辑：双击返回退出
                context.startActivity(
                    android.content.Intent(context, com.badmintonspeed.app.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                android.os.Process.killProcess(android.os.Process.myPid())
            } else vm.goTo(Screen.Home)
        }
    }

    // 结果/分析页：全屏（无侧栏，参考图 6-9 结果页全屏）
    val fullScreen = screen is Screen.Analyzing || screen is Screen.Result ||
        screen is Screen.Calibrate || screen is Screen.CalibrateMode ||
        screen is Screen.TrainMode || screen is Screen.ModelFiles
    if (fullScreen) {
        Box(Modifier.fillMaxSize().background(Background)) {
            when (val s = screen) {
                is Screen.CalibrateMode -> CalibrateModeScreen(vm)
                is Screen.Calibrate -> CalibrateScreen(vm)
                is Screen.TrainMode -> TrainModeScreen(
                    onOpenFiles = { vm.goTo(Screen.ModelFiles) },
                    onBack = { vm.goTo(Screen.Home) }
                )
                is Screen.ModelFiles -> ModelFilesScreen(onBack = { vm.goTo(Screen.TrainMode) })
                is Screen.Analyzing -> AnalysisScreen(vm)
                is Screen.Result -> ResultScreen(vm)
                else -> {}
            }
        }
        error?.let { ErrorDialog(it, vm::dismissError, vm::retryWithManualCalibration) }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // ---- 左侧导航栏 ----
        Column(
            Modifier
                .width(190.dp)
                .fillMaxHeight()
                .background(Surface)
                .padding(vertical = 28.dp)
        ) {
            // 顶部品牌区
            Text(
                "杀球测速",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
            )
            Text(
                "B A D M I N T O N  S P E E D",
                color = OnSurfaceVariant,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 24.dp, bottom = 24.dp)
            )

            navItems.forEach { item ->
                val selected = when (item.screen) {
                    is Screen.Home -> screen is Screen.Home || screen is Screen.Analyzing || screen is Screen.Result
                    is Screen.History -> screen is Screen.History || screen is Screen.RecordDetail
                    is Screen.Settings -> screen is Screen.Settings
                    is Screen.Tutorial -> screen is Screen.Tutorial
                    is Screen.Mine -> screen is Screen.Mine
                    is Screen.About -> screen is Screen.About
                    else -> false
                }
                NavRow(item.label, selected, onClick = { vm.goTo(item.screen) })
            }

            Spacer(Modifier.weight(1f))

            // 退出 / 注销
            NavRow("退出/注销", selected = false, danger = true, onClick = { showExitDialog = true })
        }

        // ---- 右侧内容区 ----
        Box(Modifier.fillMaxSize().background(Background)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    vm,
                    onStart = { pickVideo.launch(arrayOf("video/*")) },
                    onTrain = { vm.goTo(Screen.TrainMode) },
                    records
                )
                Screen.History -> HistoryScreen(vm, records)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Tutorial -> com.badmintonspeed.app.ui.tutorial.TutorialScreen()
                Screen.Mine -> com.badmintonspeed.app.ui.mine.MineScreen(vm)
                Screen.About -> com.badmintonspeed.app.ui.about.AboutScreen(vm)
                is Screen.RecordDetail -> RecordDetailScreen(s.record, onBack = { vm.goTo(Screen.History) })
                else -> {}
            }
        }
    }

    error?.let { ErrorDialog(it, vm::dismissError, vm::retryWithManualCalibration) }

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

    // v2.30 分析中返回：继续分析 / 后台运行 / 取消分析（防误触丢任务）
    if (showAnalyzeMenu) {
        AlertDialog(
            onDismissRequest = { showAnalyzeMenu = false },
            title = { Text("分析进行中") },
            text = { Text("分析仍在运行，切到后台也会继续，可随时回来查看进度。") },
            confirmButton = {
                TextButton(onClick = {
                    showAnalyzeMenu = false
                    vm.minimizeAnalysis()
                }) { Text("后台运行") }
                TextButton(onClick = { showAnalyzeMenu = false }) { Text("继续分析") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAnalyzeMenu = false
                    vm.cancelAnalysis()
                }) { Text("取消分析", color = Error) }
            }
        )
    }
}

@Composable
private fun NavRow(label: String, selected: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val bg = if (selected) SurfaceVariant else Color.Transparent
    val fg = when {
        danger -> Color(0xFFEF4444)
        selected -> Color.White
        else -> OnSurfaceVariant
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .background(bg)
            .padding(start = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (selected) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(Primary, RoundedCornerShape(2.dp))
            )
        }
        Text(
            label,
            color = fg,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(start = if (selected) 20.dp else 0.dp)
        )
    }
}

@Composable
private fun ErrorDialog(
    error: com.badmintonspeed.app.domain.AnalysisError,
    onDismiss: () -> Unit,
    onManualCalibrate: (() -> Unit)? = null,
    onRoiSelect: (() -> Unit)? = null // v2.27.3: ROI 入口移除，参数保留兼容
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
            }
        }
    )
}
