package com.badmintonspeed.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.analysis.VideoFrameExtractor
import com.badmintonspeed.app.ui.MainViewModel
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.Primary
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 画面设置页（图4）：中间为实景画面 + 3D 场地示意，
 * 右侧为「显示虚拟场地」开关与 数据1 / 数据2 参数面板。
 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.settings

    Row(Modifier.fillMaxSize()) {
        // ---- 中间画面区（实景 + 虚拟场地示意） ----
        Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            val context = LocalContext.current
            val latestVideo by produceState<String?>(null) {
                value = withContext(Dispatchers.IO) {
                    val h = File(context.filesDir, "history")
                    val v = File(context.filesDir, "videos")
                    h.listFiles { f -> f.name.endsWith(".json") }
                        ?.maxByOrNull { it.lastModified() }
                        ?.let { f ->
                            runCatching {
                                val json = org.json.JSONObject(f.readText())
                                val name = json.getString("videoName")
                                File(v, name).takeIf { it.exists() }?.absolutePath
                            }.getOrNull()
                        }
                }
            }
            val bgBmp by produceState<Bitmap?>(null, latestVideo) {
                value = latestVideo?.let { path ->
                    withContext(Dispatchers.IO) {
                        runCatching { VideoFrameExtractor().getFrame(File(path), 0L, 960) }.getOrNull()
                    }
                }
            }

            Surface(shape = RoundedCornerShape(18.dp), color = Surface) {
                Box(Modifier.fillMaxSize()) {
                    bgBmp?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } ?: Box(Modifier.fillMaxSize().background(Color(0xFF10221A)))

                    // 虚拟场地示意（图4：标注 360°拖动旋转 / IN-OUT / LIVE SPEED）
                    if (s.showVirtualCourt) {
                        CourtPreview(Modifier.fillMaxSize())
                    }

                    // 标注：IN 判定
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xCC0A1410),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("IN", color = Color(0xFF4ADE80), fontSize = 26.sp, fontWeight = FontWeight.Black)
                            Text("界内", color = OnSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                    // LIVE SPEED 标注
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xCC0A1410),
                        modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
                    ) {
                        Text(
                            "LIVE SPEED",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // ---- 右侧参数面板（图4） ----
        Column(
            Modifier
                .width(340.dp)
                .fillMaxHeight()
                .padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
        ) {
            // 显示虚拟场地
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示虚拟场地", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                GreenSwitch(checked = s.showVirtualCourt, onCheckedChange = { s.showVirtualCourt = it })
            }
            Spacer(Modifier.height(28.dp))

            // 数据1
            DataPanel(
                title = "数据1",
                shot = s.data1ShotSpeed, onShot = { s.data1ShotSpeed = it },
                live = s.data1LiveSpeed, onLive = { s.data1LiveSpeed = it },
                hit = s.data1HitType, onHit = { s.data1HitType = it },
                inOut = s.data1InOut, onInOut = { s.data1InOut = it }
            )
            Spacer(Modifier.height(20.dp))

            // 数据2
            DataPanel(
                title = "数据2",
                shot = s.data2ShotSpeed, onShot = { s.data2ShotSpeed = it },
                live = s.data2LiveSpeed, onLive = { s.data2LiveSpeed = it },
                hit = s.data2HitType, onHit = { s.data2HitType = it },
                inOut = s.data2InOut, onInOut = { s.data2InOut = it }
            )
        }
    }
}

@Composable
private fun GreenSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Primary,
            uncheckedThumbColor = Color(0xFF8CA39A),
            uncheckedTrackColor = SurfaceVariant
        )
    )
}

@Composable
private fun DataPanel(
    title: String,
    shot: Boolean, onShot: (Boolean) -> Unit,
    live: Boolean, onLive: (Boolean) -> Unit,
    hit: Boolean, onHit: (Boolean) -> Unit,
    inOut: Boolean, onInOut: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = Surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SettingSwitchRow("出拍速度", shot, onShot)
                SettingSwitchRow("实时速度", live, onLive)
                SettingSwitchRow("击球类型", hit, onHit)
                SettingSwitchRow("界内/界外", inOut, onInOut)
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        GreenSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 透视球场示意（3D 场地）：双打场地 6.10m x 13.40m */
@Composable
private fun CourtPreview(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val courtTop = h * 0.18f
        val courtBottom = h * 0.82f
        val courtW = w * 0.42f

        fun proj(xFrac: Float, yFrac: Float): Offset {
            // 简单透视：x 线性，y 近大远小
            val y = courtTop + (courtBottom - courtTop) * yFrac
            val scaleY = 0.65f + 0.35f * yFrac
            val x = cx + (xFrac - 0.5f) * courtW * scaleY
            return Offset(x, y)
        }

        // 场地外框（绿色）
        val tl = proj(0f, 0f); val tr = proj(1f, 0f)
        val br = proj(1f, 1f); val bl = proj(0f, 1f)
        val line = Color(0xCCFFFFFF)
        val stroke = 3f
        drawLine(line, tl, tr, stroke)
        drawLine(line, tr, br, stroke)
        drawLine(line, br, bl, stroke)
        drawLine(line, bl, tl, stroke)

        // 中线
        drawLine(Color(0xAAFFFFFF), proj(0.5f, 0f), proj(0.5f, 1f), 2f)
        // 网线（中间偏近一点）
        drawLine(Primary, proj(0f, 0.46f), proj(1f, 0.46f), 5f)
        // 发球线（前后场）
        drawLine(Color(0x88FFFFFF), proj(0f, 0.78f), proj(1f, 0.78f), 2f)
        drawLine(Color(0x88FFFFFF), proj(0f, 0.14f), proj(1f, 0.14f), 2f)

        // 落点高亮
        drawCircle(
            color = Color(0x334ADE80),
            radius = 26f,
            center = proj(0.5f, 0.85f),
            style = Stroke(width = 6f)
        )

        // 标注
        drawContext.canvas.nativeCanvas.drawText(
            "360°拖动旋转",
            proj(0.5f, 0.5f).x - 60f,
            proj(0.5f, 0.5f).y - 12f,
            android.graphics.Paint().apply {
                color = 0xFFE7F0EA.toInt(); textSize = 22f
                isAntiAlias = true
            }
        )
        drawContext.canvas.nativeCanvas.drawText(
            "IN/OUT",
            proj(0.5f, 0.5f).x - 26f,
            proj(0.5f, 0.5f).y + 14f,
            android.graphics.Paint().apply {
                color = 0xFFFFD60A.toInt(); textSize = 22f
                isAntiAlias = true
            }
        )
        // 球
        drawCircle(Color(0xFFFFD60A), radius = 10f, center = proj(0.5f, 0.85f))
    }
}
