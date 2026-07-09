package cn.sanrolnet.chunkup.client.pipeline

import cn.sanrolnet.chunkup.ChunkupConfig
import cn.sanrolnet.chunkup.client.infection.InfectionCoordinator
import cn.sanrolnet.chunkup.client.mixin.sodium.RenderSectionManagerAccess
import cn.sanrolnet.chunkup.client.sodium.LayeredSectionPolicy
import cn.sanrolnet.chunkup.client.sodium.SodiumIntegration
import cn.sanrolnet.chunkup.render.SectionKey
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector
import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos
import net.minecraft.world.entity.player.Player
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 区块加载时预渲染：在 Sodium 可见性遍历之前，按与玩家的距离优先提交 section mesh。
 *
 * - `chunkup.preRenderOnLoad` — 总开关（默认 true）
 * - `chunkup.preRender.budget` — 每帧最多提交的 section 数（默认 8）
 */
object SectionLoadPreRenderer {
	private const val MAX_PENDING = 4096

	private data class Entry(
		val sectionX: Int,
		val sectionY: Int,
		val sectionZ: Int,
		val distanceSq: Double,
	) : Comparable<Entry> {
		override fun compareTo(other: Entry): Int =
			distanceSq.compareTo(other.distanceSq)
	}

	private val pending = PriorityQueue<Entry>()
	private val pendingKeys = ConcurrentHashMap.newKeySet<Long>()
	private val lock = Any()

	private val queuedTotal = AtomicLong(0)
	private val submittedTotal = AtomicLong(0)
	private val skippedTotal = AtomicLong(0)
	private val deferredTotal = AtomicLong(0)

	@JvmStatic
	val enabled: Boolean
		get() = ChunkupConfig.preRenderOnLoad && SodiumIntegration.isLoaded

	@JvmStatic
	fun onSectionAdded(sectionX: Int, sectionY: Int, sectionZ: Int) {
		if (!enabled) {
			return
		}
		if (!LayeredSectionPolicy.allowSectionMesh(sectionY)) {
			skippedTotal.incrementAndGet()
			return
		}

		val originX = sectionX shl 4
		val originZ = sectionZ shl 4
		if (!InfectionCoordinator.allowSodiumForSection(originX, originZ)) {
			skippedTotal.incrementAndGet()
			return
		}

		val player = Minecraft.getInstance().player ?: return
		val distanceSq = distanceSqToSection(player, sectionX, sectionY, sectionZ)

		val key = SectionKey(sectionX, sectionY, sectionZ).asLong
		synchronized(lock) {
			if (!pendingKeys.add(key)) {
				return
			}
			if (pending.size >= MAX_PENDING) {
				pendingKeys.remove(key)
				skippedTotal.incrementAndGet()
				return
			}
			pending.offer(Entry(sectionX, sectionY, sectionZ, distanceSq))
		}
		queuedTotal.incrementAndGet()
	}

	@JvmStatic
	fun flush(manager: RenderSectionManagerAccess, collector: ChunkJobCollector) {
		if (!enabled || !collector.canOffer()) {
			return
		}

		refreshPriorities()

		val frame = manager.chunkupGetLastUpdatedFrame()
		val sections = manager.chunkupGetSectionByPosition()
		val builder = manager.chunkupGetBuilder()

		// 每帧只扫描当前队列一轮；忙碌/未解锁的条目延后到下一帧，避免 requeue+poll 死循环。
		val batch = drainPending()
		if (batch.isEmpty()) {
			return
		}

		val deferred = ArrayList<Entry>(batch.size.coerceAtMost(64))
		for (entry in batch) {
			if (!collector.canOffer()) {
				deferred.add(entry)
				continue
			}

			if (!LayeredSectionPolicy.allowSectionMesh(entry.sectionY)) {
				deferred.add(entry)
				deferredTotal.incrementAndGet()
				continue
			}

			val section = sections.get(SectionPos.asLong(entry.sectionX, entry.sectionY, entry.sectionZ))
			if (section == null || section.isDisposed) {
				skippedTotal.incrementAndGet()
				continue
			}
			if (section.buildCancellationToken != null) {
				deferred.add(entry)
				deferredTotal.incrementAndGet()
				continue
			}
			if (section.pendingUpdate == null && section.isBuilt) {
				continue
			}

			val task = manager.chunkupCreateRebuildTask(section, frame)
			if (task == null) {
				skippedTotal.incrementAndGet()
				continue
			}

			val job = builder.scheduleTask(task, true, collector::onJobFinished)
			collector.addSubmittedJob(job)
			section.buildCancellationToken = job
			section.lastSubmittedFrame = frame
			section.pendingUpdate = null
			submittedTotal.incrementAndGet()
		}

		for (entry in deferred) {
			requeue(entry)
		}
	}

	@JvmStatic
	fun onPlayerTeleported() {
		clear()
	}

	@JvmStatic
	fun clear() {
		synchronized(lock) {
			pending.clear()
			pendingKeys.clear()
		}
	}

	@JvmStatic
	fun pendingCount(): Int = synchronized(lock) { pending.size }

	@JvmStatic
	fun debugLine(): String =
		"preRender on=${enabled} pending=${pendingCount()} queued=${queuedTotal.get()} " +
			"submitted=${submittedTotal.get()} deferred=${deferredTotal.get()} " +
			"skipped=${skippedTotal.get()} budget=${ChunkupConfig.preRenderBudgetPerFrame}"

	private fun drainPending(): List<Entry> {
		synchronized(lock) {
			if (pending.isEmpty()) {
				return emptyList()
			}
			val batch = ArrayList<Entry>(pending.size)
			while (true) {
				val entry = pending.poll() ?: break
				pendingKeys.remove(SectionKey(entry.sectionX, entry.sectionY, entry.sectionZ).asLong)
				batch.add(entry)
			}
			return batch
		}
	}

	private fun refreshPriorities() {
		val player = Minecraft.getInstance().player ?: return
		synchronized(lock) {
			if (pending.isEmpty()) {
				return
			}
			val entries = ArrayList<Entry>(pending.size)
			while (true) {
				pending.poll()?.let { entries.add(it) } ?: break
			}
			for (entry in entries) {
				val distanceSq = distanceSqToSection(player, entry.sectionX, entry.sectionY, entry.sectionZ)
				pending.offer(entry.copy(distanceSq = distanceSq))
			}
		}
	}

	private fun requeue(entry: Entry) {
		val key = SectionKey(entry.sectionX, entry.sectionY, entry.sectionZ).asLong
		synchronized(lock) {
			if (pending.size >= MAX_PENDING) {
				pendingKeys.remove(key)
				return
			}
			if (pendingKeys.add(key)) {
				pending.offer(entry)
			}
		}
	}

	private fun distanceSqToSection(player: Player, sectionX: Int, sectionY: Int, sectionZ: Int): Double {
		val originX = sectionX shl 4
		val originZ = sectionZ shl 4
		val centerX = originX + 8.0
		val centerY = (sectionY shl 4) + 8.0
		val centerZ = originZ + 8.0
		return player.distanceToSqr(centerX, centerY, centerZ)
	}
}
