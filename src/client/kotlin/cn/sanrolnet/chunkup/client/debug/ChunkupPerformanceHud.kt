package cn.sanrolnet.chunkup.client.debug

import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.client.pipeline.SectionLoadPreRenderer
import cn.sanrolnet.chunkup.debug.ChunkupDebugProbe
import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityBatcher
import cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadBatcher

/**
 * Phase 2 统一性能指标：F3 首行汇总，避免散落多行难以对比。
 *
 * 核心指标：pendingLoad、preRender.pending、density.read、gpu.batch
 */
object ChunkupPerformanceHud {
	@JvmStatic
	fun lines(): List<String> {
		if (!ChunkupConfig.f3Debug) {
			return emptyList()
		}
		return listOf(
			"── Chunkup Perf ──",
			summaryLine(),
			configLine(),
		)
	}

	@JvmStatic
	fun summaryLine(): String = buildString {
		append("pendingLoad=${ChunkLoadBatcher.pendingCount()}")
		append(" pendingDensity=${ChunkDensityBatcher.pendingCount()}")
		append(" preRender.pending=${SectionLoadPreRenderer.pendingCount()}")
		if (ChunkupDebugProbe.enabled) {
			append(" density.read=${ChunkupDebugProbe.avgDensityReadMs()}ms")
			append(" gpu.batch=${ChunkupDebugProbe.avgGpuBatchMs()}ms")
		} else {
			append(" probe=off")
		}
	}

	private fun configLine(): String =
		"instantLoad=${ChunkupConfig.instantLoad} preRender=${ChunkupConfig.preRenderOnLoad} " +
			"budget=${ChunkupConfig.preRenderBudgetPerFrame} layered=${ChunkupConfig.layeredSections} " +
			"rate=${ChunkupConfig.layeredSectionsRate} gpuSections=${ChunkupConfig.gpuSections}"
}
