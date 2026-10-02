package com.badmintonspeed.app.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.Screen
import com.badmintonspeed.app.ui.components.RecordRow
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant

@Composable
fun HistoryScreen(vm: MainViewModel, records: List<AnalysisRecord>) {
    val unit = vm.settings.speedUnit
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("测速历史", style = MaterialTheme.typography.headlineMedium, color = OnBackground, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("共 ${records.size} 次记录", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无测速记录\n完成一次测速后会自动保存", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(records, key = { it.id }) { r ->
                    RecordRow(
                        record = r,
                        unit = unit,
                        onClick = { vm.goTo(Screen.RecordDetail(r)) },
                        onDelete = { vm.deleteRecord(r.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun RecordDetailScreen(record: AnalysisRecord, onBack: () -> Unit) {
    val detail = com.badmintonspeed.app.data.ResultJson.decode(record.resultJson)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("记录详情", style = MaterialTheme.typography.headlineMedium, color = OnBackground, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(record.title, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                if (detail == null) {
                    Text("记录数据解析失败", color = OnSurfaceVariant)
                } else {
                    Text("最高球速", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${"%.1f".format(detail.maxSpeedKmh)} km/h",
                        color = com.badmintonspeed.app.ui.theme.SpeedColors.forSpeed(detail.maxSpeedKmh),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(12.dp))
                    DetailLine("平均速度", "${"%.1f".format(detail.avgSpeedKmh)} km/h")
                    DetailLine("击球次数", detail.totalHits.toString())
                    DetailLine("杀球次数", detail.smashCount.toString())
                    DetailLine("轨迹点数", detail.trajectoryPoints.toString())
                    Spacer(Modifier.height(12.dp))
                    Text("击球明细", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    detail.hits.forEach { (type, speed) ->
                        Text("$type  ${"%.1f".format(speed)} km/h", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回")
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    RowSpaced(label, value)
}

@Composable
private fun RowSpaced(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(label, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
