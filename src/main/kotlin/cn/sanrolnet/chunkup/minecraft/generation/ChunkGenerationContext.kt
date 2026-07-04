package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess

data class ChunkGenerationContext(
	val level: ServerLevel?,
	val chunk: ChunkAccess,
	val stage: ChunkGenerationStage,
	val newlyGenerated: Boolean = false,
) {
	val chunkX: Int get() = chunk.pos.x
	val chunkZ: Int get() = chunk.pos.z
}
