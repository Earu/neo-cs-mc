package gg.earu.chatsounds.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Rendezvous between a long message and the chat message vanilla trimmed it down to.
 *
 * The client sends the untruncated text on the mod channel and then lets vanilla send the
 * chat message as usual. Both arrive on the same connection but reach the server thread
 * through different dispatch paths, so THEY CAN ARRIVE IN EITHER ORDER; each side checks
 * for the other and the first to find its twin wins. A chat message that is exactly the
 * vanilla cap bears the truncation signature, so it waits [graceSeconds] for its payload
 * before [flush] releases it as-is; anything shorter was never truncated and passes
 * straight through. Correlation never touches the wire: matching is by player, arrival
 * window, and the full text starting with the truncated one.
 */
class PendingLongMessages(private val ttlSeconds: Double = 5.0, private val graceSeconds: Double = 1.0) {
    companion object {
        const val VANILLA_CHAT_LIMIT = 256
    }

    class Ready(val player: UUID, val text: String)

    private class Entry(val text: String, val at: Double)

    private val fullByPlayer = ConcurrentHashMap<UUID, Entry>()
    private val chatByPlayer = ConcurrentHashMap<UUID, Entry>()

    private fun pairs(full: String, chat: String) = full.length > chat.length && full.startsWith(chat)

    /**
     * The untruncated payload text. Returns the text to relay right away when its chat
     * message is already waiting; otherwise holds it for [offerChat].
     */
    fun offerFull(player: UUID, text: String, now: Double): String? {
        val chat = chatByPlayer[player]
        if (chat != null && now - chat.at <= graceSeconds && pairs(text, chat.text)) {
            chatByPlayer.remove(player)
            return text
        }
        fullByPlayer[player] = Entry(text, now)
        return null
    }

    /**
     * A chat message as the server saw it. Returns the text to relay now (the full text
     * when a held payload pairs, the chat text itself when nothing was truncated), or null
     * when the message bears the truncation signature and should wait for [flush]. A still
     * waiting previous chat message is released as the relay text rather than dropped.
     */
    fun offerChat(player: UUID, chatText: String, now: Double): String? {
        val full = fullByPlayer.remove(player)
        if (full != null && now - full.at <= ttlSeconds && pairs(full.text, chatText)) {
            return full.text
        }
        if (chatText.length == VANILLA_CHAT_LIMIT) {
            return chatByPlayer.put(player, Entry(chatText, now))?.text
        }
        return chatText
    }

    /** Chat messages whose grace expired with no payload: relay them truncated. */
    fun flush(now: Double): List<Ready> {
        val out = ArrayList<Ready>()
        for ((player, entry) in chatByPlayer) {
            if (now - entry.at > graceSeconds && chatByPlayer.remove(player, entry)) {
                out.add(Ready(player, entry.text))
            }
        }
        fullByPlayer.entries.removeIf { now - it.value.at > ttlSeconds }
        return out
    }

    fun forget(player: UUID) {
        fullByPlayer.remove(player)
        chatByPlayer.remove(player)
    }
}
