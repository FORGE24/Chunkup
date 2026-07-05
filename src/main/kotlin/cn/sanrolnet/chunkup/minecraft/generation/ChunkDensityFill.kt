package cn.sanrolnet.chunkup.minecraft.generation

/**
 * 引擎密度场 + Aquifer 流体标记。
 *
 * [fluid]：0=无（空气/实心），1=水，2=熔岩
 */
data class ChunkDensityFill(
	val density: FloatArray,
	val fluid: ByteArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false
		other as ChunkDensityFill
		return density.contentEquals(other.density) && fluid.contentEquals(other.fluid)
	}

	override fun hashCode(): Int {
		var result = density.contentHashCode()
		result = 31 * result + fluid.contentHashCode()
		return result
	}
}
