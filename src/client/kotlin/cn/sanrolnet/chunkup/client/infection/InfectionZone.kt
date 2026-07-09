package cn.sanrolnet.chunkup.client.infection

import cn.sanrolnet.chunkup.ChunkupConfig
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.EmptyLevelChunk

/**
 * 以玩家所在 chunk 为中心的 [diameter]×[diameter] 区域（默认 32×32）。
 */
data class InfectionZone(
	val centerChunkX: Int,
	val centerChunkZ: Int,
	val halfExtent: Int,
) {
	val diameter: Int get() = halfExtent * 2

	fun containsChunk(chunkX: Int, chunkZ: Int): Boolean {
		val dx = kotlin.math.abs(chunkX - centerChunkX)
		val dz = kotlin.math.abs(chunkZ - centerChunkZ)
		return dx < halfExtent && dz < halfExtent
	}

	fun containsSectionOrigin(originX: Int, originZ: Int): Boolean =
		containsChunk(originX shr 4, originZ shr 4)

	fun chunkCount(): Int = diameter * diameter

	fun forEachChunk(block: (chunkX: Int, chunkZ: Int) -> Unit) {
		val minX = centerChunkX - halfExtent
		val minZ = centerChunkZ - halfExtent
		val maxX = centerChunkX + halfExtent - 1
		val maxZ = centerChunkZ + halfExtent - 1
		for (cx in minX..maxX) {
			for (cz in minZ..maxZ) {
				block(cx, cz)
			}
		}
	}

	companion object {
		@JvmStatic
		fun aroundPlayer(chunkX: Int, chunkZ: Int): InfectionZone {
			val half = ChunkupConfig.infectionRadiusChunks
			return InfectionZone(chunkX, chunkZ, half)
		}
	}
}

object InfectionZoneReadiness {
	/**
	 * 客户端 chunk 是否已达 FULL（方块数据完整，可 mesh）。
	 */
	@JvmStatic
	fun countReady(level: ClientLevel, zone: InfectionZone): Int {
		var ready = 0
		zone.forEachChunk { cx, cz ->
			if (isChunkReady(level, cx, cz)) {
				ready++
			}
		}
		return ready
	}

	@JvmStatic
	fun isFullyReady(level: ClientLevel, zone: InfectionZone): Boolean =
		countReady(level, zone) >= zone.chunkCount()

	@JvmStatic
	fun isChunkReady(level: ClientLevel, chunkX: Int, chunkZ: Int): Boolean {
		if (!level.hasChunk(chunkX, chunkZ)) {
			return false
		}
		val chunk = level.getChunk(chunkX, chunkZ)
		return chunk !is EmptyLevelChunk && !chunk.isEmpty
	}

	@JvmStatic
	fun progressLine(level: ClientLevel, zone: InfectionZone): String {
		val ready = countReady(level, zone)
		val total = zone.chunkCount()
		val pct = if (total == 0) 0 else ready * 100 / total
		return "infection zone [${zone.centerChunkX}, ${zone.centerChunkZ}] ${ready}/${total} ($pct%)"
	}
}
