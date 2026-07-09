package cn.sanrolnet.chunkup

import cn.sanrolnet.chunkup.config.ChunkupSettingsSnapshot
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 与外部 Qt 设置工具共享的 JSON 配置。
 *
 * 默认路径（Windows）：`%APPDATA%/Chunkup/settings.json`
 * 可用 `-Dchunkup.config.path=` 覆盖。
 * JVM System Property 优先于 JSON 中的值。
 */
object ChunkupConfigFile {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.config")
	private val GSON = Gson()
	private const val FILE_NAME = "settings.json"
	private var loaded = false

	@JvmStatic
	fun currentSnapshot(): ChunkupSettingsSnapshot {
		return ChunkupSettingsSnapshot().apply {
			instantLoad = ChunkupConfig.instantLoad
			gpuWorldGen = ChunkupConfig.gpuWorldGen
			gpuDensityBatch = ChunkupConfig.gpuDensityBatch
			forceGpu = ChunkupConfig.forceGpu
			gpuChunkLoadOnGenerated = ChunkupConfig.gpuChunkLoadOnGenerated
			gpuChunkLoadOnLoaded = ChunkupConfig.gpuChunkLoadOnLoaded
			gpuSkylightApply = ChunkupConfig.gpuSkylightApply
			gpuChunkLoadSummaryInterval = ChunkupConfig.gpuChunkLoadSummaryInterval
			gpuChunkLoadBatchSize = ChunkupConfig.gpuChunkLoadBatchSize
			gpuSections = ChunkupConfig.gpuSections
			preRenderOnLoad = ChunkupConfig.preRenderOnLoad
			preRenderBudgetPerFrame = ChunkupConfig.preRenderBudgetPerFrame
			layeredSections = ChunkupConfig.layeredSections
			layeredSectionsRate = ChunkupConfig.layeredSectionsRate
			f3Debug = ChunkupConfig.f3Debug
			debugProbe = ChunkupConfig.debugProbe
			nativeDir = System.getProperty("chunkup.native.dir", "")
			rustLogLevel = System.getenv("RUST_LOG") ?: "warn,chunkup_core=warn"
		}
	}

	@JvmStatic
	fun applyRuntime(snapshot: ChunkupSettingsSnapshot) {
		System.setProperty("chunkup.instantLoad", snapshot.instantLoad.toString())
		System.setProperty("chunkup.gpuWorldGen", snapshot.gpuWorldGen.toString())
		System.setProperty("chunkup.gpuDensityBatch", snapshot.gpuDensityBatch.toString())
		System.setProperty("chunkup.forceGpu", snapshot.forceGpu.toString())
		System.setProperty("chunkup.gpuChunkLoad.generated", snapshot.gpuChunkLoadOnGenerated.toString())
		System.setProperty("chunkup.gpuChunkLoad.loaded", snapshot.gpuChunkLoadOnLoaded.toString())
		System.setProperty("chunkup.gpuSkylightApply", snapshot.gpuSkylightApply.toString())
		System.setProperty("chunkup.gpuChunkLoad.summaryInterval", snapshot.gpuChunkLoadSummaryInterval.toString())
		System.setProperty("chunkup.gpuChunkLoad.batchSize", snapshot.gpuChunkLoadBatchSize.toString())
		System.setProperty("chunkup.gpuSections", snapshot.gpuSections.toString())
		System.setProperty("chunkup.preRenderOnLoad", snapshot.preRenderOnLoad.toString())
		System.setProperty("chunkup.preRender.budget", snapshot.preRenderBudgetPerFrame.toString())
		System.setProperty("chunkup.layeredSections", snapshot.layeredSections.toString())
		System.setProperty("chunkup.layeredSections.rate", snapshot.layeredSectionsRate.toString())
		System.setProperty("chunkup.f3Debug", snapshot.f3Debug.toString())
		System.setProperty("chunkup.debug.probe", snapshot.debugProbe.toString())
		if (snapshot.nativeDir.isNotBlank()) {
			System.setProperty("chunkup.native.dir", snapshot.nativeDir)
		}
	}

	@JvmStatic
	fun saveSnapshot(snapshot: ChunkupSettingsSnapshot) {
		val directory = resolveSettingsPath().parent
		Files.createDirectories(directory)
		Files.writeString(
			resolveSettingsPath(),
			GSON.toJson(snapshot),
			StandardCharsets.UTF_8,
		)
		LOGGER.info("chunkup settings saved to {}", resolveSettingsPath())
	}

	@JvmStatic
	fun ensureLoaded() {
		if (loaded) return
		loaded = true

		val path = resolveSettingsPath()
		if (!Files.isRegularFile(path)) {
			LOGGER.debug("chunkup settings file not found: {}", path)
			return
		}

		try {
			val text = Files.readString(path, StandardCharsets.UTF_8)
			val root = JsonParser.parseString(text).asJsonObject
			applyJson(root, path)
		} catch (e: Exception) {
			LOGGER.warn("failed loading chunkup settings from {}", path, e)
		}
	}

	@JvmStatic
	fun resolveSettingsPath(): Path {
		System.getProperty("chunkup.config.path")?.takeIf { it.isNotBlank() }?.let {
			return Paths.get(it)
		}

		System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let {
			return Paths.get(it, "Chunkup", FILE_NAME)
		}

		val minecraftConfig = Paths.get(
			System.getProperty("user.home"),
			".minecraft",
			"config",
			"chunkup",
			FILE_NAME,
		)
		if (Files.isRegularFile(minecraftConfig)) {
			return minecraftConfig
		}

		return Paths.get(System.getProperty("user.home"), ".config", "chunkup", FILE_NAME)
	}

	private fun applyJson(root: JsonObject, path: Path) {
		var applied = 0

		applied += applyBoolean(root, "instantLoad", "chunkup.instantLoad")
		applied += applyBoolean(root, "gpuWorldGen", "chunkup.gpuWorldGen")
		applied += applyBoolean(root, "gpuDensityBatch", "chunkup.gpuDensityBatch")
		applied += applyBoolean(root, "forceGpu", "chunkup.forceGpu")
		applied += applyBoolean(root, "gpuChunkLoadOnGenerated", "chunkup.gpuChunkLoad.generated")
		applied += applyBoolean(root, "gpuChunkLoadOnLoaded", "chunkup.gpuChunkLoad.loaded")
		applied += applyBoolean(root, "gpuSkylightApply", "chunkup.gpuSkylightApply")
		applied += applyInt(root, "gpuChunkLoadSummaryInterval", "chunkup.gpuChunkLoad.summaryInterval")
		applied += applyInt(root, "gpuChunkLoadBatchSize", "chunkup.gpuChunkLoad.batchSize")
		applied += applyBoolean(root, "gpuSections", "chunkup.gpuSections")
		applied += applyBoolean(root, "preRenderOnLoad", "chunkup.preRenderOnLoad")
		applied += applyInt(root, "preRenderBudgetPerFrame", "chunkup.preRender.budget")
		applied += applyBoolean(root, "layeredSections", "chunkup.layeredSections")
		applied += applyInt(root, "layeredSectionsRate", "chunkup.layeredSections.rate")
		applied += applyBoolean(root, "f3Debug", "chunkup.f3Debug")
		applied += applyBoolean(root, "debugProbe", "chunkup.debug.probe")
		applied += applyString(root, "nativeDir", "chunkup.native.dir")

		if (applied > 0) {
			LOGGER.info("chunkup settings loaded from {} ({} keys)", path, applied)
		}
	}

	private fun applyBoolean(root: JsonObject, jsonKey: String, propertyKey: String): Int {
		if (System.getProperty(propertyKey) != null || !root.has(jsonKey)) {
			return 0
		}
		System.setProperty(propertyKey, root.get(jsonKey).asBoolean.toString())
		return 1
	}

	private fun applyInt(root: JsonObject, jsonKey: String, propertyKey: String): Int {
		if (System.getProperty(propertyKey) != null || !root.has(jsonKey)) {
			return 0
		}
		System.setProperty(propertyKey, root.get(jsonKey).asInt.toString())
		return 1
	}

	private fun applyString(root: JsonObject, jsonKey: String, propertyKey: String): Int {
		if (System.getProperty(propertyKey) != null || !root.has(jsonKey)) {
			return 0
		}
		val value = root.get(jsonKey).asString.trim()
		if (value.isEmpty()) {
			return 0
		}
		System.setProperty(propertyKey, value)
		return 1
	}
}
