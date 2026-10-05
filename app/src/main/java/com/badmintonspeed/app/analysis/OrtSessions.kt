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

    /**
     * v2.42 超线程计算开关：用户可随时开/关。
     * - true ：intraThreads 用逻辑核数（含 ARM SMT 虚拟核，麒麟9000S 大核支持），并行度最高
     * - false：intraThreads 按物理核一半估算，只走物理核，更省电更稳
     */
    @Volatile
    var smtEnabled: Boolean = true

    /**
     * v2.43 配置版本号：ONNX 会话的线程数在【创建时】固定，运行时改 intraThreads 不会影响已建会话。
     * 切换超线程开关时自增 epoch；各检测器推理前比对，发现变化就用新配置【重建会话】——开关因此真实生效。
     */
    @Volatile
    private var _configEpoch: Int = 0
    val configEpoch: Int get() = _configEpoch

    /** 切换超线程开关：配置变化时自增 epoch，通知各检测器重建会话（方法名避开属性 setter 的 JVM 签名） */
    fun configureSmt(enabled: Boolean) {
        if (smtEnabled != enabled) {
            smtEnabled = enabled
            _configEpoch++
        }
    }

    /** 推理算子内部并行线程数：受超线程开关控制 */
    val intraThreads: Int
        get() {
            val logical = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            return if (smtEnabled) {
                (logical - 2).coerceIn(3, 8)          // 全逻辑核（含 SMT 虚拟核）
            } else {
                ((logical / 2) - 1).coerceIn(2, 4)    // 仅物理核，保守并行
            }
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
