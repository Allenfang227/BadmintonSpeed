package com.badmintonspeed.app.ui.train

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.theme.Background
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.data.BallLearner
import com.badmintonspeed.app.data.CourtModelRepo
import java.io.File
import com.badmintonspeed.app.ui.components.liveShadow

/**
 * v2.18 模型训练模式（首页第 3 模块）：
 *  - 左栏：模型文件管理——查看 Download/BadmintonSpeed 目录内容（方便转发/找文件）
 *  - 右栏：本地模型训练——上传"红框标注羽毛球"的图片，AI 学习标注区域，
 *          存到 ball_model/，实测时调用该模型辅助检测羽毛球。
 */
@Composable
fun TrainModeScreen(onOpenFiles: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val samplesDir = remember { CourtModelRepo.samplesDir(context) }
    val modelDir = remember { CourtModelRepo.modelDir(context) }

    var sampleCount by remember { mutableStateOf(samplesDir.listFiles()?.size ?: 0) }
    var modelInfo by remember { mutableStateOf(loadModelInfo(modelDir)) }
    var log by remember {
        mutableStateOf("就绪：选择红框标注羽毛球的图片，AI 将学习标注区域\n训练产物保存于 Download/BadmintonSpeed/ball_model/")
    }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        log = "正在解析 ${uris.size} 张图片中的红框…"
        var added = 0; var failed = 0
        try {
            for (u in uris) {
                val input = context.contentResolver.openInputStream(u) ?: continue
                val bmp = BitmapFactory.decodeStream(input)
                if (bmp == null) { failed++; continue }
                val boxes = BallLearner.detectRedBoxes(bmp)
                if (boxes.isEmpty()) {
                    failed++
                } else {
                    var idx = 0
                    for (b in boxes) {
                        val name = "sample_${System.currentTimeMillis()}_${idx}.jpg"
                        if (BallLearner.saveSample(bmp, b, samplesDir, name)) added++
                        idx++
                    }
                }
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (e: Exception) {
            log += "\n解析出错：${e.message}"
        }
        busy = false
        sampleCount = samplesDir.listFiles()?.size ?: 0
        log += "\n完成：新增样本 $added 个${if (failed > 0) "，$failed 张图未检出红框" else ""}"
    }

    val trainer = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { _: List<Uri> ->
        busy = true
        log += "\n开始训练（${sampleCount} 个样本）…"
        val model = BallLearner.train(samplesDir, modelDir)
        if (model != null) {
            CourtModelRepo.exportToPublic(context, "ball_model")
            CourtModelRepo.exportToPublic(context, "ball_samples")
            modelInfo = "模型已训练：${model.count} 个模板"
            log += "\n训练完成：${model.count} 个模板 → ball_model/templates.json（实测自动调用）"
        } else {
            modelInfo = "无模型"
            log += "\n训练失败：ball_samples/ 里没有样本，请先上传红框标注图"
        }
        busy = false
    }

    Column(
        Modifier.fillMaxSize().background(Background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, modifier = Modifier.liveShadow(cornerRadius = 10.dp, strengthDp = 4.dp, alpha = 0.35f)) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("模型训练（本地学习羽毛球外观）", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Text(if (busy) "处理中…" else "样本 $sampleCount 个 | $modelInfo", color = Color(0xFFB0BEC5), fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth().weight(1f)) {
            // ============ 左栏：模型文件管理 ============
            Card(
                Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Text("模型文件", color = Color.White, fontSize = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "训练样本与模型保存在：\nDownload/BadmintonSpeed/\n（labels 场地标注 / ball_samples 球样本 / ball_model 训练产物）",
                        color = Color(0xFF90A4AE), fontSize = 13.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onOpenFiles, Modifier.fillMaxWidth().liveShadow(cornerRadius = 10.dp, strengthDp = 4.dp, alpha = 0.35f)) {
                        Text("打开模型目录（查看/转发）")
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        val ok = CourtModelRepo.exportToPublic(context, null)
                        log += if (ok) "\n已同步全部文件到公共目录" else "\n公共目录同步失败"
                    }, Modifier.fillMaxWidth()) {
                        Text("同步到公共目录")
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        val uri = CourtModelRepo.exportZip(context)
                        if (uri != null) {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "分享模型包"))
                        } else log += "\n打包失败"
                    }, Modifier.fillMaxWidth()) {
                        Text("打包全部为 zip 并分享")
                    }
                }
            }

            // ============ 右栏：模型训练 ============
            Card(
                Modifier.weight(1.4f).fillMaxHeight().padding(start = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Text("本地模型训练", color = Color.White, fontSize = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "① 选择用红框标注好羽毛球的图片（相册多选）\n② AI 解析红框 → 裁剪羽毛球区域存入 ball_samples/\n③ 点击训练 → 生成模板库 ball_model/templates.json\n④ 之后实测视频时自动调用该模型辅助识别羽毛球",
                        color = Color(0xFF90A4AE), fontSize = 13.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Button(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.weight(1f).padding(end = 8.dp).liveShadow(cornerRadius = 10.dp, strengthDp = 4.dp, alpha = 0.35f)) {
                            Text("选择红框标注图")
                        }
                        Button(onClick = { trainer.launch("image/*") }, enabled = !busy && sampleCount > 0, modifier = Modifier.weight(1f).padding(start = 8.dp).liveShadow(cornerRadius = 10.dp, strengthDp = 4.dp, alpha = 0.35f)) {
                            Text("开始训练")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        log,
                        color = Color(0xFFECEFF1),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

private fun loadModelInfo(modelDir: File): String {
    val m = BallLearner.loadModel(modelDir)
    return if (m != null) "模型已训练：${m.count} 个模板" else "无模型（未训练）"
}
