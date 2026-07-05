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
			LOGGER.debug("chunkup settings native: engine library directory unavailable")
			return false
		}

		val libraryPath = Paths.get(directory, System.mapLibraryName("chunkup_settings"))
		return try {
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
