package cn.sanrolnet.chunkup.client.sodium

import net.fabricmc.loader.api.FabricLoader

object SodiumIntegration {
	val isLoaded: Boolean
		get() = FabricLoader.getInstance().isModLoaded("sodium")
}
