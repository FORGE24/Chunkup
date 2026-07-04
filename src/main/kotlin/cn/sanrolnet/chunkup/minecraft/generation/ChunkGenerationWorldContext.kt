package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.server.level.ServerLevel

/**
 * 在异步/嵌套生成调用中传递当前 [ServerLevel]。
 * 由 [cn.sanrolnet.chunkup.mixin.generation.ChunkMapMixin] 在 upgradeChunk 边界维护。
 */
object ChunkGenerationWorldContext {
	private val currentLevel = ThreadLocal<ServerLevel?>()

	@JvmStatic
	fun push(level: ServerLevel) {
		currentLevel.set(level)
	}

	@JvmStatic
	fun pop() {
		currentLevel.remove()
	}

	@JvmStatic
	fun get(): ServerLevel? = currentLevel.get()
}
