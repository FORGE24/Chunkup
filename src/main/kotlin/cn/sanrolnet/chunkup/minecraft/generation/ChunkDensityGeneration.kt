package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import cn.sanrolnet.chunkup.log.ChunkupSlLog
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.blending.Blender
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

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

		return try {
			ChunkDensityCache.store(chunk.pos.x, chunk.pos.z, bounds.minY, bounds.height, fill.density)
			ChunkDensityApplier.apply(chunk, fill, bounds.minY, bounds.height)
			if (level != null) {
				ChunkGenerationHooks.notify(
					ChunkGenerationContext(
						level = level,
						chunk = chunk,
						stage = ChunkGenerationStage.NOISE_FILL,
					),
				)
			}
			ChunkupDebugStats.recordDensityFill(engine.activeComputeBackend())
			true
		} catch (e: Exception) {
			LOGGER.error(
				"failed applying chunkup density for [{}, {}] minY={} height={}",
				chunk.pos.x,
				chunk.pos.z,
				bounds.minY,
				bounds.height,
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
