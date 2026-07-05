package cn.sanrolnet.chunkup.client.render

import cn.sanrolnet.chunkup.render.SectionBuildPayload
import cn.sanrolnet.chunkup.render.SectionKey
import java.util.concurrent.ConcurrentHashMap

object SectionBuildCache {
	private val cache = ConcurrentHashMap<SectionKey, SectionBuildPayload>()

	fun get(key: SectionKey): SectionBuildPayload? = cache[key]

	fun put(key: SectionKey, payload: SectionBuildPayload) {
		cache[key] = payload
	}

	fun remove(key: SectionKey): SectionBuildPayload? = cache.remove(key)

	fun invalidate(key: SectionKey) {
		val removed = cache.remove(key)
		if (removed != null) {
			ClientSectionMemory.release(removed)
		}
	}

	fun invalidateChunk(chunkX: Int, chunkZ: Int) {
		val keys = cache.keys.filter { key ->
			key.sectionX shr 4 == chunkX && key.sectionZ shr 4 == chunkZ
		}
		for (key in keys) {
			invalidate(key)
		}
	}

	fun clear() {
		for (key in cache.keys.toList()) {
			invalidate(key)
		}
	}
}
