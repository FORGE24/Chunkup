package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.debug.ChunkupDebugProbe
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.DataLayer

/**
 * 将 GPU 计算的天空光写回 [ServerLevel] 光照引擎。
 *
 * 仅在 LOADED 阶段且 [cn.sanrolnet.chunkup.ChunkupConfig.gpuSkylightApply] 为 true 时调用。
 * GPU 天空光为简化列传播，与原版不完全一致；默认应关闭写回，由原版光照引擎负责。
 */
object ChunkSkylightApplier {
	private const val CHUNK_SIZE = 16
	private const val STRIDE_Y = CHUNK_SIZE * CHUNK_SIZE

	@JvmStatic
	fun apply(level: ServerLevel, chunk: ChunkAccess, skylight: ByteArray, minY: Int, height: Int) {
		require(height > 0) { "height must be positive" }
		val expected = STRIDE_Y * height
		require(skylight.size == expected) {
			"skylight size ${skylight.size} != expected $expected (height=$height)"
		}

		val started = System.nanoTime()
		val lightEngine = level.lightEngine
		val minSection = chunk.getSectionIndex(minY)
		val maxSection = chunk.getSectionIndex(minY + height - 1)

		for (sectionIndex in minSection..maxSection) {
			val section = chunk.getSection(sectionIndex)
			if (section.hasOnlyAir()) {
				continue
			}

			val sectionY = chunk.getSectionYFromSectionIndex(sectionIndex)
			val sectionBaseY = SectionPos.sectionToBlockCoord(sectionY)
			val layer = DataLayer()

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
						val value = skylight[rowBase + lx].toInt() and 0xFF
						layer[lx, relY, lz] = value
					}
				}
			}

			val sectionPos = SectionPos.of(chunk.pos, sectionY)
			lightEngine.queueSectionData(LightLayer.SKY, sectionPos, layer)
		}

		ChunkupDebugProbe.record(
			"skylight.apply",
			System.nanoTime() - started,
			"chunk=[${chunk.pos.x}, ${chunk.pos.z}]",
		)
	}
}
