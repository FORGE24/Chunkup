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
import cn.sanrolnet.chunkup.log.ChunkupSlLog
import org.slf4j.LoggerFactory

/**
 * Fabric 事件调度：Mod 壳与 Minecraft 生命周期的对接点。
 */
object ChunkupEvents {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.events")

	fun register(engine: EngineBridge) {
		ChunkGenerationHooks.bindEngine(engine)

		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			ChunkGenerationWorldContext.bindServer(server)
		}

		ServerLifecycleEvents.SERVER_STARTING.register { server ->
			ChunkGenerationWorldContext.bindServer(server)
			if (engine.initialize()) {
				ChunkupSlLog.infoInit(
					"Server Lifecycle Module",
					"Chunkup engine ready for world generation",
					"Backend=${engine.activeComputeBackend()},GpuWorldGen=${ChunkupConfig.gpuWorldGen}," +
						"InstantLoad=${ChunkupConfig.instantLoad},DensityBatch=${ChunkupConfig.gpuDensityBatch}," +
						"BatchSize=${ChunkupConfig.gpuDensityBatchSize},CoalesceMs=${ChunkupConfig.gpuDensityBatchCoalesceMs}," +
						"MinFlush=${ChunkupConfig.gpuDensityBatchMinFlush},MaxWaitMs=${ChunkupConfig.gpuDensityBatchMaxWaitMs}",
				)
				LOGGER.info(
					"Chunkup engine initialized via {} (compute backend={}, gpuWorldGen={}, instantLoad={}, forceGpu={}, genGpu={}, loadedGpu={}, gpuSkylightApply={}, densityBatch={}, batchSize={})",
					engine.backendName,
					engine.activeComputeBackend(),
					ChunkupConfig.gpuWorldGen,
					ChunkupConfig.instantLoad,
					ChunkupConfig.forceGpu,
					ChunkupConfig.gpuChunkLoadOnGenerated,
					ChunkupConfig.gpuChunkLoadOnLoaded,
					ChunkupConfig.gpuSkylightApply,
					ChunkupConfig.gpuDensityBatch,
					ChunkupConfig.gpuDensityBatchSize,
				)
			} else {
				LOGGER.warn("Chunkup engine failed to initialize; falling back to vanilla chunk pipeline")
			}
		}

		ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
			ChunkLoadPipeline.flush(engine)
			engine.shutdown()
		}

		ServerTickEvents.END_SERVER_TICK.register { server ->
			ChunkLoadPipeline.onServerTickEnd(engine)
			pushPlayerChunk(server, engine)
		}

		ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
			ChunkGenerationHooks.dispatch(world, chunk, ChunkGenerationStage.LOADED)
		}
	}

	/** 每 20 tick（约 1 秒）推送玩家 chunk 到 GPU 驻留层（距离 LRU 驱逐评分）。 */
	private var playerChunkTick = 0

	private fun pushPlayerChunk(server: net.minecraft.server.MinecraftServer, engine: EngineBridge) {
		if (++playerChunkTick % 20 != 0) {
			return
		}
		val player = server.playerList.players.firstOrNull() ?: return
		engine.setPlayerChunk(player.blockX shr 4, player.blockZ shr 4)
	}
}
