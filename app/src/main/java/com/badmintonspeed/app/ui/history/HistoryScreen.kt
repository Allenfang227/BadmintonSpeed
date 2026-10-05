package com.badmintonspeed.app.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.analysis.VideoFrameExtractor
import com.badmintonspeed.app.data.ResultJson
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.Screen
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页（图3）：视频缩略图卡片列表。
 * 左上角时间戳、右上角「全部日期」下拉 + 「全选」。
 */
@Composable
fun HistoryScreen(vm: MainViewModel, records: List<AnalysisRecord>) {
    var dateMenu by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.CHINA) }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        // 顶栏：全部日期下拉 + 全选
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Box {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Surface,
                    modifier = Modifier.clickable { dateMenu = true }
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("全部日期", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("▾", color = OnSurfaceVariant, fontSize = 12.sp)
                    }
                }
                DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("全部日期") },
                        onClick = { dateMenu = false }
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Surface,
                modifier = Modifier.clickable { }
            ) {
                Text(
                    "全选",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无测速记录\n完成一次测速后会自动保存", color = OnSurfaceVariant, fontSize = 16.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(records, key = { it.id }) { r ->
                    HistoryCard(
                        record = r,
                        timeText = timeFmt.format(Date(r.createdAt)),
                        onClick = { vm.replayRecord(r) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(record: AnalysisRecord, timeText: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, record.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val v = File(context.filesDir, "videos/${record.videoName}")
                if (v.exists()) VideoFrameExtractor().getFrame(v, 0L, 960) else null
            }.getOrNull()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16 / 7f)
            .clickable(onClick = onClick)
            .background(Surface, RoundedCornerShape(14.dp))
    ) {
        thumb?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: Box(Modifier.fillMaxSize().background(Color(0xFF12201A)))

        // 左上角时间戳（图3）
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xCC0A1410)
        ) {
            Text(
                timeText,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // 底部信息条：最高球速
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val detail = ResultJson.decode(record.resultJson)
            if (detail != null) {
                Text("最高球速", color = OnSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${"%.0f".format(detail.maxSpeedKmh)} km/h",
                    color = Primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RecordDetailScreen(record: AnalysisRecord, onBack: () -> Unit) {
    val detail = ResultJson.decode(record.resultJson)
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("记录详情", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(record.title, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
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
                        Text("$type  ${"%.1f".format(speed)} km/h", color = Color.White, style = MaterialTheme.typography.bodyMedium)
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
