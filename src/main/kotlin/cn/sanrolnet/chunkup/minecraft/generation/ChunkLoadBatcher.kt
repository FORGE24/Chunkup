package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.debug.ChunkupDebugProbe
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

/**
 * 攒批 GPU 区块加载：入队后仅在 tick 末或停服时 flush，满批立即标记待 flush。
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
	fun pendingCount(): Int {
		synchronized(lock) {
			return pending.size
		}
	}

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

			val density = resolveDensity(context, minY, height)
			pending += PendingChunk(context, density)
		}
		return true
	}

	/**
	 * @return 是否执行了 flush
	 */
	@JvmStatic
	fun flushDue(engine: EngineBridge, force: Boolean, allowPartial: Boolean): Boolean {
		synchronized(lock) {
			if (pending.isEmpty()) {
				return false
			}
			val batchSize = ChunkupConfig.gpuChunkLoadBatchSize
			val minPartial = ChunkupConfig.gpuChunkLoadMinFlushBatch
			val shouldFlush = force ||
				pending.size >= batchSize ||
				(allowPartial && pending.size >= minPartial)
			if (!shouldFlush) {
				return false
			}
			flushLocked(engine)
			return true
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

		val results = run {
			val gpuStarted = System.nanoTime()
			val value = engine.processChunkLoadBatch(
				key.stage,
				chunkXs,
				chunkZs,
				key.minY,
				key.height,
				key.worldSeed,
				densities,
			)
			ChunkupDebugProbe.record(
				"gpu.batch",
				System.nanoTime() - gpuStarted,
				"count=${batch.size} stage=${key.stage}",
			)
			value
		}

		if (results == null || results.size != batch.size) {
			ChunkupDebugStats.recordBatchFlush(engine.activeComputeBackend(), batch.size, failed = true)
			LOGGER.warn(
				"chunkup GPU chunk load batch failed: expected {} results got {}",
				batch.size,
				results?.size ?: 0,
			)
			return
		}

		val backend = engine.activeComputeBackend()
		ChunkupDebugStats.recordBatchFlush(backend, batch.size, failed = false)
		for (i in batch.indices) {
			val item = batch[i]
			val result = results[i] ?: continue
			try {
				if (
					ChunkupConfig.gpuSkylightApply &&
					key.stage == ChunkGenerationStage.LOADED &&
					item.context.level != null
				) {
					ChunkSkylightApplier.apply(
						item.context.level,
						item.context.chunk,
						result.skylight,
						key.minY,
						key.height,
					)
				}
				ChunkLoadPipeline.recordBatchProcessed(item.context, backend, 1)
			} catch (e: Exception) {
				LOGGER.error(
					"failed applying chunkup chunk load batch item [{}, {}]",
					item.context.chunkX,
					item.context.chunkZ,
					e,
				)
			}
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

	private fun resolveDensity(context: ChunkGenerationContext, minY: Int, height: Int): FloatArray {
		if (context.stage == ChunkGenerationStage.GENERATED) {
			ChunkDensityCache.take(context.chunkX, context.chunkZ, minY, height)?.let { cached ->
				ChunkupDebugProbe.record(
					"density.read",
					0L,
					"cache hit chunk=[${context.chunkX}, ${context.chunkZ}]",
				)
				return cached
			}
		}
		return ChunkDensityReader.read(context.chunk, minY, height)
	}
}
