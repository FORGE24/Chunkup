package cn.sanrolnet.chunkup.client

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge
import cn.sanrolnet.chunkup.client.pipeline.ClientSectionPipeline
import cn.sanrolnet.chunkup.client.settings.ChunkupSettingsKeybind
import cn.sanrolnet.chunkup.client.settings.SettingsNative
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
			if (SodiumIntegration.useGpuSectionMeshes) {
				LOGGER.info("Chunkup GPU section mesh enabled (SOLID/CUTOUT/TRANSLUCENT)")
			} else {
				LOGGER.info("Chunkup running with Sodium native section rendering")
			}
		}

		if (SettingsNative.ensureLoaded()) {
			ChunkupSettingsKeybind.register()
			LOGGER.info("Chunkup Qt settings UI ready (key: ,)")
		}
	}
}
