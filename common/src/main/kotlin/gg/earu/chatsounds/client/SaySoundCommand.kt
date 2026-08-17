package gg.earu.chatsounds.client

import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.client.Minecraft

/**
 * /saysound: the chat pipeline without the chat message. The sender always plays it
 * locally (the ';' gate is bypassed: he typed the command, he meant it), and on a modded
 * server the text also relays to everyone else in range under the usual spam and radius
 * rules. The relay leaves the sender out, so nothing plays twice.
 */
object SaySoundCommand {
    /** Wired by the loader module; false when the server has no saysound_cmd channel. */
    var sendToServer: ((String) -> Boolean)? = null

    /** Drops the ';' gate prefix; a ';' inside the text still splits contexts. */
    fun normalize(raw: String): String = raw.trim().trimStart(';').trim()

    /** Returns a message for the player, or null once playback started. */
    fun run(rawText: String): String? {
        if (!ClientConfig.data.enabled) return "chatsounds are disabled"
        DataLoader.loading?.let { return "still loading sound lists (${it.percent}%)" }

        val text = normalize(rawText)
        if (text.isEmpty()) return "nothing to play"

        sendToServer?.invoke(text)
        AudioEngine.start()
        // Own uuid, not null: own sounds stay positional so Sound Physics and Dsurround
        // process them the same way they process everyone else's.
        ChatsoundsPlayer.playDirect(Minecraft.getInstance().player?.uuid, text, isOwn = true)
        return null
    }
}
