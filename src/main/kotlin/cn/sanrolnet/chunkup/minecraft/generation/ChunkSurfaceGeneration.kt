package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import net.minecraft.core.BlockPos
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.blending.Blender
import org.slf4j.LoggerFactory

/**
 * GPU buildSurface 薄层：grass/dirt/sand/snow 等顶层 1–4 格，替代原版 surface rule 递归。
 */
object ChunkSurfaceApplier {
	private const val CHUNK_SIZE = 16
	private const val STRIDE_Y = CHUNK_SIZE * CHUNK_SIZE
	private const val LAYERS = 4

	private const val SKIP: Int = 0
	private const val GRASS: Int = 1
	private const val DIRT: Int = 2
	private const val SAND: Int = 3
	private const val SNOW: Int = 4
	private const val STONE: Int = 5
	private const val GRAVEL: Int = 6

	private val GRASS_BLOCK: BlockState = Blocks.GRASS_BLOCK.defaultBlockState()
	private val DIRT_BLOCK: BlockState = Blocks.DIRT.defaultBlockState()
	private val SAND_BLOCK: BlockState = Blocks.SAND.defaultBlockState()
	private val SNOW_BLOCK: BlockState = Blocks.SNOW_BLOCK.defaultBlockState()
	private val STONE_BLOCK: BlockState = Blocks.STONE.defaultBlockState()
	private val GRAVEL_BLOCK: BlockState = Blocks.GRAVEL.defaultBlockState()

	@JvmStatic
	fun apply(
		chunk: ChunkAccess,
		layers: ByteArray,
		density: FloatArray,
		minY: Int,
		height: Int,
	) {
		require(layers.size == STRIDE_Y * LAYERS) { "surface layer size mismatch" }

		for (lz in 0 until CHUNK_SIZE) {
			for (lx in 0 until CHUNK_SIZE) {
				val col = lz * CHUNK_SIZE + lx
				val surfaceLy = findTopSolidLy(density, lx, lz, height) ?: continue
				val surfaceY = minY + surfaceLy
				val layerBase = col * LAYERS

				for (layer in 0 until LAYERS) {
					val blockId = layers[layerBase + layer].toInt() and 0xFF
					if (blockId == SKIP) continue
					val worldY = surfaceY - layer
					if (worldY < minY) continue
					val state = mapBlock(blockId) ?: continue
					val pos = BlockPos(chunk.pos.minBlockX + lx, worldY, chunk.pos.minBlockZ + lz)
					val current = chunk.getBlockState(pos)
					if (current.isAir || current.`is`(Blocks.WATER) || current.`is`(Blocks.LAVA)) {
						continue
					}
					val section = chunk.getSection(chunk.getSectionIndex(worldY))
					section.setBlockState(lx, worldY and 15, lz, state, false)
				}
			}
		}
	}

	private fun findTopSolidLy(density: FloatArray, lx: Int, lz: Int, height: Int): Int? {
		for (ly in height - 1 downTo 0) {
			val idx = ly * STRIDE_Y + lz * CHUNK_SIZE + lx
			if (density[idx] > 0f) {
				return ly
			}
		}
		return null
	}

	private fun mapBlock(id: Int): BlockState? = when (id) {
		GRASS -> GRASS_BLOCK
		DIRT -> DIRT_BLOCK
		SAND -> SAND_BLOCK
		SNOW -> SNOW_BLOCK
		STONE -> STONE_BLOCK
		GRAVEL -> GRAVEL_BLOCK
		else -> null
	}
}

object ChunkSurfaceGeneration {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.surface")

	@JvmStatic
	fun tryReplaceBuildSurface(region: WorldGenRegion, chunk: ChunkAccess): Boolean {
		if (ChunkupConfig.instantLoad || !ChunkupConfig.gpuSurfaceBuild) {
			return false
		}

		val level = region.getLevel()
		if (!GpuGenerationCompat.isOverworld(level)) {
			return false
		}

		val blender = Blender.of(region)
		if (!GpuGenerationCompat.isBlendingCompatible(blender, chunk.pos.x, chunk.pos.z)) {
			return false
		}

		if (!GpuGenerationCompat.isFreshGenerationChunk(chunk)) {
			return false
		}

		val engine = runCatching { Chunkup.engine }.getOrNull() ?: return false
		if (!engine.isAvailable()) {
			return false
		}

		val minY = level.minBuildHeight
		val height = level.height
		val density = ChunkDensityCache.take(chunk.pos.x, chunk.pos.z, minY, height)
			?: ChunkDensityReader.read(chunk, minY, height)

		val biomeKinds = ChunkSurfaceBiomeMapper.mapColumnKinds(chunk)
		val layers = engine.generateSurfaceThin(
			chunk.pos.x,
			chunk.pos.z,
			minY,
			height,
			level.seed,
			density,
			biomeKinds,
		) ?: return false

		return try {
			ChunkSurfaceApplier.apply(chunk, layers, density, minY, height)
			ChunkGenerationHooks.notify(
				ChunkGenerationContext(
					level = level,
					chunk = chunk,
					stage = ChunkGenerationStage.SURFACE,
				),
			)
			ChunkupDebugStats.recordSurfaceBuild(engine.activeComputeBackend())
			LOGGER.debug(
				"chunkup GPU surface thin chunk=[{}, {}] backend={}",
				chunk.pos.x,
				chunk.pos.z,
				engine.activeComputeBackend(),
			)
			true
		} catch (e: Exception) {
			LOGGER.error(
				"failed applying chunkup surface for [{}, {}]",
				chunk.pos.x,
				chunk.pos.z,
				e,
			)
			false
		}
	}
}
