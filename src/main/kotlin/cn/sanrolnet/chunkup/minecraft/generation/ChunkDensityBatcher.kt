package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.debug.ChunkupDebugProbe
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import cn.sanrolnet.chunkup.log.ChunkupSlLog
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * GPU NOISE_FILL 攒批。GPU/JNI 在锁外执行，避免阻塞其他生成 worker。
 */
object ChunkDensityBatcher {
	private val LOGGER = LoggerFactory.getLogger("chunkup.generation.density.batch")

	private data class BatchKey(
		val dimension: ResourceKey<Level>,
		val minY: Int,
		val height: Int,
		val worldSeed: Long,
	)

	private data class Pending(
		val chunkX: Int,
		val chunkZ: Int,
		val future: CompletableFuture<ChunkDensityFill>,
	)

	private data class FlushSnapshot(
		val key: BatchKey,
		val batch: List<Pending>,
	)

	private val lock = Any()
	private var batchKey: BatchKey? = null
	private val pending = mutableListOf<Pending>()
	private var flushTask: ScheduledFuture<*>? = null
	private var firstEnqueueNanos: Long = 0L

	private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
		Thread(runnable, "chunkup-density-batch").apply { isDaemon = true }
	}

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
		level: ServerLevel?,
		worldSeed: Long,
	): ChunkDensityFill? {
		val dimension = level?.dimension() ?: Level.OVERWORLD
		val key = BatchKey(dimension, minY, height, worldSeed)
		val request = Pending(
			chunkX = chunk.pos.x,
			chunkZ = chunk.pos.z,
			future = CompletableFuture(),
		)

		var snapshot: FlushSnapshot? = null
		synchronized(lock) {
			if (batchKey != null && batchKey != key && pending.isNotEmpty()) {
				snapshot = takeSnapshotLocked()
			}
			batchKey = key
			if (pending.isEmpty()) {
				firstEnqueueNanos = System.nanoTime()
			}
			pending += request

			if (pending.size >= ChunkupConfig.gpuDensityBatchSize) {
				snapshot = takeSnapshotLocked()
			} else if (ChunkupConfig.gpuDensityBatchCoalesceMs > 0L) {
				scheduleDebouncedFlushLocked(engine)
			}
		}

		snapshot?.let { executeFlush(engine, it) }

		if (ChunkupConfig.gpuDensityBatchCoalesceMs <= 0L) {
			drainFlush(engine, force = true)
		}

		return try {
			request.future.get(120, TimeUnit.SECONDS)
		} catch (e: Exception) {
			LOGGER.error("density batch wait failed for [{}, {}]", chunk.pos.x, chunk.pos.z, e)
			null
		}
	}

	private fun drainFlush(engine: EngineBridge, force: Boolean) {
		val snapshot = synchronized(lock) {
			if (!shouldFlushLocked(force)) {
				if (!force && pending.isNotEmpty() && ChunkupConfig.gpuDensityBatchCoalesceMs > 0L) {
					scheduleDebouncedFlushLocked(engine)
				}
				return
			}
			takeSnapshotLocked()
		} ?: return
		executeFlush(engine, snapshot)
	}

	private fun scheduleDebouncedFlushLocked(engine: EngineBridge) {
		val coalesceMs = ChunkupConfig.gpuDensityBatchCoalesceMs
		if (coalesceMs <= 0L) {
			return
		}

		cancelScheduledFlushLocked()
		val elapsedMs = (System.nanoTime() - firstEnqueueNanos) / 1_000_000L
		val maxWaitMs = ChunkupConfig.gpuDensityBatchMaxWaitMs
		val delayMs = if (elapsedMs >= maxWaitMs) 0L else minOf(coalesceMs, maxWaitMs - elapsedMs)

		flushTask = scheduler.schedule(
			{ drainFlush(engine, force = false) },
			delayMs,
			TimeUnit.MILLISECONDS,
		)
	}

	private fun shouldFlushLocked(force: Boolean): Boolean {
		if (pending.isEmpty()) {
			return false
		}
		val elapsedMs = (System.nanoTime() - firstEnqueueNanos) / 1_000_000L
		return force ||
			pending.size >= ChunkupConfig.gpuDensityBatchSize ||
			pending.size >= ChunkupConfig.gpuDensityBatchMinFlush ||
			elapsedMs >= ChunkupConfig.gpuDensityBatchMaxWaitMs
	}

	private fun takeSnapshotLocked(): FlushSnapshot? {
		if (pending.isEmpty()) {
			batchKey = null
			firstEnqueueNanos = 0L
			return null
		}

		cancelScheduledFlushLocked()

		val key = batchKey ?: return null
		val batch = pending.toList()
		pending.clear()
		batchKey = null
		firstEnqueueNanos = 0L
		return FlushSnapshot(key, batch)
	}

	private fun cancelScheduledFlushLocked() {
		flushTask?.cancel(false)
		flushTask = null
	}

	private fun executeFlush(engine: EngineBridge, snapshot: FlushSnapshot) {
		val key = snapshot.key
		val batch = snapshot.batch
		if (batch.isEmpty()) {
			return
		}

		val chunkXs = IntArray(batch.size) { batch[it].chunkX }
		val chunkZs = IntArray(batch.size) { batch[it].chunkZ }

		ChunkupSlLog.infoStart(
			"Density Batch Module",
			"Flushing GPU density batch",
			"Count=${batch.size},MinY=${key.minY},Height=${key.height},Backend=${engine.activeComputeBackend()}",
		)

		val gpuStarted = System.nanoTime()
		val results = engine.generateChunkDensityBatch(
			chunkXs,
			chunkZs,
			key.minY,
			key.height,
			key.worldSeed,
		)
		val gpuMs = (System.nanoTime() - gpuStarted) / 1_000_000

		ChunkupDebugProbe.record("gpu.density.batch", System.nanoTime() - gpuStarted, "count=${batch.size}")

		if (results == null || results.size != batch.size) {
			ChunkupDebugStats.recordDensityBatchFlush(engine.activeComputeBackend(), batch.size, failed = true)
			ChunkupSlLog.warnPerf(
				"Density Batch Module",
				"GPU density batch failed",
				"Expected=${batch.size},Actual=${results?.size ?: 0},ElapsedMs=$gpuMs",
			)
			for (item in batch) {
				item.future.completeExceptionally(IllegalStateException("density batch failed"))
			}
			return
		}

		val backend = engine.activeComputeBackend()
		ChunkupDebugStats.recordDensityBatchFlush(backend, batch.size, failed = false)
		ChunkupSlLog.infoComplete(
			"Density Batch Module",
			"GPU density batch completed",
			"Count=${batch.size},MinY=${key.minY},Height=${key.height},Backend=$backend,ElapsedMs=$gpuMs",
		)

		for (i in batch.indices) {
			val fill = results[i]
			if (fill == null) {
				batch[i].future.completeExceptionally(IllegalStateException("density batch item null"))
			} else {
				batch[i].future.complete(fill)
			}
		}
	}
}
