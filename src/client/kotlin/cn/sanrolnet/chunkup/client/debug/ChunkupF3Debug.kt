package cn.sanrolnet.chunkup.client.debug

import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge
import cn.sanrolnet.chunkup.client.infection.InfectionCoordinator
import cn.sanrolnet.chunkup.client.pipeline.SectionLoadPreRenderer
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
		val lines = mutableListOf<String>()
		lines += ChunkupPerformanceHud.lines()
		lines += SectionLoadPreRenderer.debugLine()
		lines += LayeredSectionPolicy.debugLine()
		lines += SectionMeshStats.lines()
		lines += ChunkupDebugStats.lines()
		lines += InfectionCoordinator.debugLines()
		lines += " client gpuSections=${SodiumIntegration.useGpuSectionMeshes} sodium=${SodiumIntegration.isLoaded}"
		lines += runtimeStatsLine()
		return lines
	}

	private fun runtimeStatsLine(): String {
		val stats = ClientEngineBridge.runtimeStats() ?: return " runtime=unavailable"
		if (stats.size < 3) return " runtime=stats-error"
		val slots = stats[0]
		val cpuBytes = stats[1]
		val gpuBytes = stats[2]
		return " runtime slots=$slots cpu=${formatBytes(cpuBytes)} gpu=${formatBytes(gpuBytes)}"
	}

	private fun formatBytes(bytes: Long): String {
		return when {
			bytes >= 1_048_576L -> "${bytes / 1_048_576L}MB"
			bytes >= 1024L -> "${bytes / 1024L}KB"
			else -> "${bytes}B"
		}
	}
}
