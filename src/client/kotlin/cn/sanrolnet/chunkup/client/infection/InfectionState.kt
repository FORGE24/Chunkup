package cn.sanrolnet.chunkup.client.infection

/**
 * 「第一次传染」渲染状态机。
 *
 * ACCUMULATING — 32×32 区块尚未全部 FULL，区内 Sodium mesh/绘制被门控
 * PACKING      — 全区就绪，正在 CPU 采集 + GPU batch mesh
 * INFECTED     — 批次已上传 GPU，区内由 Chunkup 绘制（Sodium 旁路）
 * STALE        — 玩家移出锚点，待重新锚定
 */
enum class InfectionState {
	ACCUMULATING,
	PACKING,
	INFECTED,
	STALE,
}
