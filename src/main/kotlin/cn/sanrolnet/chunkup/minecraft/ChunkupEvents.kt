package cn.sanrolnet.chunkup.minecraft

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.bridge.EngineBridge
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationHooks
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationStage
import cn.sanrolnet.chunkup.minecraft.generation.ChunkGenerationWorldContext
import cn.sanrolnet.chunkup.minecraft.generation.ChunkLoadPipeline
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import org.slf4j.LoggerFactory

/**
 * Fabric 事件调度：Mod 壳与 Minecraft 生命周期的对接点。
 */
object ChunkupEvents {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.events")

	fun register(engine: EngineBridge) {
		ChunkGenerationHooks.bindEngine(engine)

		ServerLifecycleEvents.SERVER_STARTING.register { server ->
			ChunkGenerationWorldContext.bindServer(server)
			if (engine.initialize()) {
				LOGGER.info(
					"Chunkup engine initialized via {} (compute backend={}, gpuChunkLoad.loaded={}, gpuSkylightApply={}, batchSize={})",
					engine.backendName,
					engine.activeComputeBackend(),
					ChunkupConfig.gpuChunkLoadOnLoaded,
					ChunkupConfig.gpuSkylightApply,
					ChunkupConfig.gpuChunkLoadBatchSize,
				)
			} else {
				LOGGER.warn("Chunkup engine failed to initialize; falling back to vanilla chunk pipeline")
			}
		}

		ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
			ChunkLoadPipeline.flush(engine)
			engine.shutdown()
		}

		ServerTickEvents.END_SERVER_TICK.register { _ ->
			ChunkLoadPipeline.flush(engine)
		}

		ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
			ChunkGenerationHooks.dispatch(world, chunk, ChunkGenerationStage.LOADED)
		}
	}
}
