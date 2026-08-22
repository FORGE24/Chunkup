package cn.sanrolnet.chunkup

object ChunkupConfig {
	@JvmStatic
	fun ensureLoaded() {
		ChunkupConfigFile.ensureLoaded()
	}

	val forceGpu: Boolean
		get() = System.getProperty("chunkup.forceGpu", "false").toBoolean()

	val gpuWorldGen: Boolean
		get() = System.getProperty("chunkup.gpuWorldGen", "true").toBoolean()

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

	val gpuDensityBatchCoalesceMs: Long
		get() = System.getProperty("chunkup.gpuDensityBatch.coalesceMs", if (gpuWorldGen) "4" else "8")
			.toLongOrNull()?.coerceIn(0, 100) ?: if (gpuWorldGen) 4L else 8L

	val gpuDensityBatchMaxWaitMs: Long
		get() = System.getProperty("chunkup.gpuDensityBatch.maxWaitMs", if (gpuWorldGen) "16" else "25")
			.toLongOrNull()?.coerceIn(1, 200) ?: if (gpuWorldGen) 16L else 25L

	val gpuDensityBatchMinFlush: Int
		get() = System.getProperty("chunkup.gpuDensityBatch.minFlush", if (gpuWorldGen) "8" else "4")
			.toIntOrNull()?.coerceIn(1, 128) ?: if (gpuWorldGen) 8 else 4

	val gpuSurfaceBuild: Boolean
		get() = gpuWorldGen ||
			(!instantLoad && System.getProperty("chunkup.gpuSurfaceBuild", "false").toBoolean())

	/** 完整 vanilla SurfaceRule 引擎（native C），默认随 gpuSurfaceBuild 开启，失败回退 vanilla。 */
	val gpuSurfaceFull: Boolean
		get() = gpuSurfaceBuild &&
			System.getProperty("chunkup.gpuSurfaceFull", "true").toBoolean()

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
		get() = !instantLoad && System.getProperty("chunkup.gpuSkylightApply", "false").toBoolean()

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

	val gpuSections: Boolean
		get() = System.getProperty("chunkup.gpuSections", "false").toBoolean()

	@get:JvmStatic
	val preRenderOnLoad: Boolean
		get() = System.getProperty("chunkup.preRenderOnLoad", "true").toBoolean()

	@get:JvmStatic
	val preRenderBudgetPerFrame: Int
		get() = System.getProperty("chunkup.preRender.budget", "8")
			.toIntOrNull()?.coerceIn(1, 64) ?: 8

	@get:JvmStatic
	val layeredSections: Boolean
		get() = System.getProperty("chunkup.layeredSections", "true").toBoolean()

	@get:JvmStatic
	val layeredSectionsRate: Int
		get() = System.getProperty("chunkup.layeredSections.rate", "3")
			.toIntOrNull()?.coerceIn(1, 16) ?: 3

	@get:JvmStatic
	val layeredSectionsInitialDepth: Int
		get() = System.getProperty("chunkup.layeredSections.initialDepth", "1")
			.toIntOrNull()?.coerceIn(0, 16) ?: 1

	@get:JvmStatic
	val layeredSectionsHeadroom: Int
		get() = System.getProperty("chunkup.layeredSections.headroom", "2")
			.toIntOrNull()?.coerceIn(0, 8) ?: 2

	val f3Debug: Boolean
		get() = System.getProperty("chunkup.f3Debug", "true").toBoolean()

	val debugProbe: Boolean
		get() = System.getProperty("chunkup.debug.probe", "false").toBoolean()

	val infectionRender: Boolean
		get() = System.getProperty("chunkup.infectionRender", "false").toBoolean()

	val infectionRadiusChunks: Int
		get() = System.getProperty("chunkup.infectionRender.radius", "16")
			.toIntOrNull()?.coerceIn(4, 32) ?: 16

	val gpuCpuCoordination: Boolean
		get() = System.getProperty("chunkup.gpuCpuCoordination", "true").toBoolean()

	val gpuCapacity: Int
		get() = System.getProperty("chunkup.gpuCapacity", "32")
			.toIntOrNull()?.coerceIn(1, 256) ?: 32

	val cpuCapacity: Int
		get() = System.getProperty("chunkup.cpuCapacity", "8")
			.toIntOrNull()?.coerceIn(1, 64) ?: 8

	val enableLaneStealing: Boolean
		get() = System.getProperty("chunkup.enableLaneStealing", "true").toBoolean()

	val laneStealThreshold: Double
		get() = System.getProperty("chunkup.laneStealThreshold", "0.75")
			.toDoubleOrNull()?.coerceIn(0.1, 0.95) ?: 0.75

	val coordinationBatchSize: Int
		get() = System.getProperty("chunkup.coordinationBatchSize", "16")
			.toIntOrNull()?.coerceIn(1, 128) ?: 16

	val coordinationCollectTimeoutMs: Long
		get() = System.getProperty("chunkup.coordinationCollectTimeoutMs", "50")
			.toLongOrNull()?.coerceIn(1, 1000) ?: 50L
}

