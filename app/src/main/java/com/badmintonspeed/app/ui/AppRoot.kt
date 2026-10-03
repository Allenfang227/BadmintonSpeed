package com.badmintonspeed.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badmintonspeed.app.ui.analysis.AnalysisScreen
import com.badmintonspeed.app.ui.about.AboutScreen
import com.badmintonspeed.app.ui.components.LiquidGlassNavBar
import com.badmintonspeed.app.ui.history.HistoryScreen
import com.badmintonspeed.app.ui.history.RecordDetailScreen
import com.badmintonspeed.app.ui.home.HomeScreen
import com.badmintonspeed.app.ui.mine.MineScreen
import com.badmintonspeed.app.ui.result.ResultScreen
import com.badmintonspeed.app.ui.sensors.GravityShadowProvider
import com.badmintonspeed.app.ui.settings.SettingsScreen
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.train.ModelFilesScreen
import com.badmintonspeed.app.ui.train.TrainModeScreen
import com.badmintonspeed.app.ui.tutorial.TutorialScreen
import kotlinx.coroutines.launch

/**
 * 底部导航项（v2.19：左侧导航栏 → 底部液态玻璃悬浮导航，横向平移切换）
 */
private val navLabels = listOf("测速模式", "历史记录", "画面设置", "使用教程", "我的")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    // v2.20：陀螺仪/重力驱动全树动态阴影（裸眼 3D）
    GravityShadowProvider {
        AppRootContent(vm)
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
            else -> if (screen is Screen.Home) {
                // 已退出确认逻辑：返回键退出
                context.startActivity(
                    android.content.Intent(context, com.badmintonspeed.app.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                android.os.Process.killProcess(android.os.Process.myPid())
            } else vm.goTo(Screen.Home)
        }
    }

    // 全屏子页（无底部导航，参考图 6-9 结果页全屏）
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

    // ---- v2.19 主界面：全屏内容 + 底部液态玻璃悬浮导航（横向平移切换） ----
    val pagerState = rememberPagerState(pageCount = { navLabels.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Background)) {
        // 内容区：五个主页面横向滑动切换（HorizontalPager）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> HomeScreen(
                        vm,
                        onStart = { pickVideo.launch(arrayOf("video/*")) },
                        onTrain = { vm.goTo(Screen.TrainMode) },
                        records
                    )
                    1 -> HistoryScreen(vm, records)
                    2 -> SettingsScreen(vm)
                    3 -> TutorialScreen()
                    4 -> MineScreen(
                        vm,
                        onAbout = { vm.goTo(Screen.About) },
                        onExit = { showExitDialog = true }
                    )
                }
            }
        }

        // 底部悬浮导航：选中态随滑动联动，点击做横向平移动画
        LiquidGlassNavBar(
            items = navLabels,
            selected = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
        )
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
