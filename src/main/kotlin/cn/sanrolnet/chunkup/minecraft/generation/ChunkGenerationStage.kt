package cn.sanrolnet.chunkup.minecraft.generation

/**
 * 区块生成管线阶段，与 Minecraft [ChunkStatus] 流水线对应。
 */
enum class ChunkGenerationStage {
	/** 噪声生物群系填充 */
	BIOMES,

	/** 三维噪声地形填充（核心引擎噪声后端入口） */
	NOISE_FILL,

	/** 地表方块放置 */
	SURFACE,

	/** 结构地物 / 生物群系装饰 */
	FEATURES,

	/** ProtoChunk → LevelChunk，首次生成完成 */
	GENERATED,

	/** 区块进入世界（含磁盘加载与新生成） */
	LOADED,
}
