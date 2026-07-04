package cn.sanrolnet.chunkup.minecraft.generation

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.bridge.EngineBridge
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.ChunkAccess
import org.slf4j.LoggerFactory

/**
 * 区块生成 Hook 调度中心：Mixin 注入点 → 监听器 → Rust 引擎。
 */
object ChunkGenerationHooks {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.generation")
	private val listeners = mutableListOf<ChunkGenerationListener>()
	private var engine: EngineBridge? = null

	@JvmStatic
	fun bindEngine(bridge: EngineBridge) {
		engine = bridge
	}

	@JvmStatic
	fun register(listener: ChunkGenerationListener) {
		listeners += listener
	}

	@JvmStatic
	@JvmOverloads
	fun dispatch(
		level: ServerLevel?,
		chunk: ChunkAccess,
		stage: ChunkGenerationStage,
		newlyGenerated: Boolean = false,
	) {
		val ctx = ChunkGenerationContext(
			level = level ?: ChunkGenerationWorldContext.get(),
			chunk = chunk,
			stage = stage,
			newlyGenerated = newlyGenerated,
		)
		dispatch(ctx)
	}

	@JvmStatic
	fun dispatch(context: ChunkGenerationContext) {
		engine?.onChunkGeneration(context.stage, context.chunkX, context.chunkZ)
		notify(context)
	}

	/** 仅通知监听器，不重复调用引擎（密度写回后使用）。 */
	@JvmStatic
	fun notify(context: ChunkGenerationContext) {
		for (listener in listeners) {
			try {
				listener.onChunkGeneration(context)
			} catch (e: Exception) {
				LOGGER.error(
					"Chunk generation listener failed at {} [{}, {}]",
					context.stage,
					context.chunkX,
					context.chunkZ,
					e,
				)
			}
		}

		if (LOGGER.isTraceEnabled) {
			LOGGER.trace(
				"chunk stage={} pos=[{}, {}] new={} level={}",
				context.stage,
				context.chunkX,
				context.chunkZ,
				context.newlyGenerated,
				context.level?.dimension()?.location(),
			)
		}
	}
}
