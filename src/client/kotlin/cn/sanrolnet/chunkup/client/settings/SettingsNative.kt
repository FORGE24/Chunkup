package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.bridge.NativeLibraryLoader
import cn.sanrolnet.chunkup.config.ChunkupSettingsSnapshot
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * JNI 桥接 Qt settings DLL（chunkup_settings.dll），进程内模态对话框，无 IPC。
 */
object SettingsNative {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.settings.native")

	@Volatile
	private var loaded = false

	@JvmStatic
	fun ensureLoaded(): Boolean {
		if (loaded) {
			return true
		}

		val directory = NativeLibraryLoader.nativeLibraryDirectory()
		if (directory == null) {
			LOGGER.warn("chunkup settings native: engine library directory unavailable")
			return false
		}

		NativeLibraryLoader.prepareNativeDirectory(Paths.get(directory))

		val libraryPath = Paths.get(directory, System.mapLibraryName("chunkup_settings"))
		if (!libraryPath.toAbsolutePath().toFile().isFile) {
			LOGGER.warn("chunkup settings native: {} not found", libraryPath)
			return false
		}

		return try {
			preloadQtDependencies(directory)
			System.load(libraryPath.toAbsolutePath().toString())
			loaded = nativeIsAvailable() != 0
			if (loaded) {
				LOGGER.info("Loaded chunkup_settings from {}", libraryPath)
			} else {
				LOGGER.warn("chunkup_settings loaded but Qt backend unavailable")
			}
			loaded
		} catch (e: UnsatisfiedLinkError) {
			LOGGER.warn("chunkup_settings not available: {}", e.message)
			false
		}
	}

	private fun preloadQtDependencies(directory: String) {
		for (baseName in QT_DEPENDENCIES) {
			val path = Paths.get(directory, System.mapLibraryName(baseName))
			if (!path.toFile().isFile) {
				continue
			}
			try {
				System.load(path.toAbsolutePath().toString())
			} catch (_: UnsatisfiedLinkError) {
				// 可选依赖，忽略
			}
		}
	}

	private val QT_DEPENDENCIES = listOf(
		"Qt6Core",
		"Qt6Gui",
		"Qt6Widgets",
		"libgcc_s_seh-1",
		"libstdc++-6",
		"libwinpthread-1",
	)

	@JvmStatic
	fun showSettingsDialog(snapshot: ChunkupSettingsSnapshot): DialogResult {
		if (!ensureLoaded()) {
			return DialogResult.ERROR
		}
		return when (nativeShowSettingsDialog(snapshot)) {
			1 -> DialogResult.SAVED
			0 -> DialogResult.CANCELLED
			else -> DialogResult.ERROR
		}
	}

	enum class DialogResult {
		SAVED,
		CANCELLED,
		ERROR,
	}

	@JvmStatic
	private external fun nativeIsAvailable(): Int

	@JvmStatic
	private external fun nativeShowSettingsDialog(snapshot: ChunkupSettingsSnapshot): Int
}
