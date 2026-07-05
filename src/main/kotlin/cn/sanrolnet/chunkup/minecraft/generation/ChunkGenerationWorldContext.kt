package cn.sanrolnet.chunkup.minecraft.generation

import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * 在异步/嵌套生成调用中传递当前 [ServerLevel]。
 *
 * 注意：`ChunkMap.scheduleChunkGeneration` 在调度后立即返回，Worker 线程执行 `doFill`
 * 时 ThreadLocal 已失效，因此需要 [bindServer] 提供维度级回退。
 */
object ChunkGenerationWorldContext {
	private val currentLevel = ThreadLocal<ServerLevel?>()
	private val levelsByDimension = ConcurrentHashMap<ResourceKey<Level>, ServerLevel>()

	@Volatile
	private var defaultLevel: ServerLevel? = null

	@JvmStatic
	fun bindServer(server: MinecraftServer) {
		levelsByDimension.clear()
		for (level in server.allLevels) {
			levelsByDimension[level.dimension()] = level
		}
		defaultLevel = server.overworld()
	}

	@JvmStatic
	fun push(level: ServerLevel) {
		currentLevel.set(level)
	}

	@JvmStatic
	fun pop() {
		currentLevel.remove()
	}

	@JvmStatic
	fun get(): ServerLevel? = currentLevel.get() ?: defaultLevel

	@JvmStatic
	fun get(dimension: ResourceKey<Level>): ServerLevel? =
		levelsByDimension[dimension] ?: defaultLevel
}
