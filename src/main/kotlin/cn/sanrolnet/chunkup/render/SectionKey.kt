package cn.sanrolnet.chunkup.render

data class SectionKey(
	val sectionX: Int,
	val sectionY: Int,
	val sectionZ: Int,
) {
	val asLong: Long
		get() = (sectionX.toLong() and 0x3FFFFF) or
			((sectionY.toLong() and 0x3FF) shl 22) or
			((sectionZ.toLong() and 0x3FFFFF) shl 32)

	companion object {
		fun fromLong(pos: Long): SectionKey = SectionKey(
			sectionX = (pos and 0x3FFFFF).toInt(),
			sectionY = ((pos shr 22) and 0x3FF).toInt(),
			sectionZ = ((pos shr 32) and 0x3FFFFF).toInt(),
		)
	}
}
