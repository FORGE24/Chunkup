package cn.sanrolnet.chunkup.config

/**
 * Chunkup 运行时配置快照，与 Qt settings DLL / JSON 字段一一对应。
 * 字段必须为 @JvmField 供 JNI 直接读写。
 */
class ChunkupSettingsSnapshot {
	@JvmField
	var gpuChunkLoadOnLoaded: Boolean = false

	@JvmField
	var gpuSkylightApply: Boolean = false

	@JvmField
	var gpuChunkLoadSummaryInterval: Int = 256

	@JvmField
	var gpuChunkLoadBatchSize: Int = 32

	@JvmField
	var gpuSections: Boolean = true

	@JvmField
	var nativeDir: String = ""

	@JvmField
	var rustLogLevel: String = "warn,chunkup_core=warn"
}
