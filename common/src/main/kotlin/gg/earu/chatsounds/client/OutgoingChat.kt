package gg.earu.chatsounds.client

import net.minecraft.client.Minecraft

/**
 * Outgoing long messages (GMod nets sound keys up to 60000 chars). Raising the chat box's
 * max length is only half the job: ChatScreen#handleChatInput trims to 256 before calling
 * ClientPacketListener#sendChat, which is where both loaders' outgoing-chat hooks live, so
 * the mod channel has to be taken from the screen itself. ChatScreenMixin calls in here.
 */
object OutgoingChat {
    const val VANILLA_CHAT_LIMIT = 256

    private val WHITESPACE = Regex("\\s+")

    /** Loader hook: sends the saysound payload; false when the server lacks the channel. */
    var sendLong: ((String) -> Boolean)? = null

    /**
     * Returns true when the message left through the mod channel and vanilla must not send
     * it. Anything a vanilla server can carry stays on the vanilla path, commands included:
     * only chat is relayed, and a truncated command is better than a silently dropped one.
     */
    fun intercept(rawText: String, addToHistory: Boolean): Boolean {
        val text = rawText.trim().replace(WHITESPACE, " ")
        if (text.length <= VANILLA_CHAT_LIMIT || text.startsWith("/")) return false
        if (sendLong?.invoke(text) != true) return false
        if (addToHistory) Minecraft.getInstance().gui.chat.addRecentChat(text)
        return true
    }
}
