package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator

/**
 * `NoiseBasedChunkGenerator.doFill` 最后两个参数是 **噪声 cell 坐标**（minimumCellY, cellHeight），
 * 不是世界方块 Y。cell 方块高度由 [NoiseSettings.getCellHeight] 决定（主世界通常为 8）。
 */
object ChunkDensityCoords {
	/** 与 `CHUNKUP_ROUTER_SIZE_VERTICAL=2` → `(1<<(2+1))` 一致，无 level 时的回退值 */
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
			return null
		}
		val baseMinY = level?.minBuildHeight ?: -64
		val cellBlockHeight = noiseCellHeightBlocks(level)
		val worldMinY = baseMinY + minimumCellY * cellBlockHeight
		val worldHeight = cellHeight * cellBlockHeight
		if (worldHeight <= 0) {
			return null
		}
		return WorldBounds(worldMinY, worldHeight)
	}
}
