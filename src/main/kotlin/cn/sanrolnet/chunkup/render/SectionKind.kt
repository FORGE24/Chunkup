package cn.sanrolnet.chunkup.render

enum class SectionKind {
	AIR_ONLY,
	SOLID_UNIFORM,
	FLUID_HEAVY,
	MIXED,
	;

	companion object {
		fun fromOrdinal(ordinal: Int): SectionKind =
			entries.getOrElse(ordinal) { MIXED }
	}
}
