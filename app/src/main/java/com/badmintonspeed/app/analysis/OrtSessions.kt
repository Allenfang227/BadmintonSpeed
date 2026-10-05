package com.badmintonspeed.app.analysis

import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.util.EnumSet

/**
 * v2.31 统一 ONNX Runtime 会话配置：多核 + 超线程感知 + NPU（NNAPI）加速。
 *
 * 修正 v2.30 的错误认知：麒麟 9000S 的大核支持 ARM SMT 同步多线程（广义上的"超线程"，
 * 与 Intel x86 超线程底层实现不同，但确实是真实存在的硬件多线程）。因此
 * `Runtime.getRuntime().availableProcessors()` 返回的是逻辑核数（含 SMT 虚拟核），
 * 线程数上限可以放开；同时刻意留 2 个核给硬件解码(MediaCodec)、界面渲染与系统调度，
 * 避免线程全开导致过热降频/闪退。
 *
 * NPU：麒麟 9000S 搭载达芬奇架构 NPU，通过 Android NNAPI（onnxruntime NNAPI
 * ExecutionProvider）调用。NNAPI 对不支持的算子会自动回退 CPU 执行，因此启用安全；
 * 首次创建带 NNAPI 的会话失败时全局标记回退 CPU，不再重试（防闪退）。
 */
object OrtSessions {

    /** 推理算子内部并行线程数（闭区间 3..8，基于逻辑核数，含 SMT 虚拟核） */
    val intraThreads: Int by lazy {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        (cores - 2).coerceIn(3, 8)
    }

    /** 不同算子之间的并行线程数（多数模型为顺序图，收益有限，给 2） */
    private const val INTER_THREADS = 2

    /**
     * 创建 SessionOptions。
     * @param useNpu 为 true 时尝试挂载 NNAPI（NPU）执行提供器；失败自动全局回退 CPU。
     */
    fun options(useNpu: Boolean = false): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraThreads)
            setInterOpNumThreads(INTER_THREADS)
        }
        if (useNpu && NpuSupport.working) {
            try {
                opts.addNnapi(EnumSet.noneOf(NNAPIFlags::class.java))
            } catch (t: Throwable) {
                NpuSupport.markFailed()
            }
        }
        return opts
    }
}

/**
 * NPU（NNAPI）健康状态：直接尝试挂载 NNAPI，会话创建/推理失败即全局回退 CPU。
 * （onnxruntime 1.17 的 provider 探测 API 为包内私有，无法运行时查询，故以尝试代替探测。）
 */
object NpuSupport {
    @Volatile
    private var _working: Boolean = true
    val working: Boolean get() = _working

    /** NNAPI 会话创建失败时调用：后续全部回退 CPU 多线程 */
    fun markFailed() { _working = false }
}
