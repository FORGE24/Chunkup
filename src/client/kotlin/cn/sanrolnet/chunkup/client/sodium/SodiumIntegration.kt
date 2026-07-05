package cn.sanrolnet.chunkup.client.sodium

import net.fabricmc.loader.api.FabricLoader

object SodiumIntegration {
	val isLoaded: Boolean
		get() = FabricLoader.getInstance().isModLoaded("sodium")

	/**
	 * 启用 Chunkup section mesh 构建（完整三 pass，与 Sodium 顶点格式兼容）。
	 * 可通过 `-Dchunkup.gpuSections=false` 回退 Sodium 原生 meshing。
	 */
	@JvmStatic
	val useGpuSectionMeshes: Boolean
		get() = System.getProperty("chunkup.gpuSections", "true").toBoolean()
}
