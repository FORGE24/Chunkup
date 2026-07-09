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
 * - `chunkup.preRenderOnLoad` / `chunkup.preRender.budget` — 加载时距离优先预渲染
 * - `chunkup.layeredSections` / `.rate` / `.initialDepth` / `.headroom` — 地表优先分层 mesh
 * - `chunkup.f3Debug` — F3 调试面板显示 Chunkup 详情
 * - `chunkup.debug.probe` — 性能探针日志（density.read、gpu.batch）
 *
 * JSON 默认路径：`%APPDATA%/Chunkup/settings.json`（由 Qt 设置工具写入）
 */
object ChunkupConfig {
	@JvmStatic
	fun ensureLoaded() {
		ChunkupConfigFile.ensureLoaded()
	}

	val forceGpu: Boolean
		get() = System.getProperty("chunkup.forceGpu", "false").toBoolean()

	/**
	 * CUDA 程序化世界生成：启用完整 GPU 密度管线（与 instantLoad 互斥）。
	 * 默认开启；后端按 CUDA → OpenCL → CPU/SIMD 探测，单次 dispatch 失败时回退 CPU（除非 forceGpu）。
	 */
	val gpuWorldGen: Boolean
		get() = System.getProperty("chunkup.gpuWorldGen", "true").toBoolean()

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
		get() = System.getProperty("chunkup.gpuDensityBatch.size", if (gpuWorldGen) "64" else "16")
			.toIntOrNull()?.coerceIn(1, 128) ?: if (gpuWorldGen) 64 else 16

	/** 攒批合并窗口（毫秒）；gpuWorldGen 默认 0 = 尽快 flush，优先生成速度。 */
	val gpuDensityBatchCoalesceMs: Long
		get() = System.getProperty("chunkup.gpuDensityBatch.coalesceMs", if (gpuWorldGen) "0" else "8")
			.toLongOrNull()?.coerceIn(0, 100) ?: if (gpuWorldGen) 0L else 8L

	/** 攒批最长等待（毫秒） */
	val gpuDensityBatchMaxWaitMs: Long
		get() = System.getProperty("chunkup.gpuDensityBatch.maxWaitMs", if (gpuWorldGen) "8" else "25")
			.toLongOrNull()?.coerceIn(1, 200) ?: if (gpuWorldGen) 8L else 25L

	/** 部分 flush 的最小 pending 数；世界生成默认 1，避免 worker 互相等待。 */
	val gpuDensityBatchMinFlush: Int
		get() = System.getProperty("chunkup.gpuDensityBatch.minFlush", if (gpuWorldGen) "1" else "4")
			.toIntOrNull()?.coerceIn(1, 128) ?: if (gpuWorldGen) 1 else 4

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

	/** 客户端 GPU section mesh（见 SodiumIntegration） */
	val gpuSections: Boolean
		get() = System.getProperty("chunkup.gpuSections", "false").toBoolean()

	/**
	 * 区块加载时预渲染 section mesh（绕过 Sodium 可见性队列，按距离优先提交）。
	 * `-Dchunkup.preRenderOnLoad=true`（默认开启）
	 */
	@get:JvmStatic
	val preRenderOnLoad: Boolean
		get() = System.getProperty("chunkup.preRenderOnLoad", "true").toBoolean()

	/** 每帧预渲染 budget（section 数），默认 8。 */
	@get:JvmStatic
	val preRenderBudgetPerFrame: Int
		get() = System.getProperty("chunkup.preRender.budget", "8")
			.toIntOrNull()?.coerceIn(1, 64) ?: 8

	/** 分层 section mesh：地表先出、再向下扩展（默认 true）。 */
	@get:JvmStatic
	val layeredSections: Boolean
		get() = System.getProperty("chunkup.layeredSections", "true").toBoolean()

	/** 每 tick 向下解锁的 section 层数（默认 3）。 */
	@get:JvmStatic
	val layeredSectionsRate: Int
		get() = System.getProperty("chunkup.layeredSections.rate", "3")
			.toIntOrNull()?.coerceIn(1, 16) ?: 3

	/** 初始锚点下方允许 mesh 的层数（默认 1）。 */
	@get:JvmStatic
	val layeredSectionsInitialDepth: Int
		get() = System.getProperty("chunkup.layeredSections.initialDepth", "1")
			.toIntOrNull()?.coerceIn(0, 16) ?: 1

	/** 锚点上方保留层数（默认 2）。 */
	@get:JvmStatic
	val layeredSectionsHeadroom: Int
		get() = System.getProperty("chunkup.layeredSections.headroom", "2")
			.toIntOrNull()?.coerceIn(0, 8) ?: 2

	val f3Debug: Boolean
		get() = System.getProperty("chunkup.f3Debug", "true").toBoolean()

	/** 性能探针：记录 density.read / gpu.batch 耗时到日志与 F3。 */
	val debugProbe: Boolean
		get() = System.getProperty("chunkup.debug.probe", "false").toBoolean()

	/**
	 * 「第一次传染」渲染：32×32 区块全部 FULL 后一次性 GPU batch，区内旁路 Sodium。
	 * `-Dchunkup.infectionRender=true`
	 */
	val infectionRender: Boolean
		get() = System.getProperty("chunkup.infectionRender", "false").toBoolean()

	/** 感染区半径（chunk），默认 16 → 32×32 区域。 */
	val infectionRadiusChunks: Int
		get() = System.getProperty("chunkup.infectionRender.radius", "16")
			.toIntOrNull()?.coerceIn(4, 32) ?: 16
}

