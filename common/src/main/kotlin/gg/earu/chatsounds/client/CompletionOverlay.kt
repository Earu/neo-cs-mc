package gg.earu.chatsounds.client

import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * The chat-screen suggestion overlay, loader-agnostic (only vanilla GuiGraphics). GMod's
 * layout flipped upward: selected first, wrap-around, separator before the wrapped head.
 */
object CompletionOverlay {
    private var lastChatInput: String? = null

    /** The ';' stripped off the input before completing, re-added to the Tab replacement. */
    private var strippedPrefix = ""

    /** Call every chat-screen frame with the current input value before [render]. */
    fun pollInput(value: String) {
        if (value == lastChatInput) return
        lastChatInput = value
        strippedPrefix = ""
        if (value.startsWith("/")) {
            CompletionEngine.clear()
            return
        }
        // Same ';' gate as playback: silenced messages get no suggestions, and with
        // invertPrefix the prefix is stripped so the trie sees the actual sound text.
        val effective = ChatsoundsPlayer.effectiveText(value)
        if (effective == null) {
            CompletionEngine.clear()
            return
        }
        strippedPrefix = value.substring(0, value.length - effective.length)
        CompletionEngine.onTextChanged(effective)
    }

    fun render(graphics: GuiGraphics, screenHeight: Int) {
        if (!ClientConfig.data.enabled) return
        val mc = Minecraft.getInstance()
        val font = mc.font
        val baseY = screenHeight - 30 // just above the chat input box
        val lineHeight = font.lineHeight + 1

        // Anchor to the right of the chat history panel so suggestions never overlap it.
        val baseX = mc.gui.chat.width + 14
        val backdrop = 0x90000000.toInt()

        fun drawRow(prefix: String?, text: String, extra: String?, y: Int, textColor: Int) {
            var width = font.width(text) + 2
            if (prefix != null) width += 30
            extra?.let { width += 12 + font.width(it) }
            graphics.fill(baseX - 2, y - 1, baseX + width, y + font.lineHeight, backdrop)
            var x = baseX
            if (prefix != null) {
                graphics.drawString(font, prefix, x, y, 0xC8C8FF)
                x += 30
            }
            graphics.drawString(font, text, x, y, textColor)
            extra?.let { graphics.drawString(font, it, x + font.width(text) + 12, y, 0xFFC850) }
        }

        DataLoader.loading?.let { loading ->
            drawRow(null, "Loading chatsounds... ${loading.percent}%", null, baseY, 0xFFFFFF)
            return
        }

        val suggestions = CompletionEngine.suggestions
        if (suggestions.isEmpty()) return

        val selected = CompletionEngine.index
        val maxRows = (baseY / lineHeight) - 2
        var row = 0

        fun draw(indexInList: Int, isSelected: Boolean) {
            if (row >= maxRows) return
            val suggestion = suggestions[indexInList]
            val y = baseY - row * lineHeight
            drawRow("%03d.".format(indexInList + 1), suggestion.text, suggestion.extra, y, if (isSelected) 0xFF4040 else 0xFFFFFF)
            row++
        }

        val start = maxOf(0, selected)
        for (i in start until suggestions.size) draw(i, i == selected)
        if (start > 0) {
            if (row < maxRows) {
                drawRow(null, "==================", null, baseY - row * lineHeight, 0xB4B4FF)
                row++
            }
            for (i in 0 until start) draw(i, false)
        }
    }

    /** Tab pressed with [currentValue] in the box; returns the replacement text or null. */
    fun onTab(currentValue: String, reverse: Boolean): String? {
        if (!ClientConfig.data.enabled) return null
        if (currentValue.startsWith("/")) return null // vanilla command completion owns Tab there
        if (CompletionEngine.suggestions.isEmpty()) return null
        return CompletionEngine.cycle(reverse)?.let { strippedPrefix + it }?.also { lastChatInput = it }
    }
}
