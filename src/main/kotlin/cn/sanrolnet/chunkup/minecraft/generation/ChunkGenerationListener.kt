package cn.sanrolnet.chunkup.minecraft.generation

fun interface ChunkGenerationListener {
	fun onChunkGeneration(context: ChunkGenerationContext)
}
