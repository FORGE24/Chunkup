package cn.sanrolnet.chunkup.client.sodium

import cn.sanrolnet.chunkup.ChunkupConfig
import net.minecraft.core.SectionPos
import java.util.concurrent.atomic.AtomicInteger

/**
 * 渲染 section 自上而下分层解锁：先 mesh 玩家附近地表，再逐 section 向下扩展。
 *
 * - `chunkup.layeredSections` — 默认 true
 * - `chunkup.layeredSections.rate` — 每 tick 向下解锁的 section 层数（默认 3）
 * - `chunkup.layeredSections.initialDepth` — 初始仅允许锚点下方几层（默认 1）
 * - `chunkup.layeredSections.headroom` — 锚点上方保留层数（默认 2）
 */
object LayeredSectionPolicy {
	private val anchorSectionY = AtomicInteger(0)
	private val depthBelow = AtomicInteger(1)
	@Volatile
	private var initialized = false

	@JvmStatic
	val enabled: Boolean
		get() = ChunkupConfig.layeredSections

	@JvmStatic
	val layersPerTick: Int
		get() = ChunkupConfig.layeredSectionsRate

	@JvmStatic
	val initialDepth: Int
		get() = ChunkupConfig.layeredSectionsInitialDepth

	@JvmStatic
	val headroomSections: Int
		get() = ChunkupConfig.layeredSectionsHeadroom

	@JvmStatic
	fun resetAnchor(blockY: Int) {
		anchorSectionY.set(SectionPos.blockToSectionCoord(blockY))
		depthBelow.set(initialDepth)
		initialized = true
	}

	@JvmStatic
	fun onClientTick(bottomSectionY: Int) {
		if (!enabled || !initialized) {
			return
		}
		val anchor = anchorSectionY.get()
		val maxDepth = (anchor - bottomSectionY).coerceAtLeast(0) + headroomSections
		val next = depthBelow.get() + layersPerTick
		depthBelow.set(next.coerceAtMost(maxDepth))
	}

	@JvmStatic
	fun allowSectionMesh(sectionY: Int): Boolean {
		if (!enabled || !initialized) {
			return true
		}
		val anchor = anchorSectionY.get()
		val minY = anchor - depthBelow.get()
		val maxY = anchor + headroomSections
		return sectionY in minY..maxY
	}

	@JvmStatic
	fun currentAnchorSectionY(): Int = anchorSectionY.get()

	@JvmStatic
	fun depthBelow(): Int = depthBelow.get()

	@JvmStatic
	fun debugLine(): String {
		if (!enabled) {
			return "layeredSections=off"
		}
		return "layered anchor=${anchorSectionY.get()} depthBelow=${depthBelow.get()}/rate=$layersPerTick headroom=$headroomSections"
	}
}
