package com.badmintonspeed.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.badmintonspeed.app.ui.analysis.AnalysisScreen
import com.badmintonspeed.app.ui.calibrate.CalibrateScreen
import com.badmintonspeed.app.ui.history.HistoryScreen
import com.badmintonspeed.app.ui.history.RecordDetailScreen
import com.badmintonspeed.app.ui.home.HomeScreen
import com.badmintonspeed.app.ui.result.ResultScreen
import com.badmintonspeed.app.ui.settings.SettingsScreen
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.Surface

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
    val error by vm.error.collectAsState()
    val records by vm.records.collectAsState()

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.onVideoPicked(it) } }

    LaunchedEffect(Unit) { vm.loadHistory() }

    val showBottom = screen is Screen.Home || screen is Screen.History || screen is Screen.Settings
    val selectedTab = when (screen) {
        is Screen.History -> 1
        is Screen.Settings -> 2
        else -> 0
    }

    BackHandler(enabled = screen !is Screen.Home) {
        when (screen) {
            is Screen.Calibrate -> vm.goTo(Screen.Home)
            is Screen.Analyzing -> vm.cancelAnalysis()
            is Screen.Result -> vm.goTo(Screen.Home)
            is Screen.RecordDetail -> vm.goTo(Screen.History)
            else -> vm.goTo(Screen.Home)
        }
    }

    Scaffold(
        containerColor = Background,
        bottomBar = {
            if (showBottom) {
                NavigationBar(containerColor = Surface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { vm.goTo(Screen.Home) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("首页") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { vm.goTo(Screen.History) },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("历史") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { vm.goTo(Screen.Settings) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(vm, onStart = { pickVideo.launch(arrayOf("video/*")) }, records)
                Screen.Calibrate -> CalibrateScreen(vm)
                Screen.Analyzing -> AnalysisScreen(vm)
                Screen.Result -> ResultScreen(vm)
                Screen.History -> HistoryScreen(vm, records)
                Screen.Settings -> SettingsScreen(vm)
                is Screen.RecordDetail -> RecordDetailScreen(s.record, onBack = { vm.goTo(Screen.History) })
            }
        }
    }

    error?.let {
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("知道了") } }
        )
    }
}
