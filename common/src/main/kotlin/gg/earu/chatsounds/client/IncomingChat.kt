package gg.earu.chatsounds.client

import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Loader-agnostic incoming-chat processing: sender extraction for system-formatted chat,
 * hide-text, and the server-authoritative gate. Loader events call in and cancel their
 * event when [Result.hidden] is set.
 */
object IncomingChat {
    /** Set once the server's repo config payload arrives; sounds then come from relays only. */
    @Volatile var serverAuthoritative = false

    class Result(val hidden: Boolean)

    /** A player-signed chat message ([text] = raw typed content). */
    fun onPlayerChat(text: String, sender: UUID): Result {
        val playHere = !serverAuthoritative
        return process(text, sender, isOwn = sender == Minecraft.getInstance().player?.uuid, playHere)
    }

    /**
     * A system chat line (plugins reformat player chat into these). Returns null when no
     * sender pattern matched — the caller leaves the message alone.
     */
    fun onSystemChat(strippedText: String): Result? {
        val playHere = !serverAuthoritative
        for (patternStr in ClientConfig.data.senderPatterns) {
            val pattern = runCatching { Regex(patternStr) }.getOrNull() ?: continue
            val match = pattern.find(strippedText) ?: continue
            val (name, message) = match.destructured
            val senderId = resolvePlayerUuid(name)
            if (senderId == null && !ClientConfig.data.playUnpositioned) return Result(hidden = false)
            return process(message, senderId, isOwn = name == Minecraft.getInstance().player?.gameProfile?.name, playHere)
        }
        return null
    }

    /** A relay payload from the modded server. */
    fun onRelay(sender: UUID, text: String) {
        AudioEngine.start()
        // Past the vanilla cap the message can only have come through the mod channel, so
        // vanilla chat never printed it. Shorter relays ride alongside the vanilla message
        // and would double up, so only these get printed here.
        if (text.length > OutgoingChat.VANILLA_CHAT_LIMIT) {
            if (HideText.shouldHide(text)) hiddenNotice(sender)
            else Minecraft.getInstance().gui.chat.addMessage(Component.literal("<${nameOf(sender)}> $text"))
        }
        ChatsoundsPlayer.play(sender, text, isOwn = sender == Minecraft.getInstance().player?.uuid)
    }

    private fun nameOf(senderId: UUID?): String =
        senderId?.let { Minecraft.getInstance().connection?.getPlayerInfo(it)?.profile?.name } ?: "?"

    private fun hiddenNotice(senderId: UUID?) {
        Minecraft.getInstance().gui.chat.addMessage(Component.literal("Hidden chatsounds message from ${nameOf(senderId)}"))
    }

    private fun process(text: String, senderId: UUID?, isOwn: Boolean, playHere: Boolean): Result {
        val hidden = HideText.shouldHide(text)
        if (hidden) hiddenNotice(senderId)
        if (playHere) {
            AudioEngine.start()
            ChatsoundsPlayer.play(senderId, text, isOwn)
        }
        return Result(hidden)
    }

    fun resolvePlayerUuid(name: String): UUID? =
        Minecraft.getInstance().connection?.getPlayerInfo(name)?.profile?.id
}
