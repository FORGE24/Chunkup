package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * 从已加载区块方块状态提取密度场，供 GPU 天空光 / 面剔除使用。
 *
 * 与 [ChunkDensityApplier] 语义对齐：solid → density > 0，air/流体 → density ≤ 0。
 */
object ChunkDensityReader {
	private const val CHUNK_SIZE = 16
	private const val STRIDE_Y = CHUNK_SIZE * CHUNK_SIZE
	private const val SOLID_DENSITY = 1.0f
	private const val AIR_DENSITY = -1.0f

	@JvmStatic
	fun read(chunk: ChunkAccess, minY: Int, height: Int): FloatArray {
		require(height > 0) { "height must be positive" }
		val out = FloatArray(STRIDE_Y * height) { AIR_DENSITY }
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ

		for (ly in 0 until height) {
			val worldY = minY + ly
			val layerBase = ly * STRIDE_Y
			for (lz in 0 until CHUNK_SIZE) {
				val rowBase = layerBase + lz * CHUNK_SIZE
				for (lx in 0 until CHUNK_SIZE) {
					val state = chunk.getBlockState(BlockPos(baseX + lx, worldY, baseZ + lz))
					out[rowBase + lx] = if (isSolidForLighting(state)) SOLID_DENSITY else AIR_DENSITY
				}
			}
		}
		return out
	}

	private fun isSolidForLighting(state: BlockState): Boolean {
		return !state.isAir && state.canOcclude()
	}
}
