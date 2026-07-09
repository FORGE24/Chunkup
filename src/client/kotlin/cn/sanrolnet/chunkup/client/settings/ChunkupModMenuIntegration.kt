package cn.sanrolnet.chunkup.client.settings

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.minecraft.client.gui.screens.Screen

object ChunkupModMenuIntegration : ModMenuApi {
	override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
		return ConfigScreenFactory { parent: Screen? -> ChunkupFabricSettingsScreen(parent) }
	}
}
