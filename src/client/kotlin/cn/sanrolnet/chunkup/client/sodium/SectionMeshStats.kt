package cn.sanrolnet.chunkup.client.sodium

import java.util.concurrent.atomic.AtomicLong

/** Sodium section mesh 路由统计，供 F3 与日志排查 worker 积压。 */
object SectionMeshStats {
	private val airOnly = AtomicLong(0)
	private val rustFast = AtomicLong(0)
	private val sodiumFallback = AtomicLong(0)
	private val rustMiss = AtomicLong(0)

	@JvmStatic
	fun recordAirOnly() {
		airOnly.incrementAndGet()
	}

	@JvmStatic
	fun recordRustFast() {
		rustFast.incrementAndGet()
	}

	@JvmStatic
	fun recordSodiumFallback() {
		sodiumFallback.incrementAndGet()
	}

	@JvmStatic
	fun recordRustMiss() {
		rustMiss.incrementAndGet()
	}

	@JvmStatic
	fun lines(): List<String> = listOf(
		"sectionMesh airOnly=${airOnly.get()} rustFast=${rustFast.get()} " +
			"sodiumFallback=${sodiumFallback.get()} rustMiss=${rustMiss.get()}",
	)
}
