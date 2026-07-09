package cn.sanrolnet.chunkup.client.infection

import cn.sanrolnet.chunkup.ChunkupConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ChunkPos
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * 「第一次传染」协调器：
 * 1. 以玩家 chunk 为锚定 32×32 区域
 * 2. 等待区内全部 chunk FULL
 * 3. 一次性打包 → GPU batch mesh（Phase 2: native）
 * 4. 区内 Sodium mesh/绘制旁路
 */
object InfectionCoordinator {
	private val LOGGER = LoggerFactory.getLogger("chunkup.client.infection")

	private val state = AtomicReference(InfectionState.STALE)
	private var activeZone: InfectionZone? = null
	private var lastPack: InfectionBatchPackager.PackResult? = null

	@JvmStatic
	val enabled: Boolean
		get() = ChunkupConfig.infectionRender

	@JvmStatic
	fun currentState(): InfectionState = state.get()

	@JvmStatic
	fun currentZone(): InfectionZone? = activeZone

	@JvmStatic
	fun lastPackResult(): InfectionBatchPackager.PackResult? = lastPack

	@JvmStatic
	fun onClientTick(client: Minecraft) {
		if (!enabled) {
			if (state.get() != InfectionState.STALE) {
				reset()
			}
			return
		}

		val level = client.level ?: return
		val player = client.player ?: return
		val playerChunk = ChunkPos(player.blockPosition())

		when (val current = state.get()) {
			InfectionState.STALE, InfectionState.INFECTED -> {
				val zone = InfectionZone.aroundPlayer(playerChunk.x, playerChunk.z)
				if (activeZone != null && activeZone != zone && current == InfectionState.INFECTED) {
					// 玩家离开已感染锚点 → 作废，重新累积
					LOGGER.info("infection stale: player left infected anchor")
					reset()
				}
				activeZone = zone
				state.set(InfectionState.ACCUMULATING)
			}

			InfectionState.ACCUMULATING -> {
				val zone = activeZone ?: return
				if (!zone.containsChunk(playerChunk.x, playerChunk.z)) {
					activeZone = InfectionZone.aroundPlayer(playerChunk.x, playerChunk.z)
					return
				}
				if (InfectionZoneReadiness.isFullyReady(level, zone)) {
					beginPacking(level, zone)
				}
			}

			InfectionState.PACKING -> Unit
		}
	}

	private fun beginPacking(level: ClientLevel, zone: InfectionZone) {
		state.set(InfectionState.PACKING)
		LOGGER.info("infection PACKING: {}", InfectionZoneReadiness.progressLine(level, zone))

		// Phase 1: 同步打包（Phase 2: 丢到 native 线程 + CUDA batch mesh + GL VBO）
		lastPack = InfectionBatchPackager.packZone(level, zone)
		state.set(InfectionState.INFECTED)

		LOGGER.info(
			"infection INFECTED: {} sections ready for GPU draw (Sodium bypass active in zone)",
			lastPack?.sectionCount ?: 0,
		)
	}

	/**
	 * Sodium 是否应处理此 section（mesh 或 draw）。
	 * false = Chunkup 接管或尚未到渲染时机。
	 */
	@JvmStatic
	fun allowSodiumForSection(originX: Int, originZ: Int): Boolean {
		if (!enabled) {
			return true
		}
		val zone = activeZone ?: return true
		if (!zone.containsSectionOrigin(originX, originZ)) {
			return true
		}
		return when (state.get()) {
			InfectionState.INFECTED -> false
			InfectionState.ACCUMULATING, InfectionState.PACKING -> false
			InfectionState.STALE -> true
		}
	}

	@JvmStatic
	fun debugLines(): List<String> {
		if (!enabled) {
			return emptyList()
		}
		val zone = activeZone
		val lines = mutableListOf<String>()
		lines += " infection=${state.get()} radius=${ChunkupConfig.infectionRadiusChunks * 2}"
		if (zone != null) {
			lines += " anchor=[${zone.centerChunkX}, ${zone.centerChunkZ}]"
			lastPack?.let {
				lines += " pack sections=${it.sectionCount} ~${it.payloadBytes / 1024}KB"
			}
		}
		return lines
	}

	@JvmStatic
	fun reset() {
		state.set(InfectionState.STALE)
		activeZone = null
		lastPack = null
	}
}
