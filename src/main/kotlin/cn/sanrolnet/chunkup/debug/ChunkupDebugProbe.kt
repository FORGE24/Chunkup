package cn.sanrolnet.chunkup.debug

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * 运行时探针：替代 IDE 断点，记录各阶段耗时与光照采样差异。
 *
 * 启用：`-Dchunkup.debug.probe=true`（F3 也会显示汇总）
 *
 * 建议 IDE 断点位置（与 [record] 日志对应）：
 * - [ChunkLoadBatcher.flushLocked] label=`gpu.batch`
 * - [ChunkDensityReader.read] label=`density.read`
 * - [ChunkSkylightApplier.apply] label=`skylight.apply`
 */
object ChunkupDebugProbe {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.probe")

	private val densityReadNs = LongAdder()
	private val densityReadCount = LongAdder()
	private val gpuBatchNs = LongAdder()
	private val gpuBatchCount = LongAdder()
	private val skylightApplyNs = LongAdder()
	private val skylightApplyCount = LongAdder()
	private val lastGpuBatchMsAtomic = AtomicLong(0)
	private val lastDensityReadMsAtomic = AtomicLong(0)
	private val lastSkylightApplyMsAtomic = AtomicLong(0)

	@JvmStatic
	val lastSkylightApplyMs: Long
		get() = lastSkylightApplyMsAtomic.get()

	@JvmStatic
	fun avgDensityReadMs(): Long {
		val count = densityReadCount.sum().coerceAtLeast(1)
		return densityReadNs.sum() / count / 1_000_000
	}

	@JvmStatic
	fun avgGpuBatchMs(): Long {
		val count = gpuBatchCount.sum().coerceAtLeast(1)
		return gpuBatchNs.sum() / count / 1_000_000
	}

	@JvmStatic
	fun lastDensityReadMs(): Long = lastDensityReadMsAtomic.get()

	@JvmStatic
	fun lastGpuBatchMs(): Long = lastGpuBatchMsAtomic.get()

	@JvmStatic
	val enabled: Boolean
		get() = System.getProperty("chunkup.debug.probe", "false").toBoolean()

	@JvmStatic
	fun record(label: String, nanos: Long, extra: String? = null) {
		when (label) {
			"density.read" -> {
				densityReadNs.add(nanos)
				densityReadCount.increment()
				lastDensityReadMsAtomic.set(nanos / 1_000_000)
			}
			"gpu.batch" -> {
				gpuBatchNs.add(nanos)
				gpuBatchCount.increment()
				lastGpuBatchMsAtomic.set(nanos / 1_000_000)
			}
			"skylight.apply" -> {
				skylightApplyNs.add(nanos)
				skylightApplyCount.increment()
				lastSkylightApplyMsAtomic.set(nanos / 1_000_000)
			}
		}
		if (enabled && (gpuBatchCount.sum() <= 8L || gpuBatchCount.sum() % 128L == 0L)) {
			val msg = buildString {
				append("[PROBE] ")
				append(label)
				append(' ')
				append(nanos / 1_000_000.0)
				append("ms")
				if (!extra.isNullOrBlank()) {
					append(' ')
					append(extra)
				}
			}
			LOGGER.warn(msg)
		}
	}

	@JvmStatic
	fun lines(): List<String> {
		if (!ChunkupConfig.f3Debug || !enabled) {
			return emptyList()
		}
		val readN = densityReadCount.sum().coerceAtLeast(1)
		val batchN = gpuBatchCount.sum().coerceAtLeast(1)
		val applyN = skylightApplyCount.sum()
		val applyLine = if (applyN > 0L) {
			" skylight.apply avg=${skylightApplyNs.sum() / applyN / 1_000_000}ms last=${lastSkylightApplyMsAtomic.get()}ms count=$applyN"
		} else if (ChunkupConfig.gpuSkylightApply) {
			" skylight.apply waiting (need LOADED+loadedGpu=true)"
		} else {
			" skylight.apply off (vanilla lighting; chunk-load compute only)"
		}
		return listOf(
			"Chunkup Probe (chunkup.debug.probe=true)",
			" density.read avg=${densityReadNs.sum() / readN / 1_000_000}ms last=${lastDensityReadMsAtomic.get()}ms count=${densityReadCount.sum()}",
			" gpu.batch avg=${gpuBatchNs.sum() / batchN / 1_000_000}ms last=${lastGpuBatchMsAtomic.get()}ms count=$batchN",
			applyLine,
		)
	}
}
