package cn.sanrolnet.chunkup.render

import java.nio.ByteBuffer

data class SectionBuildPayload(
	val kind: SectionKind,
	val vertexData: ByteBuffer,
	val vertexSegments: IntArray,
	val visibilityData: LongArray,
	val ready: Boolean,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is SectionBuildPayload) return false
		return kind == other.kind &&
			vertexData == other.vertexData &&
			vertexSegments.contentEquals(other.vertexSegments) &&
			visibilityData.contentEquals(other.visibilityData) &&
			ready == other.ready
	}

	override fun hashCode(): Int {
		var result = kind.hashCode()
		result = 31 * result + vertexData.hashCode()
		result = 31 * result + vertexSegments.contentHashCode()
		result = 31 * result + visibilityData.contentHashCode()
		result = 31 * result + ready.hashCode()
		return result
	}
}
