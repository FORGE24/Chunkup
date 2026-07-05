package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfigFile
import cn.sanrolnet.chunkup.bridge.JniBridge
import cn.sanrolnet.chunkup.config.ChunkupSettingsSnapshot
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object ChunkupSettingsUi {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.settings.ui")

	@JvmStatic
	fun open() {
		val client = Minecraft.getInstance()
		client.execute {
			if (client.player == null) {
				return@execute
			}

			// 优先尝试 Qt 原生对话框；失败则回退到游戏内界面
			if (SettingsNative.ensureLoaded()) {
				openQtDialog(client)
			} else {
				LOGGER.info("Opening in-game Chunkup settings (Qt UI unavailable)")
				client.setScreen(ChunkupFabricSettingsScreen(client.screen))
			}
		}
	}

	private fun openQtDialog(client: Minecraft) {
		val snapshot = ChunkupConfigFile.currentSnapshot()
		when (SettingsNative.showSettingsDialog(snapshot)) {
			SettingsNative.DialogResult.SAVED -> {
				ChunkupConfigFile.applyRuntime(snapshot)
				ChunkupConfigFile.saveSnapshot(snapshot)
				JniBridge.setForceGpu(snapshot.forceGpu)
				LOGGER.info("Chunkup settings applied in-process (Qt)")
			}
			SettingsNative.DialogResult.CANCELLED -> Unit
			SettingsNative.DialogResult.ERROR -> {
				LOGGER.warn("Qt settings dialog failed; falling back to in-game screen")
				client.setScreen(ChunkupFabricSettingsScreen(client.screen))
			}
		}
	}
}
