package cn.sanrolnet.chunkup.client.sodium

import net.fabricmc.loader.api.FabricLoader

object SodiumIntegration {
	val isLoaded: Boolean
		get() = FabricLoader.getInstance().isModLoaded("sodium")

	/**
	 * Rust section 快路径（仅 AIR / uniform opaque shell）。
	 * 默认 false：复杂 section 一律由 Sodium 原生 worker meshing，避免线程池枯竭。
	 * 可通过 `-Dchunkup.gpuSections=true` 开启快路径。
	 */
	@JvmStatic
	val useGpuSectionMeshes: Boolean
		get() = System.getProperty("chunkup.gpuSections", "false").toBoolean()
}
