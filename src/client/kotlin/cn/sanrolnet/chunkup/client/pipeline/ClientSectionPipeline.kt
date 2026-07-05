package cn.sanrolnet.chunkup.client.pipeline

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import org.slf4j.LoggerFactory

/**
 * Section mesh 现由 [cn.sanrolnet.chunkup.client.sodium.ChunkupSectionMesher]
 * 在 Sodium worker 线程上直接构建，不再使用 Rust 简化 mesher 后台队列。
 */
object ClientSectionPipeline {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.pipeline")

	fun init() {
		if (!SodiumIntegration.isLoaded) {
			LOGGER.info("Sodium not loaded; section pipeline disabled")
			return
		}
		if (!SodiumIntegration.useGpuSectionMeshes) {
			LOGGER.info("Chunkup section mesher disabled; Sodium native meshing active")
			return
		}
		LOGGER.info("Chunkup section mesher active (Sodium worker thread, 3 render passes)")
	}
}
