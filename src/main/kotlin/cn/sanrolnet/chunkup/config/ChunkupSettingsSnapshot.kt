package cn.sanrolnet.chunkup.config

/**
 * Chunkup 运行时配置快照，与 Qt settings DLL / JSON 字段一一对应。
 * 字段必须为 @JvmField 供 JNI 直接读写。
 */
class ChunkupSettingsSnapshot {
	@JvmField
	var instantLoad: Boolean = true

	@JvmField
	var gpuWorldGen: Boolean = false

	@JvmField
	var gpuDensityBatch: Boolean = true

	@JvmField
	var forceGpu: Boolean = true

	@JvmField
	var gpuChunkLoadOnGenerated: Boolean = false

	@JvmField
	var gpuChunkLoadOnLoaded: Boolean = false

	@JvmField
	var gpuSkylightApply: Boolean = false

	@JvmField
	var gpuChunkLoadSummaryInterval: Int = 256

	@JvmField
	var gpuChunkLoadBatchSize: Int = 64

	@JvmField
	var gpuSections: Boolean = false

	@JvmField
	var f3Debug: Boolean = true

	@JvmField
	var nativeDir: String = ""

	@JvmField
	var rustLogLevel: String = "warn,chunkup_core=warn"
}
