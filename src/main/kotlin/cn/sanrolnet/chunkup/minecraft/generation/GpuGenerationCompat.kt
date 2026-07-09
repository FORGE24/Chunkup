package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ImposterProtoChunk
import net.minecraft.world.level.levelgen.blending.Blender
import kotlin.math.abs

/**
 * GPU 噪声填充的前置条件：仅主世界、非磁盘包装块、旧存档边界需原版 Blender。
 */
object GpuGenerationCompat {
	@JvmStatic
	fun isOverworld(level: ServerLevel?): Boolean =
		level == null || level.dimension() == Level.OVERWORLD

	@JvmStatic
	fun isFreshGenerationChunk(chunk: ChunkAccess): Boolean =
		chunk !is ImposterProtoChunk

	@JvmStatic
	fun isBlendingCompatible(blender: Blender, chunkX: Int, chunkZ: Int): Boolean {
		val out = blender.blendOffsetAndFactor(chunkX, chunkZ)
		return out.alpha >= 0.999 && abs(out.blendingOffset) < 1.0e-6
	}
}
