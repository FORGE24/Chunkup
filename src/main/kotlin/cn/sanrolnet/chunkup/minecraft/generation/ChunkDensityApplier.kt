package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * 将引擎密度场写回 [ChunkAccess]。
 *
 * 索引布局与 `native/common/chunkup_kernel_algo.h` 一致：
 * `index = ly * 256 + lz * 16 + lx`
 *
 * - density > 0 → default_block（stone / deepslate）
 * - density ≤ 0 + fluid=1 → water（Aquifer / 海平面）
 * - density ≤ 0 + fluid=2 → lava
 * - 其余 → air
 */
object ChunkDensityApplier {
	private const val CHUNK_SIZE = 16
	private const val STRIDE_Y = CHUNK_SIZE * CHUNK_SIZE
	private const val SEA_LEVEL = 63

	private const val FLUID_NONE: Byte = 0
	private const val FLUID_WATER: Byte = 1
	private const val FLUID_LAVA: Byte = 2

	private val AIR: BlockState = Blocks.AIR.defaultBlockState()
	private val STONE: BlockState = Blocks.STONE.defaultBlockState()
	private val DEEPSLATE: BlockState = Blocks.DEEPSLATE.defaultBlockState()
	private val WATER: BlockState = Blocks.WATER.defaultBlockState()
	private val LAVA: BlockState = Blocks.LAVA.defaultBlockState()

	@JvmStatic
	fun apply(chunk: ChunkAccess, fill: ChunkDensityFill, minY: Int, height: Int) {
		apply(chunk, fill.density, fill.fluid, minY, height)
	}

	@JvmStatic
	fun apply(chunk: ChunkAccess, density: FloatArray, fluid: ByteArray, minY: Int, height: Int) {
		require(height > 0) { "height must be positive" }
		val expected = STRIDE_Y * height
		require(density.size == expected) {
			"density size ${density.size} != expected $expected (height=$height)"
		}
		require(fluid.size == expected) {
			"fluid size ${fluid.size} != expected $expected (height=$height)"
		}

		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ

		for (ly in 0 until height) {
			val worldY = minY + ly
			val layerBase = ly * STRIDE_Y
			for (lz in 0 until CHUNK_SIZE) {
				val rowBase = layerBase + lz * CHUNK_SIZE
				for (lx in 0 until CHUNK_SIZE) {
					val idx = rowBase + lx
					val state = resolveBlockState(density[idx], fluid[idx], worldY)
					chunk.setBlockState(BlockPos(baseX + lx, worldY, baseZ + lz), state, false)
				}
			}
		}
	}

	private fun resolveBlockState(density: Float, fluid: Byte, worldY: Int): BlockState {
		if (density > 0f) {
			return if (worldY < 0) DEEPSLATE else STONE
		}
		return when (fluid) {
			FLUID_LAVA -> LAVA
			FLUID_WATER -> WATER
			else -> {
				// 海平面以下无 Aquifer 采样时仍填充静态海水
				if (worldY <= SEA_LEVEL) WATER else AIR
			}
		}
	}
}
