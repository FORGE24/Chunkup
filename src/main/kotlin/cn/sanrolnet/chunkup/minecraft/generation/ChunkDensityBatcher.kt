package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.debug.ChunkupDebugProbe
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * CUDA NOISE_FILL 攒批：多 worker 并发 doFill 时合并为一次 GPU launch，减少 JNI/ENGINE 互斥开销。
 * 每个 doFill 调用仍同步等待本 chunk 结果（通过 [CompletableFuture]）。
 */
object ChunkDensityBatcher {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.density.batch")

	private data class BatchKey(
		val minY: Int,
		val height: Int,
		val worldSeed: Long,
	)

	private data class Pending(
		val chunkX: Int,
		val chunkZ: Int,
		val chunk: ChunkAccess,
		val future: CompletableFuture<ChunkDensityFill>,
	)

	private val lock = Any()
	private var batchKey: BatchKey? = null
	private val pending = mutableListOf<Pending>()

	@JvmStatic
	fun pendingCount(): Int {
		synchronized(lock) {
			return pending.size
		}
	}

	@JvmStatic
	fun fill(
		engine: EngineBridge,
		chunk: ChunkAccess,
		minY: Int,
		height: Int,
		level: ServerLevel,
	): ChunkDensityFill? {
		val key = BatchKey(minY, height, level.seed)
		val request = Pending(
			chunkX = chunk.pos.x,
			chunkZ = chunk.pos.z,
			chunk = chunk,
			future = CompletableFuture(),
		)

		synchronized(lock) {
			if (batchKey != null && batchKey != key && pending.isNotEmpty()) {
				flushLocked(engine)
			}
			batchKey = key
			pending += request
			if (pending.size >= ChunkupConfig.gpuDensityBatchSize) {
				flushLocked(engine)
			}
		}

		synchronized(lock) {
			if (!request.future.isDone) {
				flushLocked(engine)
			}
		}

		return try {
			request.future.get(120, TimeUnit.SECONDS)
		} catch (e: Exception) {
			LOGGER.error(
				"chunkup density batch wait failed for [{}, {}]",
				chunk.pos.x,
				chunk.pos.z,
				e,
			)
			null
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

		val chunkXs = IntArray(batch.size) { batch[it].chunkX }
		val chunkZs = IntArray(batch.size) { batch[it].chunkZ }

		val results = run {
			val gpuStarted = System.nanoTime()
			val value = engine.generateChunkDensityBatch(
				chunkXs,
				chunkZs,
				key.minY,
				key.height,
				key.worldSeed,
			)
			ChunkupDebugProbe.record(
				"gpu.density.batch",
				System.nanoTime() - gpuStarted,
				"count=${batch.size}",
			)
			value
		}

		if (results == null || results.size != batch.size) {
			ChunkupDebugStats.recordDensityBatchFlush(engine.activeComputeBackend(), batch.size, failed = true)
			LOGGER.warn(
				"chunkup GPU density batch failed: expected {} results got {}",
				batch.size,
				results?.size ?: 0,
			)
			for (item in batch) {
				item.future.completeExceptionally(IllegalStateException("density batch failed"))
			}
			return
		}

		val backend = engine.activeComputeBackend()
		ChunkupDebugStats.recordDensityBatchFlush(backend, batch.size, failed = false)
		for (i in batch.indices) {
			val fill = results[i]
			if (fill == null) {
				batch[i].future.completeExceptionally(IllegalStateException("density batch item null"))
			} else {
				batch[i].future.complete(fill)
			}
		}

		if (LOGGER.isDebugEnabled) {
			LOGGER.debug(
				"chunkup GPU density batch flushed: count={} backend={}",
				batch.size,
				backend,
			)
		}
	}
}
