package cn.sanrolnet.chunkup.minecraft.generation

import java.util.concurrent.ConcurrentHashMap

/**
 * NOISE_FILL 阶段 GPU 密度场缓存，供 GENERATED 阶段 batch 复用，避免重复 [ChunkDensityReader] 全量扫描。
 *
 * 仅用于 GENERATED（尚未写回天空光）；LOADED + 写回光照时必须走 block 读取以保证与地物一致。
 */
object ChunkDensityCache {
	private data class Entry(val minY: Int, val height: Int, val density: FloatArray)

	private val cache = ConcurrentHashMap<Long, Entry>()

	@JvmStatic
	fun store(chunkX: Int, chunkZ: Int, minY: Int, height: Int, density: FloatArray) {
		val key = chunkKey(chunkX, chunkZ)
		cache[key] = Entry(minY, height, density.copyOf())
	}

	@JvmStatic
	fun take(chunkX: Int, chunkZ: Int, minY: Int, height: Int): FloatArray? {
		val key = chunkKey(chunkX, chunkZ)
		val entry = cache.remove(key) ?: return null
		if (entry.minY != minY || entry.height != height || entry.density.size != height * 256) {
			return null
		}
		return entry.density
	}

	private fun chunkKey(chunkX: Int, chunkZ: Int): Long {
		return (chunkX.toLong() and 0xFFFFFFFFL) shl 32 or (chunkZ.toLong() and 0xFFFFFFFFL)
	}
}
