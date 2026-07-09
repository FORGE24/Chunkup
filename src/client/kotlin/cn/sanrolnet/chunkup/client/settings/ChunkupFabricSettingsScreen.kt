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
 *
 * 常用 Sodium 协同选项在主页；GPU 实验选项在 [ChunkupAdvancedSettingsScreen]。
 */
class ChunkupFabricSettingsScreen(
	private val parentScreen: Screen?,
	private val sharedSnapshot: ChunkupSettingsSnapshot? = null,
) : ChunkupScrollScreen(Component.literal("Chunkup 设置")) {
	private val snapshot: ChunkupSettingsSnapshot = sharedSnapshot ?: ChunkupConfigFile.currentSnapshot()
	private var dirty = false

	override fun buildScrollableContent(left: Int, startY: Int, labelWidth: Int, buttonWidth: Int): Int {
		var y = startY
		val rowHeight = 22

		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "GPU 世界生成", snapshot.gpuWorldGen) {
			snapshot.gpuWorldGen = it
			if (it) {
				snapshot.instantLoad = false
			}
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "极速加载（跳过 GPU 生成）", snapshot.instantLoad) {
			snapshot.instantLoad = it
			if (it) {
				snapshot.gpuWorldGen = false
			}
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "加载时预渲染", snapshot.preRenderOnLoad) {
			snapshot.preRenderOnLoad = it
		})
		y += rowHeight
		addRenderableWidget(cycle(left, y, labelWidth, buttonWidth, "预渲染 budget", BUDGET_OPTIONS, snapshot.preRenderBudgetPerFrame) {
			snapshot.preRenderBudgetPerFrame = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "分层 section mesh", snapshot.layeredSections) {
			snapshot.layeredSections = it
		})
		y += rowHeight
		addRenderableWidget(cycle(left, y, labelWidth, buttonWidth, "分层速率 /tick", RATE_OPTIONS, snapshot.layeredSectionsRate) {
			snapshot.layeredSectionsRate = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "F3 调试信息", snapshot.f3Debug) {
			snapshot.f3Debug = it
		})
		y += rowHeight
		addRenderableWidget(toggle(left, y, labelWidth, buttonWidth, "性能探针日志", snapshot.debugProbe) {
			snapshot.debugProbe = it
		})
		return y - startY + rowHeight
	}

	override fun buildFixedFooter(startY: Int) {
		var y = startY
		addRenderableWidget(
			Button.builder(Component.literal("高级 / GPU 实验…")) {
				minecraft?.setScreen(ChunkupAdvancedSettingsScreen(this, snapshot))
			}.bounds(width / 2 - 100, y, 200, 20).build(),
		)
		y += 28
		addRenderableWidget(
			Button.builder(Component.literal("Qt 原生界面…")) {
				minecraft?.let { client ->
					client.setScreen(parentScreen)
					ChunkupSettingsUi.openQtDialogAsync(client)
				}
			}.bounds(width / 2 - 100, y, 200, 20).build(),
		)
		y += 28
		addRenderableWidget(
			Button.builder(Component.literal("保存并应用")) {
				applySnapshot()
				minecraft?.setScreen(parentScreen)
			}.bounds(width / 2 - 100, y, 200, 20).build(),
		)
		y += 24
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

	override fun renderHeader(guiGraphics: GuiGraphics) {
		super.renderHeader(guiGraphics)
		guiGraphics.drawCenteredString(
			font,
			Component.literal("Sodium 协同与观测 — 部分选项即时生效"),
			width / 2,
			22,
			0xA0A0A0,
		)
	}

	override fun onClose() {
		if (dirty) {
			applySnapshot()
		}
		super.onClose()
	}

	companion object {
		private val BUDGET_OPTIONS = listOf(4, 8, 12, 16, 24, 32)
		private val RATE_OPTIONS = listOf(1, 2, 3, 4, 6, 8)
	}
}
