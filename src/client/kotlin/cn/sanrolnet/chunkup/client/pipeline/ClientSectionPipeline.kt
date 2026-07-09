package cn.sanrolnet.chunkup.client.pipeline

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import org.slf4j.LoggerFactory

/**
 * 客户端 section 管线：区块加载时按距离优先预渲染 mesh。
 */
object ClientSectionPipeline {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.pipeline")

	fun init() {
		if (!SodiumIntegration.isLoaded) {
			LOGGER.info("Sodium not loaded; section pipeline disabled")
			return
		}

		ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
			SectionLoadPreRenderer.clear()
		}

		if (ChunkupConfig.preRenderOnLoad) {
			LOGGER.info(
				"Load-time section pre-render enabled (budget={}/tick)",
				ChunkupConfig.preRenderBudgetPerFrame,
			)
		}

		if (!SodiumIntegration.useGpuSectionMeshes) {
			LOGGER.info("Sodium native section meshing active (chunkup.gpuSections=false)")
			return
		}
		LOGGER.info("Chunkup Rust section fast-path enabled; complex sections fall back to Sodium")
	}
}
