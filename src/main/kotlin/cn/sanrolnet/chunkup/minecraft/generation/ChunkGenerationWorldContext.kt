package cn.sanrolnet.chunkup.minecraft.generation



import net.minecraft.resources.ResourceKey

import net.minecraft.server.MinecraftServer

import net.minecraft.server.level.ServerLevel

import net.minecraft.world.level.Level

import java.util.concurrent.ConcurrentHashMap



/**

 * 在异步/嵌套生成调用中传递当前 [ServerLevel]。

 *

 * `ChunkMap.scheduleChunkGeneration` 在调度后立即返回，Worker 执行 `doFill` 时

 * ThreadLocal 已失效；因此需要 [bindServer] + 在 `ChunkMap.method_17227` 上 push/pop。

 */

object ChunkGenerationWorldContext {

	private val currentLevel = ThreadLocal<ServerLevel?>()

	private val levelsByDimension = ConcurrentHashMap<ResourceKey<Level>, ServerLevel>()



	@Volatile

	private var boundServer: MinecraftServer? = null



	@Volatile

	private var defaultLevel: ServerLevel? = null



	@Volatile

	private var cachedWorldSeed: Long? = null



	@JvmStatic

	fun bindServer(server: MinecraftServer) {

		boundServer = server

		refreshLevels()

	}



	@JvmStatic

	fun refreshLevels() {

		val server = boundServer ?: return

		levelsByDimension.clear()

		for (level in server.allLevels) {

			levelsByDimension[level.dimension()] = level

		}

		defaultLevel = server.overworld()

		cachedWorldSeed = resolveWorldSeed()

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

	fun get(): ServerLevel? {

		currentLevel.get()?.let { return it }

		if (defaultLevel == null) {

			refreshLevels()

		}

		return defaultLevel ?: boundServer?.overworld()

	}



	@JvmStatic

	fun get(dimension: ResourceKey<Level>): ServerLevel? =

		levelsByDimension[dimension] ?: get()



	@JvmStatic

	fun getWorldSeed(): Long? {

		get()?.let { return it.seed }

		cachedWorldSeed?.let { return it }

		return resolveWorldSeed()

	}



	private fun resolveWorldSeed(): Long? {

		boundServer?.overworld()?.let { return it.seed }

		return null

	}

}


