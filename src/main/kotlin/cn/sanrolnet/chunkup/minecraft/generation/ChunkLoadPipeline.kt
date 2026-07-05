package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * CUDA 区块加载 batch（非阻塞）：GENERATED / LOADED 入队，在 tick 末按批量阈值 flush。
 *
 * 不再在区块生成回调里同步 flush，避免「每 tick 小批 GPU + 阻塞 notify」导致的横向卡顿。
 */
object ChunkLoadPipeline {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.load")
	private val processedCount = AtomicLong(0)
	private var ticksSinceFlush = 0

	@JvmStatic
	fun enqueue(context: ChunkGenerationContext, engine: EngineBridge): Boolean {
		if (!shouldProcess(context)) {
			return false
		}
		if (!engine.isAvailable() || context.level == null) {
			return false
		}
		return ChunkLoadBatcher.enqueue(context, engine).also { enqueued ->
			if (enqueued && ChunkupConfig.gpuChunkLoadFlushInterval <= 1) {
				ChunkLoadBatcher.flushDue(engine, force = false, allowPartial = true)
			}
		}
	}

	@JvmStatic
	fun onServerTickEnd(engine: EngineBridge) {
		if (!ChunkupConfig.gpuChunkLoadEnabled) {
			return
		}
		ticksSinceFlush++
		val interval = ChunkupConfig.gpuChunkLoadFlushInterval
		val allowPartial = ticksSinceFlush >= interval
		if (ChunkLoadBatcher.flushDue(engine, force = false, allowPartial = allowPartial)) {
			ticksSinceFlush = 0
		}
	}

	@JvmStatic
	fun flush(engine: EngineBridge) {
		if (!ChunkupConfig.gpuChunkLoadEnabled) {
			return
		}
		ChunkLoadBatcher.flushDue(engine, force = true, allowPartial = true)
		ticksSinceFlush = 0
	}

	@JvmStatic
	fun processedCount(): Long = processedCount.get()

	internal fun recordBatchProcessed(context: ChunkGenerationContext, backend: String, batchSize: Int) {
		val count = processedCount.addAndGet(batchSize.toLong())
		if (LOGGER.isDebugEnabled) {
			LOGGER.debug(
				"chunkup GPU chunk load stage={} chunk=[{}, {}] backend={} batch={} (#{})",
				context.stage,
				context.chunkX,
				context.chunkZ,
				backend,
				batchSize,
				count,
			)
		}
		val summaryInterval = ChunkupConfig.gpuChunkLoadSummaryInterval
		if (count % summaryInterval == 0L) {
			LOGGER.debug(
				"chunkup GPU chunk load summary: {} chunks processed (latest backend={}, batch={})",
				count,
				backend,
				batchSize,
			)
		}
	}

	private fun shouldProcess(context: ChunkGenerationContext): Boolean {
		if (!ChunkupConfig.gpuChunkLoadEnabled) {
			return false
		}
		return when (context.stage) {
			ChunkGenerationStage.GENERATED ->
				context.newlyGenerated && ChunkupConfig.gpuChunkLoadOnGenerated
			ChunkGenerationStage.LOADED -> ChunkupConfig.gpuChunkLoadOnLoaded
			else -> false
		}
	}
}
