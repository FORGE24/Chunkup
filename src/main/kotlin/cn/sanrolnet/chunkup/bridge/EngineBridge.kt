package cn.sanrolnet.chunkup.bridge

import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityFill

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

	/** 当前活跃计算后端：`cuda` / `opencl` / `cpu-simd` / `none` */
	fun activeComputeBackend(): String = "none"

	/** 区块生成阶段回调；返回 false 表示引擎未能处理（仍继续原版流程）。 */
	fun onChunkGeneration(stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage, chunkX: Int, chunkZ: Int): Boolean

	/**
	 * GENERATED / LOADED：对已加载区块运行 GPU 天空光 + 面剔除。
	 * [density] 布局：`index = ly * 256 + lz * 16 + lx`
	 */
	fun processChunkLoad(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		density: FloatArray,
	): cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadResult? = null

	/**
	 * 批量区块加载：一次 GPU dispatch 处理多个 chunk。
	 * 返回与 [chunkXs] 等长的结果列表，失败项为 null。
	 */
	fun processChunkLoadBatch(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkXs: IntArray,
		chunkZs: IntArray,
		minY: Int,
		height: Int,
		worldSeed: Long,
		densities: FloatArray,
	): List<cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadResult?>? = null

	/**
	 * 生成区块密度场（长度 = 16×16×height）及 Aquifer 流体标记。
	 * 布局：`index = ly * 256 + lz * 16 + lx`
	 */
	fun generateChunkDensity(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): ChunkDensityFill? = null

	/**
	 * 批量 NOISE_FILL：一次 CUDA dispatch 生成多个 chunk 密度。
	 */
	fun generateChunkDensityBatch(
		chunkXs: IntArray,
		chunkZs: IntArray,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): List<ChunkDensityFill?>? = null

	/** GPU buildSurface 薄层：返回 256×4 surface block ID。 */
	fun generateSurfaceThin(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		density: FloatArray,
		biomeKind: ByteArray,
	): ByteArray? = null

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
