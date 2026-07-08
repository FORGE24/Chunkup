package cn.sanrolnet.chunkup

/**
 * 运行时开关。优先级：JVM System Property > Qt 设置 JSON > 默认值。
 *
 * - `chunkup.gpuWorldGen` — CUDA 程序化世界生成总开关（强制 instantLoad=false、gpuNoiseFill=true）
 * - `chunkup.instantLoad` — 极速加载：跳过 Chunkup GPU 生成钩子（默认 true）
 * - `chunkup.gpuNoiseFill` — NOISE_FILL 阶段 GPU 密度（gpuWorldGen 或 !instantLoad 时可用）
 * - `chunkup.gpuDensityBatch` — NOISE_FILL CUDA 攒批（默认 gpuWorldGen 时 true）
 * - `chunkup.gpuDensityBatch.size` — 密度攒批大小（1–128，默认 32）
 * - `chunkup.gpuChunkLoad.generated` — GENERATED 阶段 GPU batch（默认 false，游玩时建议关闭）
 * - `chunkup.gpuChunkLoad.loaded` — LOADED 阶段 GPU batch（默认 false）
 * - `chunkup.gpuChunkLoad.flushInterval` — 未凑满批时，至少隔多少 tick 才 flush 部分批（默认 20）
 * - `chunkup.gpuChunkLoad.minFlushBatch` — 部分 flush 的最小 pending 数（默认 16）
 * - `chunkup.gpuSkylightApply` — GPU 天空光是否写回 MC（默认 false，仅 LOADED 阶段）
 *   原因：GPU 天空光算法是简化列传播，与原版 flood-fill 不兼容。
 *   在 GENERATED 阶段写回会覆盖原版刚算好的正确光照 → skylight.mismatch≈65% → 画面过黑。
 *   如需启用，应只在 LOADED 阶段生效（且用 getLightBlock 代替 canOcclude 编码密度）。
 *   当前实现已改为仅在 LOADED 阶段写回，但仍默认关闭以确保安全。
 * - `chunkup.gpuChunkLoad.summaryInterval` — 汇总 debug 间隔
 * - `chunkup.gpuChunkLoad.batchSize` — 攒批大小（1–128）
 * - `chunkup.gpuSections` — 客户端 GPU section mesh（见 SodiumIntegration）
 * - `chunkup.f3Debug` — F3 调试面板显示 Chunkup 详情
 *
 * JSON 默认路径：`%APPDATA%/Chunkup/settings.json`（由 Qt 设置工具写入）
 */
object ChunkupConfig {
	@JvmStatic
	fun ensureLoaded() {
		ChunkupConfigFile.ensureLoaded()
	}

	val forceGpu: Boolean
		get() = System.getProperty("chunkup.forceGpu", "true").toBoolean()

	/**
	 * CUDA 程序化世界生成：启用完整 GPU 密度管线（与 instantLoad 互斥）。
	 * 启动示例：`-Dchunkup.gpuWorldGen=true`
	 */
	val gpuWorldGen: Boolean
		get() = System.getProperty("chunkup.gpuWorldGen", "false").toBoolean()

	/**
	 * 极速区块加载：不在生成 worker 上同步调用 CUDA/JNI，避免 ENGINE 互斥锁排队。
	 * 开启后 Chunkup 生成 mixin 基本 no-op，由原版 + Sodium 负责 mesh。
	 * [gpuWorldGen] 为 true 时强制关闭。
	 */
	val instantLoad: Boolean
		get() = !gpuWorldGen && System.getProperty("chunkup.instantLoad", "true").toBoolean()

	val gpuNoiseFill: Boolean
		get() = gpuWorldGen ||
			(!instantLoad && System.getProperty("chunkup.gpuNoiseFill", "true").toBoolean())

	val gpuDensityBatch: Boolean
		get() = gpuWorldGen ||
			(!instantLoad && System.getProperty("chunkup.gpuDensityBatch", "true").toBoolean())

	val gpuDensityBatchSize: Int
		get() = System.getProperty("chunkup.gpuDensityBatch.size", if (gpuWorldGen) "32" else "16")
			.toIntOrNull()?.coerceIn(1, 128) ?: if (gpuWorldGen) 32 else 16

	/** GPU buildSurface 薄层（grass/dirt/sand），默认 gpuWorldGen 时开启。 */
	val gpuSurfaceBuild: Boolean
		get() = gpuWorldGen ||
			(!instantLoad && System.getProperty("chunkup.gpuSurfaceBuild", "false").toBoolean())

	/** CUDA pinned host 缓冲（减少 D→H 拷贝延迟），默认 true。 */
	val gpuPinnedHost: Boolean
		get() = System.getProperty("chunkup.gpuPinnedHost", "true").toBoolean()

	val gpuChunkLoadOnGenerated: Boolean
		get() = !instantLoad &&
			System.getProperty("chunkup.gpuChunkLoad.generated", "false").toBoolean()

	val gpuChunkLoadOnLoaded: Boolean
		get() = !instantLoad &&
			System.getProperty("chunkup.gpuChunkLoad.loaded", "false").toBoolean()

	val gpuSkylightApply: Boolean
		get() = System.getProperty("chunkup.gpuSkylightApply", "false").toBoolean()

	/** GENERATED 或 LOADED 任一开启即运行 CUDA chunk-load batch。 */
	val gpuChunkLoadEnabled: Boolean
		get() = gpuChunkLoadOnGenerated || gpuChunkLoadOnLoaded

	val gpuChunkLoadSummaryInterval: Int
		get() = System.getProperty("chunkup.gpuChunkLoad.summaryInterval", "256")
			.toIntOrNull()?.coerceAtLeast(1) ?: 256

	val gpuChunkLoadBatchSize: Int
		get() = System.getProperty("chunkup.gpuChunkLoad.batchSize", "64")
			.toIntOrNull()?.coerceIn(1, 128) ?: 64

	val gpuChunkLoadFlushInterval: Int
		get() = if (instantLoad) {
			1
		} else {
			System.getProperty("chunkup.gpuChunkLoad.flushInterval", "20")
				.toIntOrNull()?.coerceAtLeast(1) ?: 20
		}

	val gpuChunkLoadMinFlushBatch: Int
		get() = if (instantLoad) {
			1
		} else {
			System.getProperty("chunkup.gpuChunkLoad.minFlushBatch", "16")
				.toIntOrNull()?.coerceIn(1, 128) ?: 16
		}

	val f3Debug: Boolean
		get() = System.getProperty("chunkup.f3Debug", "true").toBoolean()
}

