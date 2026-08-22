package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import cn.sanrolnet.chunkup.log.ChunkupSlLog
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.blending.Blender
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

@Suppress("unused")
private typealias _AsyncApiKeptForFuture = CompletableFuture<Void>

/**
 * 用 Chunkup 引擎密度场替换 [net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.doFill]。
 */
object ChunkDensityGeneration {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.density")
	private val SKIP_LOG_INTERVAL = AtomicLong()

	@JvmStatic
	fun tryReplaceNoiseFill(
		blender: Blender,
		chunk: ChunkAccess,
		minimumCellY: Int,
		cellHeight: Int,
	): Boolean {
		if (ChunkupConfig.instantLoad || !ChunkupConfig.gpuNoiseFill) {
			return false
		}

		if (!GpuGenerationCompat.isFreshGenerationChunk(chunk)) {
			logSkipThrottled("imposter proto chunk", "ChunkX=${chunk.pos.x},ChunkZ=${chunk.pos.z}")
			return false
		}

		val level = ChunkGenerationWorldContext.get()
		if (!GpuGenerationCompat.isOverworld(level)) {
			logSkipThrottled("non-overworld dimension", "ChunkX=${chunk.pos.x},ChunkZ=${chunk.pos.z}")
			return false
		}

		if (!GpuGenerationCompat.isBlendingCompatible(blender, chunk.pos.x, chunk.pos.z)) {
			logSkipThrottled(
				"old-chunk blending required",
				"ChunkX=${chunk.pos.x},ChunkZ=${chunk.pos.z}",
			)
			return false
		}

		val engine = runCatching { Chunkup.engine }.getOrNull() ?: return false
		if (!engine.isAvailable()) {
			logSkipThrottled("engine unavailable", "ChunkX=${chunk.pos.x},ChunkZ=${chunk.pos.z}")
			return false
		}

		val worldSeed = level?.seed ?: ChunkGenerationWorldContext.getWorldSeed()
		if (worldSeed == null) {
			logSkipThrottled(
				"no ServerLevel/world seed for noise fill",
				"ChunkX=${chunk.pos.x},ChunkZ=${chunk.pos.z}",
			)
			return false
		}

		val bounds = ChunkDensityCoords.toWorldBounds(level, minimumCellY, cellHeight)
		if (bounds == null) {
			logSkipThrottled(
				"invalid noise cell bounds",
				"CellY=$minimumCellY,CellHeight=$cellHeight",
			)
			return false
		}

		val fill = if (ChunkupConfig.gpuDensityBatch) {
			ChunkDensityBatcher.fill(
				engine,
				chunk,
				bounds.minY,
				bounds.height,
				level,
				worldSeed,
			)
		} else {
			engine.generateChunkDensity(
				chunk.pos.x,
				chunk.pos.z,
				bounds.minY,
				bounds.height,
				worldSeed,
			)
		} ?: return false

		return applyFill(chunk, fill, level, bounds.minY, bounds.height)
	}

	/**
	 * 异步版本：批量路径入队后立即返回 future，worldgen worker 不阻塞等待 GPU，
	 * 攒批窗口内多 worker 并发入队自然形成 Count>1。
	 * 批量失败时回退 CPU 单 chunk；再失败则异常传播（与 vanilla 噪声异常同级）。
	 */
	@JvmStatic
	fun tryReplaceNoiseFillAsync(
		blender: Blender,
		chunk: ChunkAccess,
		minimumCellY: Int,
		cellHeight: Int,
	): CompletableFuture<ChunkAccess>? {
		if (ChunkupConfig.instantLoad || !ChunkupConfig.gpuNoiseFill) {
			return null
		}
		if (!ChunkupConfig.gpuDensityBatch) {
			return null
		}

		if (!GpuGenerationCompat.isFreshGenerationChunk(chunk)) {
			return null
		}

		val level = ChunkGenerationWorldContext.get()
		if (!GpuGenerationCompat.isOverworld(level)) {
			return null
		}

		if (!GpuGenerationCompat.isBlendingCompatible(blender, chunk.pos.x, chunk.pos.z)) {
			return null
		}

		val engine = runCatching { Chunkup.engine }.getOrNull() ?: return null
		if (!engine.isAvailable()) {
			return null
		}

		val worldSeed = level?.seed ?: ChunkGenerationWorldContext.getWorldSeed() ?: return null

		val bounds = ChunkDensityCoords.toWorldBounds(level, minimumCellY, cellHeight) ?: return null

		return ChunkDensityBatcher.submit(
			engine,
			chunk,
			bounds.minY,
			bounds.height,
			level,
			worldSeed,
		).handle { fill, error ->
			if (error != null || fill == null) {
				LOGGER.warn(
					"GPU density batch failed for [{}, {}], falling back to CPU single chunk",
					chunk.pos.x,
					chunk.pos.z,
					error,
				)
				engine.generateChunkDensity(
					chunk.pos.x,
					chunk.pos.z,
					bounds.minY,
					bounds.height,
					worldSeed,
				) ?: throw IllegalStateException(
					"density generation failed for chunk [${chunk.pos.x}, ${chunk.pos.z}]",
					error,
				)
			} else {
				fill
			}
		}.thenApply { fill ->
			applyFill(chunk, fill, level, bounds.minY, bounds.height)
			chunk
		}
	}

	private fun applyFill(
		chunk: ChunkAccess,
		fill: ChunkDensityFill,
		level: ServerLevel?,
		minY: Int,
		height: Int,
	): Boolean {
		val engine = runCatching { Chunkup.engine }.getOrNull()

		return try {
			ChunkDensityCache.store(chunk.pos.x, chunk.pos.z, minY, height, fill.density)
			ChunkDensityApplier.apply(chunk, fill, minY, height)
			if (level != null) {
				ChunkGenerationHooks.notify(
					ChunkGenerationContext(
						level = level,
						chunk = chunk,
						stage = ChunkGenerationStage.NOISE_FILL,
					),
				)
			}
			ChunkupDebugStats.recordDensityFill(engine?.activeComputeBackend() ?: "unknown")
			true
		} catch (e: Exception) {
			LOGGER.error(
				"failed applying chunkup density for [{}, {}] minY={} height={}",
				chunk.pos.x,
				chunk.pos.z,
				minY,
				height,
				e,
			)
			false
		}
	}

	private fun logSkipThrottled(content: String, params: String) {
		val count = SKIP_LOG_INTERVAL.incrementAndGet()
		if (count <= 3L || count % 64L == 0L) {
			ChunkupSlLog.warnPerf("Density Generation Module", content, params)
		}
	}
}
