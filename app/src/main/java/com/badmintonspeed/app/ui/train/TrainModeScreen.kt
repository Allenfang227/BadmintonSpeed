package com.badmintonspeed.app.ui.train

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintonspeed.app.ui.theme.Surface
import com.badmintonspeed.app.data.BallLearner
import com.badmintonspeed.app.data.CourtModelRepo
import java.io.File

/**
 * v2.24 模型训练模式（参考专业路径重排"正确使用方法"）：
 * 导入图片（收集红框标注样本）→ 开始训练（用样本库训练）→ 导出 ONNX（训练完导出 .onnx）
 * 三个功能完全独立：导入只入库，训练只用已入库样本，导出只打包模型。
 * 修复 v2.23 缺陷："开始训练"按钮误用图片选择器（和导入图片同一个功能）。
 */
@Composable
fun TrainModeScreen(onOpenFiles: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val samplesDir = remember { CourtModelRepo.samplesDir(context) }
    val modelDir = remember { CourtModelRepo.modelDir(context) }

    var sampleCount by remember { mutableStateOf(samplesDir.listFiles()?.size ?: 0) }
    var modelInfo by remember { mutableStateOf(loadModelInfo(modelDir)) }
    var log by remember {
        mutableStateOf("流程：① 导入图片（红框标注羽毛球的照片）→ ② 开始训练（学习外观）→ ③ 导出 ONNX（生成 shuttle_user.onnx）\n实测视频时自动调用导出的 ONNX 模型辅助识别。")
    }
    var busy by remember { mutableStateOf(false) }

    // ============ 功能 1：导入图片（只收集样本，不训练） ============
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
        // v2.21：样本立即同步公共目录（卸载不丢）
        if (added > 0) CourtModelRepo.exportToPublic(context, "ball_samples")
        log += "\n导入完成：新增样本 $added 个${if (failed > 0) "，$failed 张图未检出红框" else ""}。样本已入库，可继续导入或开始训练。"
    }

    // ============ 功能 2：开始训练（直接用已入库样本，不再弹图片选择器） ============
    fun doTrain() {
        val count = samplesDir.listFiles()?.filter { it.name.endsWith(".jpg") || it.name.endsWith(".png") }?.size ?: 0
        if (count == 0) {
            log += "\n无法训练：样本库为空，请先点「导入图片」上传红框标注羽毛球的照片。"
            return
        }
        busy = true
        log += "\n开始训练（${count} 个样本）…"
        val model = BallLearner.train(samplesDir, modelDir)
        if (model != null) {
            CourtModelRepo.exportToPublic(context, "ball_model")
            CourtModelRepo.exportToPublic(context, "ball_samples")
            modelInfo = "模型已训练：${model.count} 个模板"
            log += "\n训练完成：${model.count} 个模板已写入 ball_model/templates.json。下一步可「导出 ONNX」。"
        } else {
            modelInfo = "无模型"
            log += "\n训练失败：ball_samples/ 里没有样本，请先导入图片。"
        }
        busy = false
    }

    // ============ 功能 3：导出 ONNX（训练完把模型导成 .onnx 文件） ============
    fun doExportOnnx() {
        val m = BallLearner.loadModel(modelDir)
        if (m == null) {
            log += "\n无法导出：还没有训练好的模型，请先点「开始训练」。"
            return
        }
        busy = true
        log += "\n正在导出 ONNX…"
        val dst = BallLearner.exportOnnx(modelDir)
        if (dst != null && dst.exists()) {
            CourtModelRepo.exportToPublic(context, "ball_model")
            modelInfo = "模型已训练：${m.count} 个模板 | 已导出 ONNX"
            log += "\n导出完成：${dst.name}（${dst.length() / 1024} KB）→ Download/BadmintonSpeed/ball_model/。\n实测视频时会自动调用该 ONNX 模型（ONNX Runtime 推理）；分享给别人也能直接使用。"
        } else {
            log += "\n导出失败：请确认已先「开始训练」。"
        }
        busy = false
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1B14)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack, modifier = Modifier) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("模型训练（导出 ONNX）", color = Color.White, fontSize = 20.sp)
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
                        "训练样本与模型保存在：\nDownload/BadmintonSpeed/\n（labels 场地标注 / ball_samples 球样本 / ball_model 训练产物：templates.json + shuttle_user.onnx）",
                        color = Color(0xFF90A4AE), fontSize = 13.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onOpenFiles, Modifier.fillMaxWidth()) {
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

            // ============ 右栏：训练流程（三个独立功能，按专业路径） ============
            Card(
                Modifier.weight(1.4f).fillMaxHeight().padding(start = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Text("训练流程（正确使用方法）", color = Color.White, fontSize = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "① 导入图片：选择红框标注好羽毛球的照片，解析红框→裁剪球区域→存入样本库（只入库，不训练）\n② 开始训练：用样本库中已有样本训练，生成模板模型（可反复训练叠加）\n③ 导出 ONNX：训练完成后把模型导出为 shuttle_user.onnx，实测自动调用",
                        color = Color(0xFF90A4AE), fontSize = 13.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    // ---- 功能按钮区：三个独立功能 ----
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("① 导入图片（红框标注球）")
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { doTrain() }, enabled = !busy && sampleCount > 0, modifier = Modifier.fillMaxWidth()) {
                        Text("② 开始训练（${sampleCount} 个样本）")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { doExportOnnx() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("③ 导出 ONNX（生成 shuttle_user.onnx）")
                    }

                    Spacer(Modifier.height(14.dp))
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
    val onnx = File(modelDir, "shuttle_user.onnx")
    return if (m != null && onnx.exists()) {
        "模型已训练：${m.count} 个模板 | 已导出 ONNX"
    } else if (m != null) {
        "模型已训练：${m.count} 个模板"
    } else "无模型（未训练）"
}
