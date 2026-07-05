package cn.sanrolnet.chunkup

/**
 * 运行时开关。优先级：JVM System Property > Qt 设置 JSON > 默认值。
 *
 * - `chunkup.gpuChunkLoad.loaded` — LOADED 阶段是否跑 GPU
 * - `chunkup.gpuSkylightApply` — GPU 天空光是否写回 MC
 * - `chunkup.gpuChunkLoad.summaryInterval` — 汇总 debug 间隔
 * - `chunkup.gpuChunkLoad.batchSize` — 攒批大小（1–128）
 * - `chunkup.gpuSections` — 客户端 GPU section mesh（见 SodiumIntegration）
 *
 * JSON 默认路径：`%APPDATA%/Chunkup/settings.json`（由 Qt 设置工具写入）
 */
object ChunkupConfig {
	@JvmStatic
	fun ensureLoaded() {
		ChunkupConfigFile.ensureLoaded()
	}

	val gpuChunkLoadOnLoaded: Boolean
		get() = System.getProperty("chunkup.gpuChunkLoad.loaded", "false").toBoolean()

	val gpuSkylightApply: Boolean
		get() = System.getProperty("chunkup.gpuSkylightApply", "false").toBoolean()

	val gpuChunkLoadSummaryInterval: Int
		get() = System.getProperty("chunkup.gpuChunkLoad.summaryInterval", "256")
			.toIntOrNull()?.coerceAtLeast(1) ?: 256

	val gpuChunkLoadBatchSize: Int
		get() = System.getProperty("chunkup.gpuChunkLoad.batchSize", "32")
			.toIntOrNull()?.coerceIn(1, 128) ?: 32
}
