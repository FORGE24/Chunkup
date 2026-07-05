package cn.sanrolnet.chunkup.client.sodium

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.core.SectionPos
import org.slf4j.LoggerFactory

object LayeredSectionBootstrap {
	private val LOGGER = LoggerFactory.getLogger("chunkup.client.layered")

	@JvmStatic
	fun register() {
		if (!SodiumIntegration.isLoaded) {
			return
		}

		ClientPlayConnectionEvents.JOIN.register { _, _, client ->
			client.player?.let { player ->
				LayeredSectionPolicy.resetAnchor(player.blockY)
			}
		}

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			if (!LayeredSectionPolicy.enabled) {
				return@register
			}
			val level = client.level ?: return@register
			val player = client.player
			if (player != null) {
				val playerSection = SectionPos.blockToSectionCoord(player.blockY)
				if (kotlin.math.abs(playerSection - LayeredSectionPolicy.currentAnchorSectionY()) > 4) {
					LayeredSectionPolicy.resetAnchor(player.blockY)
				}
			}
			LayeredSectionPolicy.onClientTick(SectionPos.blockToSectionCoord(level.minBuildHeight))
		}

		if (LayeredSectionPolicy.enabled) {
			LOGGER.info(
				"Layered section meshing enabled (rate={}, initialDepth={}, headroom={})",
				LayeredSectionPolicy.layersPerTick,
				LayeredSectionPolicy.initialDepth,
				LayeredSectionPolicy.headroomSections,
			)
		}
	}
}
