package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.ChunkupConfigFile
import cn.sanrolnet.chunkup.bridge.JniBridge
import cn.sanrolnet.chunkup.config.ChunkupSettingsSnapshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.network.chat.Component

/**
 * 游戏内设置 — 高级 / GPU 实验选项。
 */
class ChunkupAdvancedSettingsScreen(
	private val parentScreen: ChunkupFabricSettingsScreen,
	private val snapshot: ChunkupSettingsSnapshot,
) : ChunkupScrollScreen(Component.literal("Chunkup 高级设置")) {
	private var dirty = false

	override fun buildScrollableContent(left: Int, startY: Int, labelWidth: Int, buttonWidth: Int): Int {
		var y = startY
		val rowHeight = 24

		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "GPU 密度攒批", snapshot.gpuDensityBatch) {
			snapshot.gpuDensityBatch = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "强制 GPU（禁用 CPU 回退）", snapshot.forceGpu) {
			snapshot.forceGpu = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "GENERATED 阶段 GPU", snapshot.gpuChunkLoadOnGenerated) {
			snapshot.gpuChunkLoadOnGenerated = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "LOADED 阶段 GPU", snapshot.gpuChunkLoadOnLoaded) {
			snapshot.gpuChunkLoadOnLoaded = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "GPU 天空光写回", snapshot.gpuSkylightApply) {
			snapshot.gpuSkylightApply = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "GPU Section Mesh", snapshot.gpuSections) {
			snapshot.gpuSections = it
		})
		y += rowHeight
		addRenderableWidget(cycle(left, y, labelWidth, buttonWidth, "攒批大小", BATCH_OPTIONS, snapshot.gpuChunkLoadBatchSize) {
			snapshot.gpuChunkLoadBatchSize = it
		})
		return y - startY + rowHeight
	}

	override fun buildFixedFooter(startY: Int) {
		addRenderableWidget(
			Button.builder(Component.literal("返回")) {
				minecraft?.setScreen(parentScreen)
			}.bounds(width / 2 - 100, startY, 200, 20).build(),
		)
	}

	private fun toggle(
		x: Int,
		y: Int,
		labelWidth: Int,
		buttonWidth: Int,
		label: String,
		initial: Boolean,
		onChange: (Boolean) -> Unit,
	): CycleButton<Boolean> {
		return CycleButton.onOffBuilder(initial)
			.create(x + labelWidth, y, buttonWidth, 20, Component.literal(label)) { _, value ->
				onChange(value)
				markDirty()
			}
	}

	private fun cycle(
		x: Int,
		y: Int,
		labelWidth: Int,
		buttonWidth: Int,
		label: String,
		options: List<Int>,
		initial: Int,
		onChange: (Int) -> Unit,
	): CycleButton<Int> {
		return CycleButton.builder { value: Int -> Component.literal("$label: $value") }
			.withValues(options)
			.withInitialValue(options.firstOrNull { it == initial } ?: options.first())
			.create(x, y, labelWidth + buttonWidth, 20, Component.literal(label)) { _, value ->
				onChange(value)
				markDirty()
			}
	}

	private fun markDirty() {
		dirty = true
	}

	override fun onClose() {
		if (dirty) {
			applySnapshot()
		}
		minecraft?.setScreen(parentScreen)
	}

	private fun applySnapshot() {
		ChunkupConfigFile.applyRuntime(snapshot)
		ChunkupConfigFile.saveSnapshot(snapshot)
		JniBridge.setForceGpu(snapshot.forceGpu)
		dirty = false
	}

	override fun renderHeader(guiGraphics: GuiGraphics) {
		super.renderHeader(guiGraphics)
		guiGraphics.drawCenteredString(
			font,
			Component.literal("实验性功能，可能影响稳定性"),
			width / 2,
			24,
			0xFF8080,
		)
	}

	companion object {
		private val BATCH_OPTIONS = listOf(1, 8, 16, 32, 64, 128)
	}
}
