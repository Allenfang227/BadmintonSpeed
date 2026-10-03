package com.badmintonspeed.app.ui.train

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.data.CourtModelRepo
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.Surface

/** 模型目录文件列表：查看 Download/BadmintonSpeed 内容，逐项分享 / 导入他人模型包 */
@Composable
fun ModelFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tabs = listOf("labels（场地标注）", "ball_samples（球样本）", "ball_model（模型）")
    var tab by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var importLog by remember { mutableStateOf("") }

    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val n = CourtModelRepo.importZip(context, uri)
        importLog = if (n >= 0) "导入成功：合并 ${n} 个文件（样本/标注叠加，模型模板去重）" else "导入失败：无法解析该模型包（需为 App 导出的 model_backup.zip）"
        refresh++   // 触发列表刷新
    }

    Column(Modifier.fillMaxSize().background(Background).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, modifier = Modifier) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("模型目录 Download/BadmintonSpeed", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                val uri = CourtModelRepo.exportZip(context)
                if (uri != null) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "分享全部"))
                }
            }, modifier = Modifier) { Text("打包分享") }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { importZipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                modifier = Modifier            ) { Text("导入模型包") }
        }
        if (importLog.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(importLog, color = if (importLog.startsWith("导入成功")) Color(0xFF4ADE80) else Color(0xFFFFB74D), fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row {
            tabs.forEachIndexed { i, t ->
                Button(
                    onClick = { tab = i },
                    colors = if (tab == i) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    modifier = Modifier.padding(end = 8.dp)                ) { Text(t, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val sub = listOf("labels", "ball_samples", "ball_model")[tab]
        val items = remember(tab, refresh) { CourtModelRepo.listPublic(context, sub) }
        if (items.isEmpty()) {
            Text("目录为空（可先手动标定场地 / 训练模型后回来查看）", color = Color(0xFF90A4AE), fontSize = 13.sp)
        } else {
            LazyColumn {
                items(items) { f ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (f.uri != null) {
                                Button(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = if (f.name.endsWith(".json")) "application/json" else "image/jpeg"
                                        putExtra(Intent.EXTRA_STREAM, f.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "分享 ${f.name}"))
                                }) { Text("分享", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}
