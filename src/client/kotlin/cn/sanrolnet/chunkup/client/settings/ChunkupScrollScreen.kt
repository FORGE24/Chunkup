package cn.sanrolnet.chunkup.client.settings

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 带垂直滚动的设置屏基类，小分辨率下也能访问全部控件。
 */
abstract class ChunkupScrollScreen(title: Component) : Screen(title) {
	protected var scrollOffset = 0
		private set

	private var maxScroll = 0
	private var contentHeight = 0

	protected val contentTop: Int
		get() = 36 - scrollOffset

	protected abstract fun buildScrollableContent(left: Int, startY: Int, labelWidth: Int, buttonWidth: Int): Int

	protected open fun buildFixedFooter(startY: Int) {}

	override fun init() {
		rebuildScrollLayout()
	}

	private fun rebuildScrollLayout() {
		clearWidgets()
		val labelWidth = 220
		val buttonWidth = 120
		val left = width / 2 - (labelWidth + buttonWidth) / 2

		contentHeight = buildScrollableContent(left, contentTop, labelWidth, buttonWidth)
		val footerY = (contentTop + contentHeight + 8).coerceAtLeast(height - 76)
		buildFixedFooter(footerY)

		maxScroll = (contentHeight - height + 120).coerceAtLeast(0)
		scrollOffset = scrollOffset.coerceIn(0, maxScroll)
		if (scrollOffset > 0) {
			clearWidgets()
			buildScrollableContent(left, contentTop, labelWidth, buttonWidth)
			buildFixedFooter((contentTop + contentHeight + 8).coerceAtLeast(height - 76))
		}
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
		if (maxScroll <= 0) {
			return super.mouseScrolled(mouseX, mouseY, delta)
		}
		val step = (delta * 16).toInt().coerceAtLeast(1)
		scrollOffset = (scrollOffset - step).coerceIn(0, maxScroll)
		rebuildScrollLayout()
		return true
	}

	override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		renderBackground(guiGraphics)
		renderHeader(guiGraphics)
		if (maxScroll > 0) {
			guiGraphics.drawString(font, "滚动: $scrollOffset/$maxScroll", 8, height - 12, 0x808080, false)
		}
		super.render(guiGraphics, mouseX, mouseY, partialTick)
	}

	protected open fun renderHeader(guiGraphics: GuiGraphics) {
		guiGraphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF)
	}
}
