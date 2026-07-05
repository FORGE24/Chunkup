package cn.sanrolnet.chunkup.client.pipeline

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import org.slf4j.LoggerFactory

/**
 * Section mesh：默认 Sodium 原生；可选 Rust 快路径见 [ChunkBuilderMeshingTaskMixin]。
 */
object ClientSectionPipeline {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.pipeline")

	fun init() {
		if (!SodiumIntegration.isLoaded) {
			LOGGER.info("Sodium not loaded; section pipeline disabled")
			return
		}
		if (!SodiumIntegration.useGpuSectionMeshes) {
			LOGGER.info("Sodium native section meshing active (chunkup.gpuSections=false)")
			return
		}
		LOGGER.info("Chunkup Rust section fast-path enabled; complex sections fall back to Sodium")
	}
}
