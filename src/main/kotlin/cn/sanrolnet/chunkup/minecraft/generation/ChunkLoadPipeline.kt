package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * 新区块生成（GENERATED）阶段的 GPU 面剔除 / 天空光预计算。
 *
 * 默认不在 LOADED 阶段运行，避免与原版区块加载、光照引擎冲突。
 * 通过 [ChunkLoadBatcher] 攒批后单次 GPU dispatch 并行处理。
 */
object ChunkLoadPipeline {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.load")
	private val processedCount = AtomicLong(0)

	@JvmStatic
	fun enqueue(context: ChunkGenerationContext, engine: EngineBridge): Boolean {
		if (!shouldProcess(context)) {
			return false
		}

		if (!engine.isAvailable()) {
			return false
		}

		if (context.level == null) {
			return false
		}

		return ChunkLoadBatcher.enqueue(context, engine)
	}

	@JvmStatic
	fun flush(engine: EngineBridge) {
		ChunkLoadBatcher.flush(engine)
	}

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
		val interval = ChunkupConfig.gpuChunkLoadSummaryInterval
		if (count % interval == 0L) {
			LOGGER.debug(
				"chunkup GPU chunk load summary: {} chunks processed (latest backend={}, batch={})",
				count,
				backend,
				batchSize,
			)
		}
	}

	private fun shouldProcess(context: ChunkGenerationContext): Boolean {
		return when (context.stage) {
			ChunkGenerationStage.GENERATED -> context.newlyGenerated
			ChunkGenerationStage.LOADED -> ChunkupConfig.gpuChunkLoadOnLoaded
			else -> false
		}
	}
}
