package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.Chunkup
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

object ChunkupSettingsKeybind {
	private val SETTINGS_KEY = KeyBindingHelper.registerKeyBinding(
		KeyMapping(
			"key.chunkup.settings",
			GLFW.GLFW_KEY_COMMA,
			"key.category.chunkup",
		),
	)

	@JvmStatic
	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			while (SETTINGS_KEY.consumeClick()) {
				ChunkupSettingsUi.open(client)
			}
		}
	}
}
