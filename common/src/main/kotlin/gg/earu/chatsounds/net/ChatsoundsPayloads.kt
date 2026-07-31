package gg.earu.chatsounds.net

import java.util.UUID

/**
 * Wire messages shared by every loader — plain data on this branch: 1.20.1 predates
 * CustomPacketPayload/StreamCodec, so each loader module owns its own serialization
 * (SimpleChannel on Forge, PacketByteBuf channels on Fabric). All channels are OPTIONAL:
 * vanilla clients/servers interoperate untouched. Only text crosses the wire, never audio.
 */
object ChatsoundsPayloads {
    const val NAMESPACE = "chatsounds"

    sealed interface Message

    /** S->C: the server's repo_config.json — the client rebuilds its lists from it. */
    class RepoConfigPayload(val json: String) : Message {
        companion object {
            const val PATH = "repo_config"
        }
    }

    /** S->C: a chat message to sound out, positioned at the sender. */
    class RelayPayload(val sender: UUID, val text: String) : Message {
        companion object {
            const val PATH = "relay"
        }
    }

    /** C->S: the saysound/broadcast command path. */
    class SaySoundPayload(val text: String) : Message {
        companion object {
            const val PATH = "saysound"
        }
    }
}
