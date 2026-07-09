package cn.sanrolnet.chunkup.client.infection

import cn.sanrolnet.chunkup.ChunkupConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import org.slf4j.LoggerFactory

object InfectionRenderBootstrap {
	private val LOGGER = LoggerFactory.getLogger("chunkup.client.infection.bootstrap")

	@JvmStatic
	fun register() {
		if (!InfectionCoordinator.enabled) {
			LOGGER.info("infection render disabled (chunkup.infectionRender=false)")
			return
		}

		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			InfectionCoordinator.reset()
		}

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			InfectionCoordinator.onClientTick(client)
		}

		LOGGER.info(
			"infection render enabled: {}×{} zone, GPU batch after full load",
			ChunkupConfig.infectionRadiusChunks * 2,
			ChunkupConfig.infectionRadiusChunks * 2,
		)
	}
}
