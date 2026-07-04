package cn.sanrolnet.chunkup.bridge

import cn.sanrolnet.chunkup.Chunkup
import org.slf4j.LoggerFactory

/**
 * Java 22+ Foreign Function & Memory API 路径（JNI 的现代替代）。
 *
 * 符号绑定在引擎编译完成后由 [FfmSymbols] 解析；当前为占位实现。
 */
object FfmBridge : EngineBridge {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.ffm")

	override val backendName: String = "ffm"

	override fun isAvailable(): Boolean = isSupported() && FfmSymbols.resolve()

	override fun initialize(): Boolean {
		if (!isAvailable()) return false
		return FfmSymbols.initialize()
	}

	override fun shutdown() {
		if (!isAvailable()) return
		FfmSymbols.shutdown()
	}

	override fun onChunkGeneration(
		stage: cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage,
		chunkX: Int,
		chunkZ: Int,
	): Boolean = false

	fun isSupported(): Boolean {
		return Runtime.version().feature() >= 22
	}
}

/**
 * FFM 符号表占位；后续由 build 脚本生成或直接绑定 libchunkup_core 导出符号。
 */
internal object FfmSymbols {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.ffm.symbols")

	fun resolve(): Boolean {
		LOGGER.debug("FFM symbol resolution not yet implemented")
		return false
	}

	fun initialize(): Boolean = false

	fun shutdown() {}
}
