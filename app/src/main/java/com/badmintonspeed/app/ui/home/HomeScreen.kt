package com.badmintonspeed.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.Primary

/** 白色首页配色（v2.22：首页改白，浅色卡） */
private val WhiteBg = Color(0xFFFFFFFF)
private val CardBg = Color(0xFFF2F7F4)
private val CardBorder = Color(0xFFDCE7E1)
private val TitleDark = Color(0xFF10231A)
private val DescGray = Color(0xFF5E7267)
private val IconTile = Color(0xFFE7F0EA)
private val DividerLight = Color(0xFFE3EBE6)

/**
 * 主页（v2.22 白色版）：居中标题 + 浅色大卡，全宽胶囊按钮；
 * 无任何沉浸观感，纯平面白色风格。
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onStart: () -> Unit,
    onTrain: () -> Unit,
    records: List<AnalysisRecord>
) {
    Column(
        Modifier.fillMaxSize().background(WhiteBg).padding(horizontal = 48.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            "选择测速模式",
            color = TitleDark,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(60.dp).height(1.dp).background(DividerLight))
            Text(
                "  Select Speed Test Mode  ",
                color = DescGray,
                fontSize = 15.sp
            )
            Box(Modifier.width(60.dp).height(1.dp).background(DividerLight))
        }
        Spacer(Modifier.height(28.dp))

        Row(Modifier.fillMaxWidth().weight(1f)) {
            ModeCard(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp),
                icon = { RadarIcon() },
                title = "实时测速",
                desc = "把手机对准正在打球的场地，本APP会实时计算并显示每帧球的球速并还原出3D羽球轨迹。",
                buttonText = "开发中",
                active = false,
                onClick = {}
            )
            ModeCard(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp),
                icon = { UploadIcon() },
                title = "上传视频测速",
                desc = "上传录制好的打球视频，本APP会计算整个视频后，再显示每帧球速并还原出3D羽球轨迹。",
                buttonText = "进入",
                active = true,
                onClick = onStart
            )
            ModeCard(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                icon = { TrainIcon() },
                title = "模型训练",
                desc = "上传红框标注羽毛球的图片，AI 在本地学习标注区域，训练出专属羽毛球模型，实测时自动调用。",
                buttonText = "进入",
                active = true,
                onClick = onTrain
            )
        }

        Spacer(Modifier.height(20.dp))
        val best = records.maxOfOrNull { it.maxSpeedKmh } ?: 0f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("累计测速", color = DescGray, fontSize = 14.sp)
            Text(" ${records.size} 次", color = TitleDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(32.dp))
            Text("最高球速", color = DescGray, fontSize = 14.sp)
            Text(
                if (best > 0) " ${"%.0f".format(best)} km/h" else " --",
                color = if (best > 0) Color(0xFFCA8A04) else TitleDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerLight))
    }
}

/** 浅色模式大卡：白底浅灰卡 + 描边 + 浅色图标方块 + 底部全宽胶囊按钮 */
@Composable
private fun ModeCard(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    buttonText: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(66.dp)
                    .background(IconTile, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) { icon() }
            Spacer(Modifier.height(18.dp))
            Text(title, color = TitleDark, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                desc,
                color = DescGray,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(if (active) Primary else Color(0xFF9FB8AA))
                    .clickable(enabled = active, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    buttonText,
                    color = if (active) Color(0xFF06120A) else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun TrainIcon() {
    Canvas(Modifier.size(36.dp)) {
        val c = Color(0xFF0E9BDB)
        drawCircle(
            color = c, radius = size.minDimension / 2.2f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
        )
        drawCircle(color = c, radius = size.minDimension / 7f)
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = size.minDimension / 2.2f
        drawCircle(color = c, radius = 3.5f, center = Offset(cx - r * 0.6f, cy - r * 0.5f))
        drawCircle(color = c, radius = 3.5f, center = Offset(cx + r * 0.6f, cy + r * 0.5f))
        drawCircle(color = c, radius = 3.5f, center = Offset(cx + r * 0.5f, cy - r * 0.6f))
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
