package com.badmintonspeed.app.analysis

import ai.onnxruntime.OrtSession

/**
 * v2.30 统一 ONNX Runtime 会话配置：真正用上多核。
 *
 * 此前 CourtSeg / Shuttle / 用户 ONNX 三个检测器都用默认 SessionOptions，
 * ONNX Runtime 默认 intraOp 线程数 = 1 → 全程单核（外部检查发现的"只用单核"根因）。
 *
 * 线程数按 CPU 核数动态计算，并刻意留 2 个核给：
 *   硬件解码(MediaCodec)、界面渲染、系统调度 —— 避免线程全开导致过热降频/闪退。
 * 8 核机型（如麒麟 9000S：1 超大核 + 3 大核 + 4 小核）→ 6 条推理线程，
 * 覆盖全部性能核、兼顾小核，既明显快于单核，又不把机器压满。
 */
object OrtSessions {

    /** 推理算子内部并行线程数（闭区间 3..6） */
    val intraThreads: Int by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        (cores - 2).coerceIn(3, 6)
    }

    /** 不同算子之间的并行线程数（多数模型为顺序图，收益有限，给 2） */
    private const val INTER_THREADS = 2

    fun options(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraThreads)
            setInterOpNumThreads(INTER_THREADS)
        }
}
