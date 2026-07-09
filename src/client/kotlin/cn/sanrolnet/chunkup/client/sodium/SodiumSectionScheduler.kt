package cn.sanrolnet.chunkup.client.sodium

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.render.SectionKey
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer
import me.jellysquid.mods.sodium.client.render.util.DeferredRenderTask
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SodiumSectionScheduler {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.sodium.scheduler")
	private val pendingRebuilds = ConcurrentHashMap.newKeySet<SectionKey>()
	private val flushScheduled = AtomicBoolean(false)

	fun scheduleRebuild(key: SectionKey) {
		if (!SodiumIntegration.isLoaded) return
		pendingRebuilds.add(key)
		enqueueFlush()
	}

	@JvmStatic
	fun onGpuMeshApplied(key: SectionKey) {
		pendingRebuilds.remove(key)
	}

	private fun enqueueFlush() {
		if (!flushScheduled.compareAndSet(false, true)) return
		DeferredRenderTask.schedule {
			try {
				flushPendingRebuilds()
			} finally {
				flushScheduled.set(false)
				if (pendingRebuilds.isNotEmpty()) {
					enqueueFlush()
				}
			}
		}
	}

	private fun flushPendingRebuilds() {
		for (key in pendingRebuilds.toList()) {
			if (tryScheduleRebuild(key)) {
				pendingRebuilds.remove(key)
			}
		}
	}

	private fun tryScheduleRebuild(key: SectionKey): Boolean {
		val sectionX = key.sectionX
		val sectionY = key.sectionY
		val sectionZ = key.sectionZ

		val renderer = SodiumWorldRenderer.instanceNullable() ?: return false

		return try {
			if (!renderer.isSectionReady(sectionX, sectionY, sectionZ)) {
				false
			} else {
				renderer.scheduleRebuildForChunk(sectionX, sectionY, sectionZ, true)
				true
			}
		} catch (e: Exception) {
			LOGGER.debug("Failed to schedule Sodium section rebuild at [{}, {}, {}]", sectionX, sectionY, sectionZ, e)
			false
		}
	}
}
