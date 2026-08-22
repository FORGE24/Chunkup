package cn.sanrolnet.chunkup.client.sodium
import cn.sanrolnet.chunkup.client.bridge.ClientEngineBridge
import net.fabricmc.loader.api.FabricLoader
object SodiumIntegration {
	val isLoaded: Boolean
		get() = FabricLoader.getInstance().isModLoaded("sodium")
	@JvmStatic
	val useGpuSectionMeshes: Boolean
		get() {
			if (!System.getProperty("chunkup.gpuSections", "false").toBoolean()) {
				return false
			}
			val backend = ClientEngineBridge.activeComputeBackend()
			return backend == "cuda" || backend == "opencl"
		}
}