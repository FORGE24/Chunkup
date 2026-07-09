package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfigFile
import cn.sanrolnet.chunkup.bridge.JniBridge
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ChunkupSettingsUi {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.settings.ui")

	private val qtDialogLock = AtomicBoolean(false)
	private val qtExecutor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "chunkup-settings-qt").apply { isDaemon = true }
	}

	@JvmStatic
	fun open() {
		open(Minecraft.getInstance())
	}

	@JvmStatic
	fun open(client: Minecraft) {
		client.execute {
			openFabricScreen(client)
		}
	}

	@JvmStatic
	fun openFabricScreen(client: Minecraft) {
		client.setScreen(ChunkupFabricSettingsScreen(client.screen))
	}

	/** 在专用线程打开 Qt 对话框，避免阻塞 MC 渲染线程。 */
	@JvmStatic
	fun openQtDialogAsync(client: Minecraft) {
		if (!qtDialogLock.compareAndSet(false, true)) {
			LOGGER.info("Qt settings dialog already open")
			return
		}
		if (!SettingsNative.ensureLoaded()) {
			qtDialogLock.set(false)
			LOGGER.warn("Qt settings unavailable; opening in-game screen")
			client.execute { openFabricScreen(client) }
			return
		}

		val snapshot = ChunkupConfigFile.currentSnapshot()
		qtExecutor.execute {
			try {
				val result = SettingsNative.showSettingsDialog(snapshot)
				client.execute {
					when (result) {
						SettingsNative.DialogResult.SAVED -> {
							ChunkupConfigFile.applyRuntime(snapshot)
							ChunkupConfigFile.saveSnapshot(snapshot)
							JniBridge.setForceGpu(snapshot.forceGpu)
							LOGGER.info("Chunkup settings applied in-process (Qt)")
						}
						SettingsNative.DialogResult.CANCELLED -> Unit
						SettingsNative.DialogResult.ERROR -> {
							LOGGER.warn("Qt settings dialog failed; opening in-game screen")
							openFabricScreen(client)
						}
					}
				}
			} finally {
				qtDialogLock.set(false)
			}
		}
	}
}
