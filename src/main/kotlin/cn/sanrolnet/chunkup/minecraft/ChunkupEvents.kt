package cn.sanrolnet.chunkup.minecraft

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

/**
 * Fabric 事件调度：Mod 壳与 Minecraft 生命周期的对接点。
 */
object ChunkupEvents {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.events")

	fun register(engine: EngineBridge) {
		ChunkGenerationHooks.bindEngine(engine)

		ServerLifecycleEvents.SERVER_STARTING.register { _ ->
			if (engine.initialize()) {
				LOGGER.info("Chunkup engine initialized via {}", engine.backendName)
			} else {
				LOGGER.warn("Chunkup engine failed to initialize; falling back to vanilla chunk pipeline")
			}
		}

		ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
			engine.shutdown()
		}

		ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
			ChunkGenerationHooks.dispatch(world, chunk, ChunkGenerationStage.LOADED)
		}
	}
}
