package cn.sanrolnet.chunkup.client.sodium

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.render.SectionKey
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer
import org.slf4j.LoggerFactory

object SodiumSectionScheduler {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.sodium.scheduler")

	fun scheduleRebuild(key: SectionKey) {
		if (!SodiumIntegration.isLoaded) return

		val sectionX = key.sectionX shr 4
		val sectionY = key.sectionY shr 4
		val sectionZ = key.sectionZ shr 4

		try {
			val renderer = SodiumWorldRenderer.instanceNullable() ?: return
			renderer.scheduleRebuildForChunk(sectionX, sectionY, sectionZ, true)
		} catch (e: ReflectiveOperationException) {
			LOGGER.debug("Failed to schedule Sodium section rebuild at [{}, {}, {}]", sectionX, sectionY, sectionZ, e)
		} catch (e: IllegalStateException) {
			LOGGER.trace("Sodium renderer unavailable for section rebuild", e)
		}
	}
}
