package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/**
 * 攒批 GPU 区块加载：满 [ChunkupConfig.gpuChunkLoadBatchSize] 或 tick flush 时
 * 单次 dispatch 并行处理整批 chunk。
 */
object ChunkLoadBatcher {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.batch")

	private data class BatchKey(
		val stage: ChunkGenerationStage,
		val minY: Int,
		val height: Int,
		val worldSeed: Long,
		val dimension: ResourceLocation?,
	)

	private data class PendingChunk(
		val context: ChunkGenerationContext,
		val density: FloatArray,
	)

	private val lock = Any()
	private var batchKey: BatchKey? = null
	private val pending = mutableListOf<PendingChunk>()

	@JvmStatic
	fun enqueue(context: ChunkGenerationContext, engine: EngineBridge): Boolean {
		val level = context.level ?: return false
		val minY = level.minBuildHeight
		val height = level.height
		val key = BatchKey(
			context.stage,
			minY,
			height,
			level.seed,
			level.dimension().location(),
		)

		synchronized(lock) {
			if (batchKey != null && batchKey != key && pending.isNotEmpty()) {
				flushLocked(engine)
			}
			batchKey = key

			val density = ChunkDensityReader.read(context.chunk, minY, height)
			pending += PendingChunk(context, density)

			if (pending.size >= ChunkupConfig.gpuChunkLoadBatchSize) {
				flushLocked(engine)
			}
		}
		return true
	}

	@JvmStatic
	fun flush(engine: EngineBridge) {
		synchronized(lock) {
			flushLocked(engine)
		}
	}

	private fun flushLocked(engine: EngineBridge) {
		if (pending.isEmpty()) {
			batchKey = null
			return
		}

		val key = batchKey ?: return
		val batch = pending.toList()
		pending.clear()
		batchKey = null

		val chunkXs = IntArray(batch.size) { batch[it].context.chunkX }
		val chunkZs = IntArray(batch.size) { batch[it].context.chunkZ }
		val densities = FloatArray(batch.size * batch[0].density.size)
		for (i in batch.indices) {
			System.arraycopy(batch[i].density, 0, densities, i * batch[i].density.size, batch[i].density.size)
		}

		val results = engine.processChunkLoadBatch(
			key.stage,
			chunkXs,
			chunkZs,
			key.minY,
			key.height,
			key.worldSeed,
			densities,
		)

		if (results == null || results.size != batch.size) {
			LOGGER.warn(
				"chunkup GPU chunk load batch failed: expected {} results got {}",
				batch.size,
				results?.size ?: 0,
			)
			for (item in batch) {
				ChunkGenerationHooks.notify(item.context)
			}
			return
		}

		val backend = engine.activeComputeBackend()
		for (i in batch.indices) {
			val item = batch[i]
			val result = results[i]
			if (result != null) {
				try {
					if (ChunkupConfig.gpuSkylightApply && item.context.level != null) {
						ChunkSkylightApplier.apply(
							item.context.level,
							item.context.chunk,
							result.skylight,
							key.minY,
							key.height,
						)
					}
					ChunkLoadPipeline.recordBatchProcessed(item.context, backend, batch.size)
				} catch (e: Exception) {
					LOGGER.error(
						"failed applying chunkup chunk load batch item [{}, {}]",
						item.context.chunkX,
						item.context.chunkZ,
						e,
					)
				}
			}
			ChunkGenerationHooks.notify(item.context)
		}

		if (LOGGER.isDebugEnabled) {
			LOGGER.debug(
				"chunkup GPU chunk load batch flushed: count={} stage={} backend={}",
				batch.size,
				key.stage,
				backend,
			)
		}
	}
}
