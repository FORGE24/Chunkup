package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import net.minecraft.world.level.chunk.ChunkAccess
import org.slf4j.LoggerFactory

/**
 * 用 Chunkup 引擎密度场替换 [net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.doFill]。
 */
object ChunkDensityGeneration {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.density")

	@JvmStatic
	fun tryReplaceNoiseFill(chunk: ChunkAccess, minY: Int, height: Int): Boolean {
		val engine = runCatching { Chunkup.engine }.getOrNull() ?: return false
		if (!engine.isAvailable()) {
			return false
		}

		val level = ChunkGenerationWorldContext.get()
		if (level == null) {
			LOGGER.debug("skip chunkup noise fill: no ServerLevel context for [{}, {}]", chunk.pos.x, chunk.pos.z)
			return false
		}

		val fill = engine.generateChunkDensity(
			chunk.pos.x,
			chunk.pos.z,
			minY,
			height,
			level.seed,
		) ?: return false

		return try {
			ChunkDensityApplier.apply(chunk, fill, minY, height)
			ChunkGenerationHooks.notify(
				ChunkGenerationContext(
					level = level,
					chunk = chunk,
					stage = ChunkGenerationStage.NOISE_FILL,
				),
			)
			LOGGER.debug(
				"replaced vanilla noise fill for chunk [{}, {}] minY={} height={}",
				chunk.pos.x,
				chunk.pos.z,
				minY,
				height,
			)
			true
		} catch (e: Exception) {
			LOGGER.error(
				"failed applying chunkup density for [{}, {}]",
				chunk.pos.x,
				chunk.pos.z,
				e,
			)
			false
		}
	}
}
