package com.badmintonspeed.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.DividerColor
import com.badmintonspeed.app.ui.theme.OnBackground
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceBright
import com.badmintonspeed.app.ui.theme.SurfaceVariant

/**
 * 主页（图2）：选择测速模式。
 * 左侧导航栏由 AppRoot 承载；本页为内容区。
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onStart: () -> Unit,
    onTrain: () -> Unit,
    records: List<AnalysisRecord>
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)
    ) {
        // 标题
        Text(
            "选择测速模式",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Select Speed Test Mode",
            color = OnSurfaceVariant,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(40.dp))

        Row(Modifier.fillMaxWidth()) {
            // ---- 实时测速（开发中） ----
            ModeCard(
                modifier = Modifier.weight(1f).padding(end = 28.dp),
                icon = { RadarIcon() },
                title = "实时测速",
                desc = "把手机对准正在打球的场地，本APP会实时计算并显示每帧球的球速并还原出3D羽球轨迹。",
                buttonText = "开发中",
                enabled = false
            )
            // ---- 上传视频测速（进入） ----
            ModeCard(
                modifier = Modifier.weight(1f).padding(start = 28.dp),
                icon = { UploadIcon() },
                title = "上传视频测速",
                desc = "上传录制好的打球视频，本APP会计算整个视频后，再显示每帧球速并还原出3D羽球轨迹。",
                buttonText = "进入",
                enabled = true,
                onClick = onStart
            )
            // ---- 模型训练（进入） ----
            ModeCard(
                modifier = Modifier.weight(1f).padding(start = 28.dp),
                icon = { TrainIcon() },
                title = "模型训练",
                desc = "上传红框标注羽毛球的图片，AI 在本地学习标注区域，训练出专属羽毛球模型，实测时自动调用。",
                buttonText = "进入",
                enabled = true,
                onClick = onTrain
            )
        }

        Spacer(Modifier.weight(1f))

        // 底部统计条
        val best = records.maxOfOrNull { it.maxSpeedKmh } ?: 0f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("累计测速", color = OnSurfaceVariant, fontSize = 14.sp)
            Text(" ${records.size} 次", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(32.dp))
            Text("最高球速", color = OnSurfaceVariant, fontSize = 14.sp)
            Text(
                if (best > 0) " ${"%.0f".format(best)} km/h" else " --",
                color = if (best > 0) Color(0xFFFFD60A) else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor)
        )
    }
}

@Composable
private fun TrainIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
        val c = Color(0xFF4FC3F7)
        drawCircle(color = c, radius = size.minDimension / 2.2f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
        drawCircle(color = c, radius = size.minDimension / 6f)
        // 简易"脑/网络"：三个点连线
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = size.minDimension / 2.2f
        drawCircle(color = c, radius = 4f, center = androidx.compose.ui.geometry.Offset(cx - r * 0.6f, cy - r * 0.5f))
        drawCircle(color = c, radius = 4f, center = androidx.compose.ui.geometry.Offset(cx + r * 0.6f, cy + r * 0.5f))
        drawCircle(color = c, radius = 4f, center = androidx.compose.ui.geometry.Offset(cx + r * 0.5f, cy - r * 0.6f))
    }
}

@Composable
private fun ModeCard(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .width(64.dp)
                    .height(64.dp)
                    .background(SurfaceBright, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) { icon() }
            Spacer(Modifier.height(20.dp))
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                desc,
                color = OnSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Primary else Color(0xFF2A3B31),
                    contentColor = if (enabled) Color(0xFF06120A) else OnSurfaceVariant,
                    disabledContainerColor = Color(0xFF2A3B31),
                    disabledContentColor = OnSurfaceVariant
                ),
                modifier = Modifier.width(140.dp).height(46.dp)
            ) {
                Text(buttonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RadarIcon() {
    Canvas(Modifier.width(34.dp).height(34.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        drawArc(
            color = Primary,
            startAngle = -90f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
        drawLine(
            color = Primary,
            start = c,
            end = Offset(c.x + r, c.y),
            strokeWidth = 3f,
            cap = StrokeCap.Round
        )
        drawCircle(Primary, radius = 4f, center = c)
    }
}

@Composable
private fun UploadIcon() {
    Canvas(Modifier.width(34.dp).height(34.dp)) {
        val w = size.width
        val h = size.height
        val y = h * 0.62f
        drawRoundRect(
            color = Primary,
            topLeft = Offset(w * 0.22f, y),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
        )
        drawLine(
            color = Primary,
            start = Offset(w / 2f, y - h * 0.18f),
            end = Offset(w / 2f, y - h * 0.02f),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Primary,
            start = Offset(w * 0.38f, y - h * 0.18f),
            end = Offset(w / 2f, y - h * 0.34f),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Primary,
            start = Offset(w / 2f, y - h * 0.34f),
            end = Offset(w * 0.62f, y - h * 0.18f),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
    }
}
