package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunkSection
import org.slf4j.LoggerFactory

/**
 * 完整 vanilla buildSurface（native C SurfaceRule 引擎，chunkup_surface_rules.h）。
 *
 * 数据流：
 * 1. 扫描 chunk 噪声阶段方块 → SR 块 ID（列 major）+ WORLD_SURFACE_WG 高度图
 * 2. 采集 4×4×(height/4) quart biome 网格 → ChunkupSrBiome ordinal
 * 3. native 规则树求值（原位写回命中块）
 * 4. 应用差异块回 chunk（流体输出标记 postprocessing）
 *
 * biome 取 chunk noise biome（quart 网格），非 BiomeManager 的 zoom 采样——
 * 亚 quart 精度的 biome 边界（≤3 格）与 vanilla 有差异，属已知近似。
 */
object ChunkSurfaceRulesGeneration {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation.surfaceRules")

	private const val CHUNK_SIZE = 16

	/* ChunkupSrBlock（与 native chunkup_surface_rules.h 一致） */
	private const val SR_AIR: Int = 1
	private const val SR_STONE: Int = 12
	private const val SR_DEEPSLATE: Int = 13
	private const val SR_WATER: Int = 28
	private const val SR_LAVA: Int = 29

	/** SR 块 ID → BlockState（index = id，0 跳过） */
	private val SR_BLOCK_STATES: Array<BlockState?> = arrayOfNulls<BlockState>(30).also { arr ->
		arr[1] = Blocks.AIR.defaultBlockState()
		arr[2] = Blocks.BEDROCK.defaultBlockState()
		arr[3] = Blocks.WHITE_TERRACOTTA.defaultBlockState()
		arr[4] = Blocks.ORANGE_TERRACOTTA.defaultBlockState()
		arr[5] = Blocks.TERRACOTTA.defaultBlockState()
		arr[6] = Blocks.YELLOW_TERRACOTTA.defaultBlockState()
		arr[7] = Blocks.BROWN_TERRACOTTA.defaultBlockState()
		arr[8] = Blocks.RED_TERRACOTTA.defaultBlockState()
		arr[9] = Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState()
		arr[10] = Blocks.RED_SAND.defaultBlockState()
		arr[11] = Blocks.RED_SANDSTONE.defaultBlockState()
		arr[12] = Blocks.STONE.defaultBlockState()
		arr[13] = Blocks.DEEPSLATE.defaultBlockState()
		arr[14] = Blocks.DIRT.defaultBlockState()
		arr[15] = Blocks.PODZOL.defaultBlockState()
		arr[16] = Blocks.COARSE_DIRT.defaultBlockState()
		arr[17] = Blocks.MYCELIUM.defaultBlockState()
		arr[18] = Blocks.GRASS_BLOCK.defaultBlockState()
		arr[19] = Blocks.CALCITE.defaultBlockState()
		arr[20] = Blocks.GRAVEL.defaultBlockState()
		arr[21] = Blocks.SAND.defaultBlockState()
		arr[22] = Blocks.SANDSTONE.defaultBlockState()
		arr[23] = Blocks.PACKED_ICE.defaultBlockState()
		arr[24] = Blocks.SNOW_BLOCK.defaultBlockState()
		arr[25] = Blocks.MUD.defaultBlockState()
		arr[26] = Blocks.POWDER_SNOW.defaultBlockState()
		arr[27] = Blocks.ICE.defaultBlockState()
		arr[28] = Blocks.WATER.defaultBlockState()
		arr[29] = Blocks.LAVA.defaultBlockState()
	}

	/** MC biome → ChunkupSrBiome ordinal（native 侧规则条件/温度表索引） */
	private fun classifyBiome(holder: Holder<Biome>): Byte = when {
		holder.`is`(Biomes.FROZEN_PEAKS) -> 1
		holder.`is`(Biomes.SNOWY_SLOPES) -> 2
		holder.`is`(Biomes.JAGGED_PEAKS) -> 3
		holder.`is`(Biomes.GROVE) -> 4
		holder.`is`(Biomes.WINDSWEPT_SAVANNA) -> 5
		holder.`is`(Biomes.WINDSWEPT_GRAVELLY_HILLS) -> 6
		holder.`is`(Biomes.WINDSWEPT_HILLS) -> 7
		holder.`is`(Biomes.MANGROVE_SWAMP) -> 8
		holder.`is`(Biomes.OLD_GROWTH_PINE_TAIGA) -> 9
		holder.`is`(Biomes.OLD_GROWTH_SPRUCE_TAIGA) -> 10
		holder.`is`(Biomes.ICE_SPIKES) -> 11
		holder.`is`(Biomes.MUSHROOM_FIELDS) -> 12
		holder.`is`(Biomes.STONY_PEAKS) -> 13
		holder.`is`(Biomes.STONY_SHORE) -> 14
		holder.`is`(Biomes.DRIPSTONE_CAVES) -> 15
		holder.`is`(Biomes.WARM_OCEAN) -> 16
		holder.`is`(Biomes.BEACH) -> 17
		holder.`is`(Biomes.SNOWY_BEACH) -> 18
		holder.`is`(Biomes.DESERT) -> 19
		holder.`is`(Biomes.BADLANDS) -> 20
		holder.`is`(Biomes.ERODED_BADLANDS) -> 21
		holder.`is`(Biomes.WOODED_BADLANDS) -> 22
		holder.`is`(Biomes.SWAMP) -> 23
		holder.`is`(Biomes.FROZEN_OCEAN) -> 24
		holder.`is`(Biomes.DEEP_FROZEN_OCEAN) -> 25
		holder.`is`(Biomes.LUKEWARM_OCEAN) -> 26
		holder.`is`(Biomes.DEEP_LUKEWARM_OCEAN) -> 27
		else -> 0
	}

	/**
	 * 完整规则引擎入口（gates 已由 [ChunkSurfaceGeneration] 检查）。
	 *
	 * @return true 已处理；false 求值失败（调用方应回退 vanilla buildSurface）
	 */
	@JvmStatic
	fun tryApply(level: ServerLevel, chunk: ChunkAccess, engine: EngineBridge): Boolean {
		val minY = level.minBuildHeight
		val height = level.height

		// 消费 NOISE_FILL 阶段残留的密度缓存（full 路径直接读 chunk 方块，无需 density）
		ChunkDensityCache.take(chunk.pos.x, chunk.pos.z, minY, height)

		val snapshot = snapshotBlocks(chunk, minY, height) ?: return false
		val biomeQuart = collectBiomeQuart(chunk, minY, height)

		val output = engine.generateSurfaceFull(
			chunk.pos.x,
			chunk.pos.z,
			minY,
			height,
			level.seed,
			snapshot.blocks,
			snapshot.heightmap,
			biomeQuart,
		) ?: return false

		if (output.size != snapshot.blocks.size) {
			return false
		}

		return try {
			val changed = applyChanged(chunk, snapshot.blocks, output, minY, height)
			ChunkGenerationHooks.notify(
				ChunkGenerationContext(
					level = level,
					chunk = chunk,
					stage = ChunkGenerationStage.SURFACE,
				),
			)
			ChunkupDebugStats.recordSurfaceBuild(engine.activeComputeBackend())
			LOGGER.debug(
				"chunkup GPU surface full chunk=[{}, {}] changed={} backend={}",
				chunk.pos.x,
				chunk.pos.z,
				changed,
				engine.activeComputeBackend(),
			)
			true
		} catch (e: Exception) {
			LOGGER.error(
				"failed applying chunkup surface rules for [{}, {}]",
				chunk.pos.x,
				chunk.pos.z,
				e,
			)
			false
		}
	}

	/** 扫描噪声阶段方块 → SR 块 ID（列 major `(lx*16+lz)*height + ly`）+ WORLD_SURFACE_WG。 */
	private class Snapshot(val blocks: ByteArray, val heightmap: IntArray)

	private fun snapshotBlocks(chunk: ChunkAccess, minY: Int, height: Int): Snapshot? {
		if (height <= 0 || height and 3 != 0 || minY and 3 != 0) {
			return null
		}
		val blocks = ByteArray(256 * height)
		val heightmap = IntArray(256) { minY - 1 }
		val sectionCount = chunk.sections.size

		for (sectionIndex in 0 until sectionCount) {
			val section = chunk.getSection(sectionIndex)
			if (section.hasOnlyAir()) {
				continue
			}
			val sectionBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex))

			for (localY in 0 until CHUNK_SIZE) {
				val worldY = sectionBaseY + localY
				val ly = worldY - minY
				if (ly < 0 || ly >= height) {
					continue
				}
				for (lz in 0 until CHUNK_SIZE) {
					for (lx in 0 until CHUNK_SIZE) {
						val state = section.getBlockState(lx, localY, lz)
						if (state.isAir) {
							continue
						}
						val sr = toSrId(state)
						val col = (lx * CHUNK_SIZE + lz) * height
						blocks[col + ly] = sr
						val hIdx = lx + lz * CHUNK_SIZE
						if (heightmap[hIdx] < worldY) {
							heightmap[hIdx] = worldY
						}
					}
				}
			}
		}
		return Snapshot(blocks, heightmap)
	}

	/** 噪声阶段方块 → SR ID：air/stone/deepslate/water/lava；其余实心块按"非默认实心"处理。 */
	private fun toSrId(state: BlockState): Byte = when {
		state.`is`(Blocks.STONE) -> SR_STONE.toByte()
		state.`is`(Blocks.DEEPSLATE) -> SR_DEEPSLATE.toByte()
		state.`is`(Blocks.WATER) -> SR_WATER.toByte()
		state.`is`(Blocks.LAVA) -> SR_LAVA.toByte()
		else -> SR_DEEPSLATE.toByte()
	}

	/** 采集 4×4×(height/4) quart biome 网格（`(qx*4+qz)*qyCnt + qy`）。 */
	private fun collectBiomeQuart(chunk: ChunkAccess, minY: Int, height: Int): ByteArray {
		val qyCnt = height shr 2
		val out = ByteArray(16 * qyCnt)
		val baseQX = chunk.pos.x shl 2
		val baseQY = minY shr 2
		val baseQZ = chunk.pos.z shl 2

		for (qx in 0 until 4) {
			for (qz in 0 until 4) {
				val col = (qx * 4 + qz) * qyCnt
				for (qy in 0 until qyCnt) {
					val holder = chunk.getNoiseBiome(baseQX + qx, baseQY + qy, baseQZ + qz)
					out[col + qy] = classifyBiome(holder)
				}
			}
		}
		return out
	}

	/** 应用差异块：与输入快照逐格对比，仅写回规则命中的替换。 */
	private fun applyChanged(
		chunk: ChunkAccess,
		input: ByteArray,
		output: ByteArray,
		minY: Int,
		height: Int,
	): Int {
		var changed = 0
		val sectionCount = chunk.sections.size
		var sectionIndex = Int.MIN_VALUE
		var section: LevelChunkSection? = null
		val pos = BlockPos.MutableBlockPos()
		val baseX = chunk.pos.minBlockX
		val baseZ = chunk.pos.minBlockZ

		for (lx in 0 until CHUNK_SIZE) {
			for (lz in 0 until CHUNK_SIZE) {
				val colBase = (lx * CHUNK_SIZE + lz) * height
				for (ly in 0 until height) {
					val idx = colBase + ly
					val outId = output[idx].toInt() and 0xFF
					if (outId == input[idx].toInt() and 0xFF) {
						continue
					}
					val state = SR_BLOCK_STATES.getOrElse(outId) { null } ?: continue
					val worldY = minY + ly
					val secIdx = chunk.getSectionIndex(worldY)
					if (secIdx < 0 || secIdx >= sectionCount) {
						continue
					}
					if (secIdx != sectionIndex) {
						sectionIndex = secIdx
						section = chunk.getSection(secIdx)
					}
					section!!.setBlockState(lx, worldY and 15, lz, state, false)
					if (!state.fluidState.isEmpty) {
						pos.set(baseX + lx, worldY, baseZ + lz)
						chunk.markPosForPostprocessing(pos)
					}
					changed++
				}
			}
		}
		return changed
	}
}
