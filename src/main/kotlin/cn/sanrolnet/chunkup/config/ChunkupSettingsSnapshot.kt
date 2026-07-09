package cn.sanrolnet.chunkup.config

/**
 * Chunkup 运行时配置快照，与 Qt settings DLL / JSON 字段一一对应。
 * 字段必须为 @JvmField 供 JNI 直接读写。
 */
class ChunkupSettingsSnapshot {
	@JvmField
	var instantLoad: Boolean = false

	@JvmField
	var gpuWorldGen: Boolean = true

	@JvmField
	var gpuDensityBatch: Boolean = true

	@JvmField
	var forceGpu: Boolean = false

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

	/** 加载时距离优先预渲染（Sodium 协同，默认开启）。 */
	@JvmField
	var preRenderOnLoad: Boolean = true

	/** 每帧预渲染 section 数（默认 8）。 */
	@JvmField
	var preRenderBudgetPerFrame: Int = 8

	/** 地表优先分层 mesh（默认开启）。 */
	@JvmField
	var layeredSections: Boolean = true

	/** 每 tick 向下解锁层数（默认 3）。 */
	@JvmField
	var layeredSectionsRate: Int = 3

	@JvmField
	var f3Debug: Boolean = true

	/** 性能探针日志（默认关闭，调优时开启）。 */
	@JvmField
	var debugProbe: Boolean = false

	@JvmField
	var nativeDir: String = ""

	@JvmField
	var rustLogLevel: String = "warn,chunkup_core=warn"
}
