package cn.sanrolnet.chunkup.client.pipeline

import cn.sanrolnet.chunkup.Chunkup
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge
import cn.sanrolnet.chunkup.client.render.SectionBuildCache
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import cn.sanrolnet.chunkup.client.sodium.SodiumSectionScheduler
import cn.sanrolnet.chunkup.render.SectionKey
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import org.slf4j.LoggerFactory
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ClientSectionPipeline {
	private val LOGGER = LoggerFactory.getLogger("${Chunkup.MOD_ID}.client.pipeline")
	private val pending = PriorityQueue<BuildRequest>(compareBy { it.distanceSq })
	private val queued = ConcurrentHashMap.newKeySet<SectionKey>()
	private val running = AtomicBoolean(false)

	private var lastPlayerX = 0.0
	private var lastPlayerY = 0.0
	private var lastPlayerZ = 0.0
	private var hasLastPlayerPos = false

	fun init() {
		if (!SodiumIntegration.isLoaded) {
			LOGGER.info("Sodium not loaded; section pipeline disabled")
			return
		}

		ClientChunkEvents.CHUNK_LOAD.register { _: Level?, chunk: LevelChunk ->
			scheduleChunk(chunk)
		}
		ClientChunkEvents.CHUNK_UNLOAD.register { _: Level?, chunk: LevelChunk ->
			SectionBuildCache.invalidateChunk(chunk.pos.x, chunk.pos.z)
		}
		ClientTickEvents.END_CLIENT_TICK.register {
			onClientTick()
		}
		LOGGER.info("Client section pipeline ready")
	}

	private fun onClientTick() {
		val player = Minecraft.getInstance().player ?: return
		if (!hasLastPlayerPos) {
			lastPlayerX = player.x
			lastPlayerY = player.y
			lastPlayerZ = player.z
			hasLastPlayerPos = true
			return
		}

		val dx = player.x - lastPlayerX
		val dy = player.y - lastPlayerY
		val dz = player.z - lastPlayerZ
		if (dx * dx + dy * dy + dz * dz <= 16.0) {
			return
		}

		lastPlayerX = player.x
		lastPlayerY = player.y
		lastPlayerZ = player.z
		reprioritizeNearbySections()
	}

	private fun reprioritizeNearbySections() {
		val player = Minecraft.getInstance().player ?: return
		val world = Minecraft.getInstance().level ?: return
		val renderDistance = Minecraft.getInstance().options.renderDistance().get()
		val chunkRadius = renderDistance + 1
		val centerChunkX = player.blockPosition().x shr 4
		val centerChunkZ = player.blockPosition().z shr 4

		for (chunkX in (centerChunkX - chunkRadius)..(centerChunkX + chunkRadius)) {
			for (chunkZ in (centerChunkZ - chunkRadius)..(centerChunkZ + chunkRadius)) {
				val chunk = world.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
				scheduleChunk(chunk, reprioritize = true)
			}
		}
	}

	private fun scheduleChunk(chunk: LevelChunk, reprioritize: Boolean = false) {
		val player = Minecraft.getInstance().player ?: return
		val px = player.x
		val py = player.y
		val pz = player.z

		val minSection = chunk.level.minSection
		val maxSection = chunk.level.maxSection

		for (sectionY in minSection until maxSection) {
			val sectionX = chunk.pos.x shl 4
			val sectionZ = chunk.pos.z shl 4
			val key = SectionKey(sectionX, sectionY shl 4, sectionZ)
			if (SectionBuildCache.get(key) != null) continue

			val cx = sectionX + 8.0
			val cy = (sectionY shl 4) + 8.0
			val cz = sectionZ + 8.0
			val dx = cx - px
			val dy = cy - py
			val dz = cz - pz
			val distanceSq = dx * dx + dy * dy + dz * dz

			if (reprioritize) {
				synchronized(pending) {
					val retained = pending.filter { it.key != key }
					pending.clear()
					pending.addAll(retained)
				}
				queued.remove(key)
			} else if (!queued.add(key)) {
				continue
			}

			synchronized(pending) {
				pending.add(BuildRequest(key, chunk, sectionY, distanceSq))
			}
		}
		drainQueue()
	}

	private fun drainQueue() {
		if (!running.compareAndSet(false, true)) return
		Thread {
			try {
				while (true) {
					val request = synchronized(pending) {
						pending.poll()
					} ?: break

					queued.remove(request.key)
					if (SectionBuildCache.get(request.key) != null) continue

					val blockStates = extractBlockStates(request.chunk, request.sectionY)
					val payload = ClientEngineBridge.onSectionBuild(
						request.key.sectionX,
						request.key.sectionY,
						request.key.sectionZ,
						blockStates,
					)
					if (payload != null && payload.ready) {
						SectionBuildCache.put(request.key, payload)
						SodiumSectionScheduler.scheduleRebuild(request.key)
						cn.sanrolnet.chunkup.client.lod.LodSectionBridge.active?.onSectionPayloadReady(request.key, payload)
					}
				}
			} finally {
				running.set(false)
				val hasMore = synchronized(pending) { pending.isNotEmpty() }
				if (hasMore) drainQueue()
			}
		}.apply {
			name = "chunkup-section-build"
			isDaemon = true
			start()
		}
	}

	private fun extractBlockStates(chunk: LevelChunk, sectionY: Int): ByteArray {
		val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY))
		val states = ByteArray(4096)
		var index = 0
		for (y in 0 until 16) {
			for (z in 0 until 16) {
				for (x in 0 until 16) {
					val state = section.getBlockState(x, y, z)
					states[index++] = when {
						state.isAir -> 0
						!state.fluidState.isEmpty -> 2
						state.blocksMotion() -> 1
						else -> 3
					}
				}
			}
		}
		return states
	}

	private data class BuildRequest(
		val key: SectionKey,
		val chunk: LevelChunk,
		val sectionY: Int,
		val distanceSq: Double,
	)
}
