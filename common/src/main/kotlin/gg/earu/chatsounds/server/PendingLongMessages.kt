package gg.earu.chatsounds.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairs a long message with the chat message vanilla trimmed it down to.
 *
 * The client sends the untruncated text on the mod channel and then lets vanilla send the
 * chat message as usual, so both arrive in that order on the same connection. Matching them
 * by order and prefix keeps the correlation off the wire entirely: nothing is appended to
 * what players actually see, and the chat message stays an ordinary signed message.
 *
 * A pair that does not line up (stale, or the chat text is not the head of the long one) is
 * dropped rather than guessed at; the caller then relays the truncated text as before.
 */
class PendingLongMessages(private val ttlSeconds: Double = 5.0) {
    private class Entry(val text: String, val at: Double)

    private val byPlayer = ConcurrentHashMap<UUID, Entry>()

    /** The untruncated text, held until its chat message lands. */
    fun offer(player: UUID, text: String, now: Double) {
        byPlayer[player] = Entry(text, now)
    }

    fun forget(player: UUID) {
        byPlayer.remove(player)
    }

    /** The untruncated text for [chatText], or null when the two do not pair up. */
    fun take(player: UUID, chatText: String, now: Double): String? {
        val entry = byPlayer.remove(player) ?: return null
        if (now - entry.at > ttlSeconds) return null
        return entry.text.takeIf { it.length > chatText.length && it.startsWith(chatText) }
    }
}
