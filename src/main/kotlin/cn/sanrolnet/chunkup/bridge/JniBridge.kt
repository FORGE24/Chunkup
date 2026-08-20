package cn.sanrolnet.chunkup.bridge

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityFill
import cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadResult
import cn.sanrolnet.chunkup.render.SectionBuildPayload
import cn.sanrolnet.chunkup.render.SectionKind
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 * 通过 JNI 加载 `chunkup_core` 动态库，转发至 Rust 核心引擎。
 */
object JniBridge : EngineBridge {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.jni")

	override val backendName: String = "jni"

	private var loaded = false

	init {
		loaded = NativeLibraryLoader.loadEngineLibraries()
		if (loaded) {
			NativeLibraryLoader.nativeLibraryDirectory()?.let { dir ->
				nativeSetNativeLibraryDirectory(dir)
			}
		}
		if (!loaded) {
			LOGGER.warn("Native library chunkup_core not found; engine runs in stub mode")
		}
	}

	override fun isAvailable(): Boolean = loaded && nativeIsAvailable()

	override fun initialize(): Boolean {
		if (!loaded) return false
		return nativeInitialize(ChunkupConfig.forceGpu)
	}

	override fun activeComputeBackend(): String {
		if (!loaded || !nativeIsAvailable()) return "none"
		return nativeGetActiveBackend()
	}

	override fun shutdown() {
		if (!loaded) return
		nativeShutdown()
	}

	@JvmStatic
	fun setForceGpu(enabled: Boolean) {
		if (!loaded) return
		nativeSetForceGpu(enabled)
	}

	@JvmStatic
	fun debugStatsLines(): Array<String> {
		if (!loaded) return emptyArray()
		return try {
			nativeGetDebugStats() ?: emptyArray()
		} catch (e: UnsatisfiedLinkError) {
			LOGGER.warn(
				"nativeGetDebugStats unavailable; reinstall the latest chunkupRelease JAR to refresh embedded natives",
			)
			emptyArray()
		}
	}

	override fun onChunkGeneration(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkX: Int,
		chunkZ: Int,
	): Boolean {
		if (!loaded) return false
		return nativeOnChunkGeneration(stage.ordinal, chunkX, chunkZ)
	}

	override fun processChunkLoad(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		density: FloatArray,
	): ChunkLoadResult? {
		if (!loaded) return null
		val raw = nativeProcessChunkLoad(
			stage.ordinal,
			chunkX,
			chunkZ,
			minY,
			height,
			worldSeed,
			density,
		) ?: return null
		return decodeChunkLoadResult(raw)
	}

	override fun processChunkLoadBatch(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkXs: IntArray,
		chunkZs: IntArray,
		minY: Int,
		height: Int,
		worldSeed: Long,
		densities: FloatArray,
	): List<ChunkLoadResult?>? {
		if (!loaded || chunkXs.isEmpty() || chunkXs.size != chunkZs.size) return null
		val raw = nativeProcessChunkLoadBatch(
			stage.ordinal,
			minY,
			height,
			worldSeed,
			chunkXs,
			chunkZs,
			densities,
		) as? Array<*> ?: return null
		if (raw.size != chunkXs.size) return null
		return raw.map { element ->
			if (element == null) null else decodeChunkLoadResult(element)
		}
	}

	private fun decodeChunkLoadResult(raw: Any): ChunkLoadResult? {
		if (raw !is Array<*>) return null
		if (raw.size < 2) return null
		val skylight = raw[0] as? ByteArray ?: return null
		val faceMask = raw[1] as? ByteArray ?: return null
		return ChunkLoadResult(skylight, faceMask)
	}

	override fun generateChunkDensity(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): ChunkDensityFill? {
		if (!loaded) return null
		val raw = nativeGenerateChunkDensity(chunkX, chunkZ, minY, height, worldSeed) ?: return null
		return decodeChunkDensityFill(raw)
	}

	override fun generateChunkDensityBatch(
		chunkXs: IntArray,
		chunkZs: IntArray,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): List<ChunkDensityFill?>? {
		if (!loaded || chunkXs.isEmpty() || chunkXs.size != chunkZs.size) return null
		val raw = nativeGenerateChunkDensityBatch(chunkXs, chunkZs, minY, height, worldSeed) ?: return null
		if (raw.size != chunkXs.size) return null
		return raw.map { element ->
			if (element == null) null else decodeChunkDensityFill(element)
		}
	}

	private fun decodeChunkDensityFill(raw: Any): ChunkDensityFill? {
		if (raw !is Array<*>) return null
		if (raw.size < 2) return null
		val density = raw[0] as? FloatArray ?: return null
		val fluid = raw[1] as? ByteArray ?: return null
		return ChunkDensityFill(density, fluid)
	}

	override fun generateSurfaceThin(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		density: FloatArray,
		biomeKind: ByteArray,
	): ByteArray? {
		if (!loaded) return null
		return nativeGenerateSurfaceThin(chunkX, chunkZ, minY, height, worldSeed, density, biomeKind)
	}

	override fun generateSurfaceFull(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		blocks: ByteArray,
		heightmap: IntArray,
		biomeQuart: ByteArray,
	): ByteArray? {
		if (!loaded) return null
		return nativeGenerateSurfaceFull(chunkX, chunkZ, minY, height, worldSeed, blocks, heightmap, biomeQuart)
	}

	override fun onSectionBuild(
		sectionX: Int,
		sectionY: Int,
		sectionZ: Int,
		blockStates: ByteArray,
	): SectionBuildPayload? {
		if (!loaded || blockStates.size != 4096) return null
		val raw = nativeOnSectionBuild(sectionX, sectionY, sectionZ, blockStates) ?: return null
		return decodeSectionBuildPayload(raw)
	}

	private fun decodeSectionBuildPayload(raw: Any): SectionBuildPayload? {
		if (raw !is Array<*>) return null
		if (raw.size < 5) return null
		val kind = SectionKind.fromOrdinal(raw[0] as Int)
		val vertexData = raw[1] as? ByteBuffer ?: return null
		val vertexSegments = raw[2] as? IntArray ?: return null
		val visibilityData = raw[3] as? LongArray ?: return null
		val ready = raw[4] as? Boolean ?: false
		return SectionBuildPayload(kind, vertexData, vertexSegments, visibilityData, ready)
	}

	@JvmStatic
	private external fun nativeSetNativeLibraryDirectory(directory: String)

	@JvmStatic
	private external fun nativeIsAvailable(): Boolean

	@JvmStatic
	private external fun nativeInitialize(forceGpu: Boolean): Boolean

	@JvmStatic
	private external fun nativeSetForceGpu(forceGpu: Boolean)

	@JvmStatic
	private external fun nativeGetDebugStats(): Array<String>?

	@JvmStatic
	private external fun nativeGetActiveBackend(): String

	@JvmStatic
	private external fun nativeShutdown()

	@JvmStatic
	private external fun nativeOnChunkGeneration(stageOrdinal: Int, chunkX: Int, chunkZ: Int): Boolean

	@JvmStatic
	private external fun nativeProcessChunkLoad(
		stageOrdinal: Int,
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		density: FloatArray,
	): Any?

	@JvmStatic
	private external fun nativeProcessChunkLoadBatch(
		stageOrdinal: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		chunkXs: IntArray,
		chunkZs: IntArray,
		densities: FloatArray,
	): Any?

	@JvmStatic
	private external fun nativeGenerateChunkDensity(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): Any?

	@JvmStatic
	private external fun nativeGenerateChunkDensityBatch(
		chunkXs: IntArray,
		chunkZs: IntArray,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): Array<Any?>?

	@JvmStatic
	private external fun nativeGenerateSurfaceThin(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		density: FloatArray,
		biomeKind: ByteArray,
	): ByteArray?

	@JvmStatic
	private external fun nativeGenerateSurfaceFull(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
		blocks: ByteArray,
		heightmap: IntArray,
		biomeQuart: ByteArray,
	): ByteArray?

	@JvmStatic
	private external fun nativeOnSectionBuild(
		sectionX: Int,
		sectionY: Int,
		sectionZ: Int,
		blockStates: ByteArray,
	): Any?

	// =========================================================================
	// ChunkRuntime JNI 绑定(设计 §8-12,CPU/GPU 异构运行时壳)
	// =========================================================================

	fun runtimeCreate(): Boolean {
		if (!loaded) return false
		return nativeRuntimeCreate()
	}

	fun runtimeShutdown() {
		if (!loaded) return
		nativeRuntimeShutdown()
	}

	fun runtimeRegisterArchived(dim: Int, x: Int, z: Int): Boolean {
		if (!loaded) return false
		return nativeRuntimeRegisterArchived(dim, x, z)
	}

	fun runtimeBeginCpuLoad(dim: Int, x: Int, z: Int): Boolean {
		if (!loaded) return false
		return nativeRuntimeBeginCpuLoad(dim, x, z)
	}

	fun runtimeFinishCpuLoad(dim: Int, x: Int, z: Int, payload: ByteArray): Boolean {
		if (!loaded) return false
		return nativeRuntimeFinishCpuLoad(dim, x, z, payload)
	}

	fun runtimeBeginGpuStage(dim: Int, x: Int, z: Int): Boolean {
		if (!loaded) return false
		return nativeRuntimeBeginGpuStage(dim, x, z)
	}

	fun runtimeFinishGpuStage(dim: Int, x: Int, z: Int, gpuId: Long, size: Int): Boolean {
		if (!loaded) return false
		return nativeRuntimeFinishGpuStage(dim, x, z, gpuId, size)
	}

	/** chunk 数据所在地:0=Absent, 1=Cpu, 2=Gpu */
	fun runtimeChunkDataLocation(dim: Int, x: Int, z: Int): Int {
		if (!loaded) return 0
		return nativeRuntimeChunkDataLocation(dim, x, z)
	}

	/** runtime 统计:[slot_count, cpu_resident_bytes, gpu_resident_bytes],runtime 未创建返回 null */
	fun runtimeStats(): LongArray? {
		if (!loaded) return null
		return try {
			nativeRuntimeStats()
		} catch (e: UnsatisfiedLinkError) {
			null
		}
	}

	@JvmStatic
	private external fun nativeRuntimeCreate(): Boolean

	@JvmStatic
	private external fun nativeRuntimeShutdown()

	@JvmStatic
	private external fun nativeRuntimeRegisterArchived(dim: Int, x: Int, z: Int): Boolean

	@JvmStatic
	private external fun nativeRuntimeBeginCpuLoad(dim: Int, x: Int, z: Int): Boolean

	@JvmStatic
	private external fun nativeRuntimeFinishCpuLoad(dim: Int, x: Int, z: Int, payload: ByteArray): Boolean

	@JvmStatic
	private external fun nativeRuntimeBeginGpuStage(dim: Int, x: Int, z: Int): Boolean

	@JvmStatic
	private external fun nativeRuntimeFinishGpuStage(dim: Int, x: Int, z: Int, gpuId: Long, size: Int): Boolean

	@JvmStatic
	private external fun nativeRuntimeChunkDataLocation(dim: Int, x: Int, z: Int): Int

	@JvmStatic
	private external fun nativeRuntimeStats(): LongArray?

	fun interopAvailable(): Boolean = try { nativeInteropIsAvailable() } catch (e: UnsatisfiedLinkError) { false }

	fun interopUploadBlockStates(blockStates: ByteArray): Long = nativeInteropUploadBlockStates(blockStates)

	fun interopFreeBlockStates(devicePtr: Long) { if (devicePtr != 0L) nativeInteropFreeBlockStates(devicePtr) }

	fun interopMeshCountOnlyHost(blockStates: ByteArray, sectionCount: Int): IntArray? =
		nativeInteropMeshCountOnlyHost(blockStates, sectionCount)

	fun interopMeshCountOnlyDevice(devicePtr: Long, sectionCount: Int): IntArray? =
		nativeInteropMeshCountOnlyDevice(devicePtr, sectionCount)

	fun interopGlRegister(vboId: Int): Boolean = nativeInteropGlRegister(vboId)

	fun interopGlUnregister(vboId: Int) = nativeInteropGlUnregister(vboId)

	fun interopMeshToVboHost(
		blockStates: ByteArray,
		sectionCount: Int,
		vertexStride: Int,
		vboId: Int,
		vertexOffsetTable: IntArray,
		drawCommandBuffer: IntArray
	): Int = nativeInteropMeshToVboHost(blockStates, sectionCount, vertexStride, vboId, vertexOffsetTable, drawCommandBuffer)

	fun interopMeshToVboDevice(
		devicePtr: Long,
		sectionCount: Int,
		vertexStride: Int,
		vboId: Int,
		vertexOffsetTable: IntArray,
		drawCommandBuffer: IntArray
	): Int = nativeInteropMeshToVboDevice(devicePtr, sectionCount, vertexStride, vboId, vertexOffsetTable, drawCommandBuffer)

	@JvmStatic
	private external fun nativeInteropIsAvailable(): Boolean

	@JvmStatic
	private external fun nativeInteropUploadBlockStates(blockStates: ByteArray): Long

	@JvmStatic
	private external fun nativeInteropFreeBlockStates(devicePtr: Long)

	@JvmStatic
	private external fun nativeInteropMeshCountOnlyHost(blockStates: ByteArray, sectionCount: Int): IntArray?

	@JvmStatic
	private external fun nativeInteropMeshCountOnlyDevice(devicePtr: Long, sectionCount: Int): IntArray?

	@JvmStatic
	private external fun nativeInteropGlRegister(vboId: Int): Boolean

	@JvmStatic
	private external fun nativeInteropGlUnregister(vboId: Int)

	@JvmStatic
	private external fun nativeInteropMeshToVboHost(
		blockStates: ByteArray, sectionCount: Int, vertexStride: Int, vboId: Int,
		vertexOffsetTable: IntArray, drawCommandBuffer: IntArray
	): Int

	@JvmStatic
	private external fun nativeInteropMeshToVboDevice(
		devicePtr: Long, sectionCount: Int, vertexStride: Int, vboId: Int,
		vertexOffsetTable: IntArray, drawCommandBuffer: IntArray
	): Int
}
