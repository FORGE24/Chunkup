package cn.sanrolnet.chunkup.client.render

import cn.sanrolnet.chunkup.render.SectionBuildPayload

object ClientSectionMemory {
	fun release(payload: SectionBuildPayload) {
		val buffer = payload.vertexData
		if (buffer.isDirect) {
			cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge.releaseSectionBuffer(buffer)
		}
	}
}
