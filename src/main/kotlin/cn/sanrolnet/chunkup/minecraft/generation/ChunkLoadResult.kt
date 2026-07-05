package cn.sanrolnet.chunkup.minecraft.generation

/**
 * GPU 区块加载（天空光 + 面剔除）结果。
 *
 * 布局与 `native/common/chunkup_kernel.h` 一致：`index = ly * 256 + lz * 16 + lx`
 */
data class ChunkLoadResult(
	val skylight: ByteArray,
	val faceMask: ByteArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false
		other as ChunkLoadResult
		return skylight.contentEquals(other.skylight) && faceMask.contentEquals(other.faceMask)
	}

	override fun hashCode(): Int {
		var result = skylight.contentHashCode()
		result = 31 * result + faceMask.contentHashCode()
		return result
	}
}
