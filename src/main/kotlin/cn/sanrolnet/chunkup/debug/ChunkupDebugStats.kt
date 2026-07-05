package cn.sanrolnet.chunkup.debug

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.JniBridge
import cn.sanrolnet.chunkup.bridge.NativeLibraryLoader
import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityBatcher
import cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadBatcher
import cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadPipeline
import java.util.concurrent.atomic.AtomicLong

/**
 * Chunkup 运行时统计，供 F3 调试面板与日志汇总使用。
 */
object ChunkupDebugStats {
	private val densityFillCount = AtomicLong(0)
	private val batchFlushCount = AtomicLong(0)
	private val batchFailCount = AtomicLong(0)
	private val densityBatchFlushCount = AtomicLong(0)
	private val densityBatchFailCount = AtomicLong(0)
	private val lastBackend = java.util.concurrent.atomic.AtomicReference("none")

	@JvmStatic
	fun recordDensityFill(backend: String) {
		densityFillCount.incrementAndGet()
		lastBackend.set(backend)
	}

	@JvmStatic
	fun recordDensityBatchFlush(backend: String, batchSize: Int, failed: Boolean) {
		densityBatchFlushCount.incrementAndGet()
		if (failed) {
			densityBatchFailCount.incrementAndGet()
		}
		lastBackend.set("$backend density x$batchSize")
	}

	@JvmStatic
	fun recordBatchFlush(backend: String, batchSize: Int, failed: Boolean) {
		batchFlushCount.incrementAndGet()
		if (failed) {
			batchFailCount.incrementAndGet()
		}
		lastBackend.set("$backend x$batchSize")
	}

	@JvmStatic
	fun densityFillCount(): Long = densityFillCount.get()

	@JvmStatic
	fun batchFlushCount(): Long = batchFlushCount.get()

	@JvmStatic
	fun batchFailCount(): Long = batchFailCount.get()

	@JvmStatic
	fun lastBackend(): String = lastBackend.get()

	@JvmStatic
	fun lines(): List<String> {
		if (!ChunkupConfig.f3Debug) {
			return emptyList()
		}

		val engine = runCatching { Chunkup.engine }.getOrNull()
		val lines = mutableListOf<String>()
		lines += "Chunkup Engine"
		lines += " backend: ${engine?.activeComputeBackend() ?: "unavailable"}"
		lines += " bridge: ${engine?.backendName ?: "none"} available=${engine?.isAvailable() ?: false}"
		lines += " gpuWorldGen=${ChunkupConfig.gpuWorldGen} instantLoad=${ChunkupConfig.instantLoad} gpuNoiseFill=${ChunkupConfig.gpuNoiseFill}"
		lines += " densityBatch=${ChunkupConfig.gpuDensityBatch} densityBatchSize=${ChunkupConfig.gpuDensityBatchSize}"
		lines += " genGpu=${ChunkupConfig.gpuChunkLoadOnGenerated} loadedGpu=${ChunkupConfig.gpuChunkLoadOnLoaded} skylightApply=${ChunkupConfig.gpuSkylightApply}"
		lines += " forceGpu=${ChunkupConfig.forceGpu} batch=${ChunkupConfig.gpuChunkLoadBatchSize}"
		lines += " nativeDir=${NativeLibraryLoader.nativeLibraryDirectory() ?: "n/a"}"
		lines += " Kotlin: density=${densityFillCount.get()} densityBatch=${densityBatchFlushCount.get()} densityBatchFail=${densityBatchFailCount.get()}"
		lines += " batchFlush=${batchFlushCount.get()} batchFail=${batchFailCount.get()}"
		lines += " chunksProcessed=${ChunkLoadPipeline.processedCount()} pendingLoad=${ChunkLoadBatcher.pendingCount()} pendingDensity=${ChunkDensityBatcher.pendingCount()} last=$lastBackend"
		lines += " stages GPU: NOISE_FILL + chunk-load(GENERATED=${ChunkupConfig.gpuChunkLoadOnGenerated} LOADED=${ChunkupConfig.gpuChunkLoadOnLoaded})"
		for (line in JniBridge.debugStatsLines()) {
			lines += " rust: $line"
		}
		lines += ChunkupDebugProbe.lines()
		return lines
	}
}
