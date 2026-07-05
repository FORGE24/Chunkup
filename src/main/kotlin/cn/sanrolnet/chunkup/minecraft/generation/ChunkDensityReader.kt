package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.debug.ChunkupDebugProbe
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * 从已加载区块方块状态提取密度场，供 GPU 天空光使用。
 *
 * 编码（与 `chunkup_skylight_opacity` 对齐）：
 * - air / 透明：≤ 0
 * - 完全不透光：1.0（opacity=15）
 * - 半透明：getLightBlock / 15
 */
object ChunkDensityReader {
	private const val CHUNK_SIZE = 16
	private const val STRIDE_Y = CHUNK_SIZE * CHUNK_SIZE
	private const val AIR_DENSITY = -1.0f
	private const val SOLID_DENSITY = 1.0f

	@JvmStatic
	fun read(chunk: ChunkAccess, minY: Int, height: Int): FloatArray {
		require(height > 0) { "height must be positive" }
		val started = System.nanoTime()
		val out = FloatArray(STRIDE_Y * height) { AIR_DENSITY }
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ
		val pos = BlockPos.MutableBlockPos()

		for (sectionIndex in chunk.sections.indices) {
			val section = chunk.getSection(sectionIndex)
			if (section.hasOnlyAir()) {
				continue
			}

			val sectionY = chunk.getSectionYFromSectionIndex(sectionIndex)
			val sectionBaseY = SectionPos.sectionToBlockCoord(sectionY)

			for (localY in 0 until 16) {
				val worldY = sectionBaseY + localY
				val ly = worldY - minY
				if (ly < 0 || ly >= height) {
					continue
				}

				val layerBase = ly * STRIDE_Y
				val relY = SectionPos.sectionRelative(worldY)
				for (lz in 0 until CHUNK_SIZE) {
					val rowBase = layerBase + lz * CHUNK_SIZE
					for (lx in 0 until CHUNK_SIZE) {
						val state = section.getBlockState(lx, relY, lz)
						pos.set(baseX + lx, worldY, baseZ + lz)
						out[rowBase + lx] = densityForSkylight(state, chunk, pos)
					}
				}
			}
		}

		ChunkupDebugProbe.record(
			"density.read",
			System.nanoTime() - started,
			"chunk=[${chunk.pos.x}, ${chunk.pos.z}] height=$height",
		)
		return out
	}

	internal fun densityForSkylight(state: BlockState, chunk: ChunkAccess, pos: BlockPos): Float {
		if (state.isAir) {
			return AIR_DENSITY
		}
		val lightBlock = state.getLightBlock(chunk, pos)
		if (lightBlock >= 15) {
			return SOLID_DENSITY
		}
		if (lightBlock <= 0) {
			return 0.0f
		}
		return lightBlock / 15.0f
	}
}
