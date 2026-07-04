package cn.sanrolnet.chunkup.client.lod

import cn.sanrolnet.chunkup.render.SectionBuildPayload
import cn.sanrolnet.chunkup.render.SectionKey

/**
 * LOD 模组协作占位接口：由外部 LOD 模组实现并注册，Chunkup 在 section 就绪时可回调。
 */
interface LodSectionBridge {
	fun onSectionPayloadReady(key: SectionKey, payload: SectionBuildPayload)

	fun onSectionInvalidated(key: SectionKey)

	companion object {
		@JvmStatic
		var active: LodSectionBridge? = null
	}
}
