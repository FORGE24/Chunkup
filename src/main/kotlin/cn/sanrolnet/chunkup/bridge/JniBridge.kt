package cn.sanrolnet.chunkup.bridge

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.minecraft.generation.ChunkDensityFill
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
		if (!loaded) {
			LOGGER.warn("Native library chunkup_core not found; engine runs in stub mode")
		}
	}

	override fun isAvailable(): Boolean = loaded && nativeIsAvailable()

	override fun initialize(): Boolean {
		if (!loaded) return false
		return nativeInitialize()
	}

	override fun shutdown() {
		if (!loaded) return
		nativeShutdown()
	}

	override fun onChunkGeneration(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkX: Int,
		chunkZ: Int,
	): Boolean {
		if (!loaded) return false
		return nativeOnChunkGeneration(stage.ordinal, chunkX, chunkZ)
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

	private fun decodeChunkDensityFill(raw: Any): ChunkDensityFill? {
		if (raw !is Array<*>) return null
		if (raw.size < 2) return null
		val density = raw[0] as? FloatArray ?: return null
		val fluid = raw[1] as? ByteArray ?: return null
		return ChunkDensityFill(density, fluid)
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
	private external fun nativeIsAvailable(): Boolean

	@JvmStatic
	private external fun nativeInitialize(): Boolean

	@JvmStatic
	private external fun nativeShutdown()

	@JvmStatic
	private external fun nativeOnChunkGeneration(stageOrdinal: Int, chunkX: Int, chunkZ: Int): Boolean

	@JvmStatic
	private external fun nativeGenerateChunkDensity(
		chunkX: Int,
		chunkZ: Int,
		minY: Int,
		height: Int,
		worldSeed: Long,
	): Any?

	@JvmStatic
	private external fun nativeOnSectionBuild(
		sectionX: Int,
		sectionY: Int,
		sectionZ: Int,
		blockStates: ByteArray,
	): Any?
}
