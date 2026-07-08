package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.chunk.ChunkAccess

/** 将 MC biome 映射为 native ChunkupSurfaceBiomeKind ordinal。 */
object ChunkSurfaceBiomeMapper {
	private const val DEFAULT: Byte = 0
	private const val DESERT: Byte = 1
	private const val SNOW: Byte = 2
	private const val BEACH: Byte = 3
	private const val BADLANDS: Byte = 4

	@JvmStatic
	fun mapColumnKinds(chunk: ChunkAccess): ByteArray {
		val kinds = ByteArray(256)
		for (lz in 0 until 16) {
			for (lx in 0 until 16) {
				val holder = chunk.getNoiseBiome(lx, 0, lz)
				kinds[lz * 16 + lx] = classify(holder)
			}
		}
		return kinds
	}

	private fun classify(holder: Holder<Biome>): Byte {
		return when {
			holder.`is`(Biomes.DESERT) || holder.`is`(Biomes.WINDSWEPT_SAVANNA) -> DESERT
			holder.`is`(Biomes.SNOWY_PLAINS) ||
				holder.`is`(Biomes.ICE_SPIKES) ||
				holder.`is`(Biomes.SNOWY_TAIGA) ||
				holder.`is`(Biomes.GROVE) ||
				holder.`is`(Biomes.SNOWY_SLOPES) ||
				holder.`is`(Biomes.FROZEN_PEAKS) ||
				holder.`is`(Biomes.JAGGED_PEAKS) -> SNOW
			holder.`is`(Biomes.BEACH) || holder.`is`(Biomes.SNOWY_BEACH) -> BEACH
			holder.`is`(Biomes.BADLANDS) ||
				holder.`is`(Biomes.WOODED_BADLANDS) ||
				holder.`is`(Biomes.ERODED_BADLANDS) -> BADLANDS
			else -> DEFAULT
		}
	}
}
