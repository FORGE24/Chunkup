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

	@JvmStatic
	private external fun nativeReleaseSectionBuffer(buffer: ByteBuffer)
}
