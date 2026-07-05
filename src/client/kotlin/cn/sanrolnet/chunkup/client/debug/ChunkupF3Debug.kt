package cn.sanrolnet.chunkup.client.debug

import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.client.sodium.LayeredSectionPolicy
import cn.sanrolnet.chunkup.client.sodium.SectionMeshStats
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import cn.sanrolnet.chunkup.debug.ChunkupDebugStats

object ChunkupF3Debug {
	@JvmStatic
	fun lines(): List<String> {
		if (!ChunkupConfig.f3Debug) {
			return emptyList()
		}
		val lines = ChunkupDebugStats.lines().toMutableList()
		lines += SectionMeshStats.lines()
		lines += LayeredSectionPolicy.debugLine()
		lines += " client gpuSections=${SodiumIntegration.useGpuSectionMeshes} sodium=${SodiumIntegration.isLoaded}"
		return lines
	}
}
