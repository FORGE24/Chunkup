package cn.sanrolnet.chunkup.client

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge
import cn.sanrolnet.chunkup.client.pipeline.ClientSectionPipeline
import cn.sanrolnet.chunkup.client.settings.ChunkupSettingsKeybind
import cn.sanrolnet.chunkup.client.settings.SettingsNative
import cn.sanrolnet.chunkup.client.sodium.LayeredSectionBootstrap
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object ChunkupClient : ClientModInitializer {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client")

	override fun onInitializeClient() {
		if (!ClientEngineBridge.initialize()) {
			LOGGER.warn("Chunkup client engine unavailable")
		}
		if (SodiumIntegration.isLoaded) {
			ClientSectionPipeline.init()
			LayeredSectionBootstrap.register()
			if (SodiumIntegration.useGpuSectionMeshes) {
				LOGGER.info("Chunkup Rust section fast-path enabled (Sodium fallback for complex sections)")
			} else {
				LOGGER.info("Sodium native section meshing active")
			}
		}

		ChunkupSettingsKeybind.register()
		LOGGER.info("Chunkup settings ready (in-game key: ,)")
	}
}
