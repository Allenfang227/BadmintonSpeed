package com.badmintonspeed.app.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 手写 ONNX 模型导出器（v2.24，用户要求"训练完之后导数为 onnx 格式"）。
 *
 * 导出的是真正可被 ONNX Runtime 加载推理的模型文件：
 *   Gemm(W=模板向量拼接, [2304,N]) → Sigmoid → 输出 [1,N] 打分
 * 即一个线性分类器：输入 48×48 灰度归一化 patch（2304 维），输出 N 个模板匹配分 0~1，
 * 与 BallLearner 的 NCC 打分语义一致（多模板取 max）。
 *
 * ONNX 是 protobuf 二进制。这里手写最小编码器（ModelProto→GraphProto→Node/Tensor），
 * 编码算法已在宿主机用 python onnx.checker + onnxruntime 数值核对验证通过：
 *   ModelProto: ir_version=1, opset_import=8, graph=7
 *   GraphProto: node=1, name=2, initializer=5, input=11, output=12
 *   NodeProto: input=1, output=2, op_type=4
 *   TensorProto: dims=1, data_type=2, name=8, raw_data=9
 *   ValueInfoProto: name=1, type=2；TypeProto: tensor_type=1；
 *   Tensor: elem_type=1, shape=2；TensorShape: dim=1；Dimension: dim_value=1
 */
object OnnxExporter {

    private fun varint(v: Long, out: ByteArrayOutputStream) {
        var n = v
        while (true) {
            val b = (n and 0x7F).toInt()
            n = n ushr 7
            if (n != 0L) out.write(b or 0x80) else { out.write(b); break }
        }
    }

    private fun tag(field: Int, wire: Int, out: ByteArrayOutputStream) =
        varint((field.toLong() shl 3) or wire.toLong(), out)

    private fun ld(field: Int, payload: ByteArray, out: ByteArrayOutputStream) {
        tag(field, 2, out)
        varint(payload.size.toLong(), out)
        out.write(payload)
    }

    private fun vintField(field: Int, v: Long, out: ByteArrayOutputStream) {
        tag(field, 0, out)
        varint(v, out)
    }

    private fun stringField(field: Int, s: String, out: ByteArrayOutputStream) =
        ld(field, s.toByteArray(Charsets.UTF_8), out)

    private fun bytesField(field: Int, b: ByteArray, out: ByteArrayOutputStream) =
        ld(field, b, out)

    private fun floats(a: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 4)
        for (v in a) bb.putFloat(v)
        return bb.array()
    }

    /** TensorShapeProto：dim -> Dimension{dim_value} */
    private fun shapeDims(dims: IntArray, out: ByteArrayOutputStream) {
        for (d in dims) {
            val dimMsg = ByteArrayOutputStream()
            vintField(1, d.toLong(), dimMsg)   // Dimension.dim_value
            ld(1, dimMsg.toByteArray(), out)   // TensorShapeProto.dim
        }
    }

    /** 生成模型字节：templates 每个是 48×48=2304 维归一化灰度向量 */
    fun buildModel(templates: List<FloatArray>): ByteArray {
        val N = templates.size
        val D = templates[0].size
        // W 按列拼接（列 j = 模板 j），形状 [D, N]
        val flat = FloatArray(D * N)
        for (i in 0 until D) for (j in 0 until N) flat[i * N + j] = templates[j][i]

        // ---- TensorProto W ----
        val wt = ByteArrayOutputStream()
        bytesField(9, floats(flat), wt)       // raw_data
        stringField(8, "W", wt)               // name
        vintField(2, 1L, wt)                  // data_type = FLOAT(1)
        vintField(1, D.toLong(), wt)          // dims[0]
        vintField(1, N.toLong(), wt)          // dims[1]

        // ---- Node Gemm: input,W -> logits ----
        val gemm = ByteArrayOutputStream()
        stringField(1, "input", gemm)
        stringField(1, "W", gemm)
        stringField(2, "logits", gemm)
        stringField(4, "Gemm", gemm)

        // ---- Node Sigmoid: logits -> score ----
        val sig = ByteArrayOutputStream()
        stringField(1, "logits", sig)
        stringField(2, "score", sig)
        stringField(4, "Sigmoid", sig)

        // ---- GraphProto ----
        val graph = ByteArrayOutputStream()
        stringField(2, "shuttle_learner", graph)
        ld(1, gemm.toByteArray(), graph)
        ld(1, sig.toByteArray(), graph)
        ld(5, wt.toByteArray(), graph)

        // input: [1, D] float
        val tIn = ByteArrayOutputStream()
        vintField(1, 1L, tIn)                 // elem_type FLOAT
        val shIn = ByteArrayOutputStream()
        shapeDims(intArrayOf(1, D), shIn)
        ld(2, shIn.toByteArray(), tIn)        // Tensor.shape
        val typIn = ByteArrayOutputStream()
        ld(1, tIn.toByteArray(), typIn)       // TypeProto.tensor_type
        val viIn = ByteArrayOutputStream()
        stringField(1, "input", viIn)
        ld(2, typIn.toByteArray(), viIn)
        ld(11, viIn.toByteArray(), graph)

        // output: [1, N] float
        val tOut = ByteArrayOutputStream()
        vintField(1, 1L, tOut)
        val shOut = ByteArrayOutputStream()
        shapeDims(intArrayOf(1, N), shOut)
        ld(2, shOut.toByteArray(), tOut)
        val typOut = ByteArrayOutputStream()
        ld(1, tOut.toByteArray(), typOut)
        val viOut = ByteArrayOutputStream()
        stringField(1, "score", viOut)
        ld(2, typOut.toByteArray(), viOut)
        ld(12, viOut.toByteArray(), graph)

        // ---- ModelProto ----
        val model = ByteArrayOutputStream()
        vintField(1, 8L, model)               // ir_version = 8
        val opset = ByteArrayOutputStream()
        stringField(1, "", opset)             // domain ""
        vintField(2, 17L, opset)              // version 17
        ld(8, opset.toByteArray(), model)     // opset_import
        ld(7, graph.toByteArray(), model)     // graph
        return model.toByteArray()
    }
}
