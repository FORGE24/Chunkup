package cn.sanrolnet.chunkup

import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.minecraft.ChunkupEvents
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

object Chunkup : ModInitializer {
	const val MOD_ID: String = "chunkup"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	lateinit var engine: EngineBridge
		private set

	override fun onInitialize() {
		ChunkupConfig.ensureLoaded()
		engine = EngineBridge.create(preferFfm = false)
		ChunkupEvents.register(engine)
		LOGGER.info("Chunkup mod shell ready (bridge={})", engine.backendName)
	}

	fun id(path: String): ResourceLocation
		= ResourceLocation(MOD_ID, path)
}
