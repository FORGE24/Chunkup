package cn.sanrolnet.chunkup.client.infection

import cn.sanrolnet.chunkup.client.sodium.SectionBlockStateEncoder
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import net.minecraft.world.level.chunk.LevelChunk
import org.slf4j.LoggerFactory

/**
 * 将感染区内全部 section 方块语义打包为连续字节流，供 C++/CUDA batch mesh。
 *
 * 布局（每个 section）：
 * - int32 sectionOriginX, sectionOriginY, sectionOriginZ
 * - uint8[4096] block semantic（与 [SectionBlockStateEncoder] 一致）
 *
 * Phase 1：仅采集 + 统计；GPU kernel 与 VBO 上传在 native 层待实现。
 */
object InfectionBatchPackager {
	private val LOGGER = LoggerFactory.getLogger("chunkup.client.infection.pack")

	data class PackResult(
		val sectionCount: Int,
		val payloadBytes: Int,
		val skippedAir: Int,
	)

	@JvmStatic
	fun packZone(level: ClientLevel, zone: InfectionZone): PackResult {
		val minY = level.minBuildHeight
		val maxY = level.maxBuildHeight
		val minSection = SectionPos.blockToSectionCoord(minY)
		val maxSection = SectionPos.blockToSectionCoord(maxY - 1)

		var sections = 0
		var bytes = 0
		var skippedAir = 0

		zone.forEachChunk { cx, cz ->
			val chunk = level.getChunk(cx, cz) as? LevelChunk ?: return@forEachChunk
			for (sy in minSection..maxSection) {
				val blockY = SectionPos.sectionToBlockCoord(sy)
				val section = chunk.getSection(chunk.getSectionIndex(blockY))
				if (section.hasOnlyAir()) {
					skippedAir++
					continue
				}
				val originX = cx shl 4
				val originY = SectionPos.sectionToBlockCoord(sy)
				val originZ = cz shl 4
				val encoded = encodeSection(chunk, originX, originY, originZ)
				sections++
				bytes += 12 + encoded.size
			}
		}

		LOGGER.info(
			"infection pack complete: sections={} payload~{}KB skippedAir={} center=[{}, {}]",
			sections,
			bytes / 1024,
			skippedAir,
			zone.centerChunkX,
			zone.centerChunkZ,
		)

		return PackResult(sections, bytes, skippedAir)
	}

	private fun encodeSection(chunk: LevelChunk, originX: Int, originY: Int, originZ: Int): ByteArray {
		// 简化路径：逐 block 读 chunk section（Phase 2 可改为 WorldSlice 批量克隆）
		val out = ByteArray(4096)
		val pos = net.minecraft.core.BlockPos.MutableBlockPos()
		var i = 0
		for (ly in 0 until 16) {
			for (lz in 0 until 16) {
				for (lx in 0 until 16) {
					pos.set(originX + lx, originY + ly, originZ + lz)
					val state = chunk.getBlockState(pos)
					out[i++] = SectionBlockStateEncoder.encodeBlockState(state)
				}
			}
		}
		return out
	}
}
