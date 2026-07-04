package cn.sanrolnet.chunkup.bridge

/**
 * Kotlin ↔ 原生引擎的统一入口。
 *
 * 调用链：Mod 壳 → [JNI | FFM] → Rust 核心 → [CUDA | OpenCL | CPU/SIMD]
 */
interface EngineBridge {
	val backendName: String

	fun isAvailable(): Boolean

	fun initialize(): Boolean

	fun shutdown()

	/** 区块生成阶段回调；返回 false 表示引擎未能处理（仍继续原版流程）。 */
	fun onChunkGeneration(stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage, chunkX: Int, chunkZ: Int): Boolean

	/** Section 网格构建；无可用结果时返回 null。 */
	fun onSectionBuild(sectionX: Int, sectionY: Int, sectionZ: Int, blockStates: ByteArray): cn.sanrolnet.chunkup.render.SectionBuildPayload? = null

	companion object {
		fun create(preferFfm: Boolean = false): EngineBridge {
			if (preferFfm && FfmBridge.isSupported()) {
				return FfmBridge
			}
			return JniBridge
		}
	}
}
