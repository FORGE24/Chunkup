package cn.sanrolnet.chunkup.client.settings

import cn.sanrolnet.chunkup.ChunkupConfigFile
import cn.sanrolnet.chunkup.bridge.JniBridge
import cn.sanrolnet.chunkup.config.ChunkupSettingsSnapshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 游戏内设置界面（不依赖 Qt DLL），逗号键始终可用。
 */
class ChunkupFabricSettingsScreen(private val parentScreen: Screen?) : Screen(Component.literal("Chunkup 设置")) {
	private val snapshot: ChunkupSettingsSnapshot = ChunkupConfigFile.currentSnapshot()
	private var dirty = false

	override fun init() {
		var y = 40
		val rowHeight = 24
		val labelWidth = 220
		val buttonWidth = 120
		val left = width / 2 - (labelWidth + buttonWidth) / 2

		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "极速加载（推荐）", snapshot.instantLoad) {
			snapshot.instantLoad = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "强制 GPU（不回退 CPU）", snapshot.forceGpu) {
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
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "F3 调试信息", snapshot.f3Debug) {
			snapshot.f3Debug = it
		})
		y += rowHeight
		addRenderableWidget(cycle(left, y, labelWidth, buttonWidth, "攒批大小", BATCH_OPTIONS, snapshot.gpuChunkLoadBatchSize) {
			snapshot.gpuChunkLoadBatchSize = it
		})
		y += rowHeight + 8

		addRenderableWidget(
			Button.builder(Component.literal("保存并应用")) {
				applySnapshot()
				minecraft?.setScreen(parentScreen)
			}.bounds(width / 2 - 100, y, 200, 20).build(),
		)
		y += 28
		addRenderableWidget(
			Button.builder(Component.literal("取消")) {
				minecraft?.setScreen(parentScreen)
			}.bounds(width / 2 - 100, y, 200, 20).build(),
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

	private fun applySnapshot() {
		ChunkupConfigFile.applyRuntime(snapshot)
		ChunkupConfigFile.saveSnapshot(snapshot)
		JniBridge.setForceGpu(snapshot.forceGpu)
		dirty = false
	}

	override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		renderBackground(guiGraphics)
		guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF)
		guiGraphics.drawCenteredString(
			font,
			Component.literal("部分选项需重进世界后完全生效"),
			width / 2,
			24,
			0xA0A0A0,
		)
		super.render(guiGraphics, mouseX, mouseY, partialTick)
	}

	override fun onClose() {
		if (dirty) {
			applySnapshot()
		}
		super.onClose()
	}

	companion object {
		private val BATCH_OPTIONS = listOf(1, 8, 16, 32, 64, 128)
	}
}
