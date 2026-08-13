package gg.earu.chatsounds.client

/**
 * Outgoing long messages (GMod nets sound keys up to 60000 chars). The chat message itself
 * always stays on the vanilla path: signed, moderated, broadcast, logged, and trimmed to
 * 256 by ChatScreen#handleChatInput as usual. For text past the cap this sends the full
 * version ahead on the mod channel; the server pairs it with the trimmed chat message that
 * follows on the same connection ([PendingLongMessages]) and relays it for playback.
 * ChatScreenMixin calls in here before vanilla trims, and never cancels anything.
 */
object OutgoingChat {
    const val VANILLA_CHAT_LIMIT = 256

    private val WHITESPACE = Regex("\\s+")

    /** Loader hook: sends the saysound payload; false when the server lacks the channel. */
    var sendLong: ((String) -> Boolean)? = null

    /**
     * Ships the untruncated text ahead of the chat message. Normalized exactly like
     * vanilla's normalizeChatMessage so the server can prefix-match the two. Commands and
     * anything under the cap have nothing to pair; a server without the channel just gets
     * the vanilla truncation, as before.
     */
    fun beforeChatSend(rawText: String) {
        val text = rawText.trim().replace(WHITESPACE, " ")
        if (text.length <= VANILLA_CHAT_LIMIT || text.startsWith("/")) return
        sendLong?.invoke(text)
    }
}
