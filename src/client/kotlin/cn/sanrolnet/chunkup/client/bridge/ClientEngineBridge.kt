package cn.sanrolnet.chunkup.client.bridge

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.bridge.JniBridge
import cn.sanrolnet.chunkup.render.SectionBuildPayload
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

object ClientEngineBridge : EngineBridge by JniBridge {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.engine")

	override fun onSectionBuild(
		sectionX: Int,
		sectionY: Int,
		sectionZ: Int,
		blockStates: ByteArray,
	): SectionBuildPayload? {
		val payload = JniBridge.onSectionBuild(sectionX, sectionY, sectionZ, blockStates)
		if (payload == null) {
			LOGGER.trace("section build miss at [{}, {}, {}]", sectionX, sectionY, sectionZ)
		}
		return payload
	}

	fun releaseSectionBuffer(buffer: ByteBuffer) {
		if (!JniBridge.isAvailable()) return
		nativeReleaseSectionBuffer(buffer)
	}

	// =========================================================================
	// ChunkRuntime 委托(设计 §8-12,CPU/GPU 异构运行时壳)
	// =========================================================================

	fun runtimeCreate() = JniBridge.runtimeCreate()
	fun runtimeShutdown() = JniBridge.runtimeShutdown()
	fun runtimeRegisterArchived(dim: Int, x: Int, z: Int) = JniBridge.runtimeRegisterArchived(dim, x, z)
	fun runtimeBeginCpuLoad(dim: Int, x: Int, z: Int) = JniBridge.runtimeBeginCpuLoad(dim, x, z)
	fun runtimeFinishCpuLoad(dim: Int, x: Int, z: Int, payload: ByteArray) = JniBridge.runtimeFinishCpuLoad(dim, x, z, payload)
	fun runtimeBeginGpuStage(dim: Int, x: Int, z: Int) = JniBridge.runtimeBeginGpuStage(dim, x, z)
	fun runtimeFinishGpuStage(dim: Int, x: Int, z: Int, gpuId: Long, size: Int) = JniBridge.runtimeFinishGpuStage(dim, x, z, gpuId, size)
	fun runtimeChunkDataLocation(dim: Int, x: Int, z: Int) = JniBridge.runtimeChunkDataLocation(dim, x, z)
	fun runtimeStats() = JniBridge.runtimeStats()

	@JvmStatic
	private external fun nativeReleaseSectionBuffer(buffer: ByteBuffer)
}
