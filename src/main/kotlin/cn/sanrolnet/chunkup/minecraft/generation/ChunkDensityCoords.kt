package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

object ChunkDensityCoords {
	const val NOISE_CELL_HEIGHT_BLOCKS = 8

	data class WorldBounds(val minY: Int, val height: Int)
	@JvmStatic
	fun noiseCellHeightBlocks(level: ServerLevel?): Int {
		if (level == null) {
			return NOISE_CELL_HEIGHT_BLOCKS
		}
		val generator = level.chunkSource.generator
		if (generator is NoiseBasedChunkGenerator) {
			return generator.generatorSettings().value().noiseSettings().cellHeight
		}
		return NOISE_CELL_HEIGHT_BLOCKS
	}
	@JvmStatic
	fun toWorldBounds(level: ServerLevel?, minimumCellY: Int, cellHeight: Int): WorldBounds? {
		if (cellHeight <= 0) {
			return toFullWorldBounds(level)
		}
		val baseMinY = level?.minBuildHeight ?: -64
		val baseMaxY = (level?.height ?: 384) + baseMinY
		val cellBlockHeight = noiseCellHeightBlocks(level)
		val rawMinY = baseMinY + minimumCellY * cellBlockHeight
		val rawMaxY = rawMinY + cellHeight * cellBlockHeight
		val worldMinY = rawMinY.coerceAtLeast(baseMinY)
		val worldMaxY = rawMaxY.coerceAtMost(baseMaxY)
		val worldHeight = worldMaxY - worldMinY
		if (worldHeight <= 0) {
			return null
		}
		return WorldBounds(worldMinY, worldHeight)
	}
	@JvmStatic
	fun toFullWorldBounds(level: ServerLevel?): WorldBounds? {
		val baseMinY = level?.minBuildHeight ?: -64
		val baseHeight = level?.height ?: 384
		return WorldBounds(baseMinY, baseHeight)
	}
}
