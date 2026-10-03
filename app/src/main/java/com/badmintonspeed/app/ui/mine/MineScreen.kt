package com.badmintonspeed.app.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.domain.PerformanceMode
import com.badmintonspeed.app.domain.SpeedUnit
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.Error
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface

/** 我的页：速度单位 / 性能模式 / 检测灵敏度 / 关于 / 退出 */
@Composable
fun MineScreen(
    vm: MainViewModel,
    onAbout: () -> Unit = {},
    onExit: () -> Unit = {}
) {
    val s = vm.settings
    var unitMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 32.dp)) {
        Text("我的", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Me & Settings", color = OnSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))

        // 速度单位
        MenuRow("速度单位", s.speedUnit.displayName, expanded = unitMenu) {
            unitMenu = true
        }
        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
            SpeedUnit.values().forEach { u ->
                DropdownMenuItem(text = { Text(u.displayName) }, onClick = {
                    vm.setSpeedUnit(u)
                    unitMenu = false
                })
            }
        }
        Spacer(Modifier.height(14.dp))

        // 性能模式
        MenuRow("性能模式", s.performanceMode.displayName, expanded = modeMenu) {
            modeMenu = true
        }
        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
            PerformanceMode.values().forEach { m ->
                DropdownMenuItem(text = { Text("${m.displayName} (${m.analysisFps} fps)") }, onClick = {
                    vm.setPerformanceMode(m)
                    modeMenu = false
                })
            }
        }
        Spacer(Modifier.height(14.dp))

        // 检测灵敏度
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Surface,
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
                        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("检测灵敏度（置信度）", color = Color.White, fontSize = 14.sp)
                Text("${s.brightThreshold}", color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "提示：灵敏度为 AI 识别置信度阈值，识别不到球时可适当调低（130–250）。",
            color = OnSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun MenuRow(
    label: String,
    value: String,
    expanded: Boolean,
    danger: Boolean = false,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
                        .clickable { onOpen() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = if (danger) Error else Color.White,
                fontSize = 14.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    Text(value, color = if (danger) Error else Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Text("  ▾", color = OnSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
