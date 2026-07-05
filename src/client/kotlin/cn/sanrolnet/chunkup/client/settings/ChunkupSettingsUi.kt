package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfigFile
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object ChunkupSettingsUi {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.settings.ui")

	@JvmStatic
	fun open() {
		val client = Minecraft.getInstance()
		client.execute {
			if (!SettingsNative.ensureLoaded()) {
				LOGGER.warn("Chunkup settings UI unavailable (chunkup_settings.dll not loaded)")
				return@execute
			}

			val snapshot = ChunkupConfigFile.currentSnapshot()
			when (SettingsNative.showSettingsDialog(snapshot)) {
				SettingsNative.DialogResult.SAVED -> {
					ChunkupConfigFile.applyRuntime(snapshot)
					ChunkupConfigFile.saveSnapshot(snapshot)
					LOGGER.info("Chunkup settings applied in-process")
				}
				SettingsNative.DialogResult.CANCELLED -> Unit
				SettingsNative.DialogResult.ERROR -> LOGGER.warn("Chunkup settings dialog failed")
			}
		}
	}
}
